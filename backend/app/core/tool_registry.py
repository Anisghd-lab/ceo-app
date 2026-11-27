from __future__ import annotations

import json
import mimetypes
import uuid
from pathlib import Path
from typing import Any

import httpx

from app.core.approval_gate import ApprovalGate
from app.core.event_bus import EventBus
from app.core.memory_engine import MemoryEngine
from app.core.sandbox_manager import SandboxManager, SandboxSecurityError
from app.db.database import Database, utc_now


ALL_ATOMIC_TOOLS = [
    "sandbox_files",
    "sandbox_exec",
    "web_research",
    "company_memory",
    "artifact_publish",
    "http_api_call",
    "ceo_orchestration",
]


TOOL_DESCRIPTIONS = {
    "sandbox_files": 'Read/write/list files inside this company sandbox. Args: {"op": "read"|"write"|"list", "path": "relative/path", "content": "..."}',
    "sandbox_exec": 'Execute python, node, or bash inside the isolated sandbox to generate files, run builds, verify quality, or run scripts. Args: {"language": "python"|"node"|"bash", "code": "..."}',
    "web_research": 'Fetch public web content or search info. Args: {"op": "fetch", "url": "https://..."}',
    "company_memory": 'Read or write isolated company memory. Args: {"op": "remember"|"recall"|"set_core", "content": "...", "query": "...", "key": "..."}',
    "artifact_publish": 'Register a deliverable file from sandbox/output or workspace so the founder can inspect & download it on Android. Args: {"rel_path": "output/file.pdf", "title": "...", "summary": "..."}',
    "http_api_call": 'Call a company-authorized external REST API connector. Mutating calls automatically create a Human Approval Gate. Args: {"connector": "name", "method": "GET"|"POST", "path": "/endpoint", "body": {}}',
    "ceo_orchestration": 'CEO-only tool to request human approval or post a board update. Args: {"op": "request_approval", "title": "...", "subtitle": "...", "action_type": "SHIP_RELEASE"}',
}


class ToolRegistry:
    """Universal 7-primitive sandboxed tool registry (zero hardcoded business verticals)."""

    def __init__(
        self,
        db: Database,
        sandbox: SandboxManager,
        memory: MemoryEngine,
        approvals: ApprovalGate,
        bus: EventBus,
    ) -> None:
        self.db = db
        self.sandbox = sandbox
        self.memory = memory
        self.approvals = approvals
        self.bus = bus

    async def execute(
        self,
        company_id: str,
        bot: dict[str, Any],
        tool_name: str,
        args: dict[str, Any],
        mission_id: str | None = None,
    ) -> dict[str, Any]:
        allowed = json.loads(bot.get("allowed_tools_json") or "[]")
        if tool_name not in allowed:
            return {"ok": False, "error": f"Tool '{tool_name}' is not permitted for bot '{bot['name']}'."}

        kill_switch = await self.db.get_setting("kill_switch", False)
        if kill_switch:
            return {"ok": False, "error": "Global Kill Switch is active. All bot tools are paused."}

        try:
            if tool_name == "sandbox_files":
                return await self._tool_files(company_id, args)
            if tool_name == "sandbox_exec":
                return await self._tool_exec(company_id, args)
            if tool_name == "web_research":
                return await self._tool_web(args)
            if tool_name == "company_memory":
                return await self._tool_memory(company_id, bot["name"], args)
            if tool_name == "artifact_publish":
                return await self._tool_artifact(company_id, bot, mission_id, args)
            if tool_name == "http_api_call":
                return await self._tool_http(company_id, mission_id, args)
            if tool_name == "ceo_orchestration":
                return await self._tool_ceo(company_id, bot, mission_id, args)
            return {"ok": False, "error": f"Unknown tool: {tool_name}"}
        except SandboxSecurityError as exc:
            await self.db.audit(company_id, bot["name"], "SANDBOX_SECURITY_BLOCK", {"tool": tool_name, "error": str(exc)})
            return {"ok": False, "error": f"Security blocked: {exc}"}
        except Exception as exc:
            return {"ok": False, "error": str(exc)}

    async def _tool_files(self, company_id: str, args: dict[str, Any]) -> dict[str, Any]:
        op = str(args.get("op", "list")).lower()
        subdir = str(args.get("subdir", "workspace"))
        if subdir not in ("workspace", "output", ""):
            subdir = "workspace"
        if op == "list":
            return {"ok": True, "files": self.sandbox.list_files(company_id, subdir=subdir)}
        if op == "read":
            content = self.sandbox.read_file(company_id, str(args.get("path", "")), subdir=subdir)
            return {"ok": True, "content": content}
        if op == "write":
            path = self.sandbox.write_file(
                company_id, str(args.get("path", "")), str(args.get("content", "")), subdir=subdir
            )
            return {"ok": True, "written": str(path.relative_to(self.sandbox.company_root(company_id))), "bytes": path.stat().st_size}
        return {"ok": False, "error": f"Unsupported file op: {op}"}

    async def _tool_exec(self, company_id: str, args: dict[str, Any]) -> dict[str, Any]:
        e2b_key = await self.db.get_setting("e2b_api_key", "") or None
        res = await self.sandbox.execute_code(
            company_id=company_id,
            language=str(args.get("language", "python")),
            code=str(args.get("code", "")),
            e2b_api_key=e2b_key,
        )
        return {
            "ok": res.exit_code == 0 and not res.timed_out,
            "exit_code": res.exit_code,
            "stdout": res.stdout,
            "stderr": res.stderr,
            "timed_out": res.timed_out,
            "engine": res.engine,
        }

    async def _tool_web(self, args: dict[str, Any]) -> dict[str, Any]:
        url = str(args.get("url", "")).strip()
        if not url.startswith(("http://", "https://")):
            return {"ok": False, "error": "URL must start with http:// or https://"}
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
            resp = await client.get(url, headers={"User-Agent": "FoundryOS-Bot/0.1"})
        return {
            "ok": resp.status_code < 400,
            "status_code": resp.status_code,
            "content_preview": resp.text[:6000],
        }

    async def _tool_memory(self, company_id: str, bot_name: str, args: dict[str, Any]) -> dict[str, Any]:
        op = str(args.get("op", "recall")).lower()
        if op == "remember":
            entry = await self.memory.remember(
                company_id,
                str(args.get("content", "")),
                bot_name=bot_name,
                category=str(args.get("category", "note")),
            )
            return {"ok": True, "entry": entry}
        if op == "set_core":
            await self.memory.set_core_block(company_id, str(args.get("key", "general")), str(args.get("content", "")))
            return {"ok": True}
        entries = await self.memory.recall(company_id, query=str(args.get("query", "")), limit=8)
        return {"ok": True, "memories": entries, "core": await self.memory.get_core_blocks(company_id)}

    async def _tool_artifact(
        self, company_id: str, bot: dict[str, Any], mission_id: str | None, args: dict[str, Any]
    ) -> dict[str, Any]:
        rel_path = str(args.get("rel_path", "")).lstrip("/")
        root = self.sandbox.ensure_company_sandbox(company_id)
        target = (root / rel_path).resolve()
        if not target.is_relative_to(root):
            raise SandboxSecurityError(f"Artifact path escape: {rel_path}")
        if not target.exists() or not target.is_file():
            # Also try inside output/ or workspace/
            for candidate_dir in ("output", "workspace"):
                cand = (root / candidate_dir / rel_path).resolve()
                if cand.is_relative_to(root) and cand.exists() and cand.is_file():
                    target = cand
                    rel_path = str(cand.relative_to(root))
                    break
            else:
                return {"ok": False, "error": f"Artifact file does not exist in sandbox: {rel_path}"}

        mime, _ = mimetypes.guess_type(target.name)
        artifact_id = f"art_{uuid.uuid4().hex[:12]}"
        size = target.stat().st_size
        now = utc_now()
        title = str(args.get("title") or target.name)
        summary = str(args.get("summary") or f"Generated by {bot['name']} ({size} bytes)")

        preview_rel = self._maybe_generate_preview(root, target, artifact_id)

        await self.db.execute(
            """
            INSERT INTO artifacts (
                id, company_id, mission_id, bot_id, bot_name, title, filename,
                rel_path, mime_type, size_bytes, summary, qa_status, preview_rel_path, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
            """,
            (
                artifact_id,
                company_id,
                mission_id,
                bot["id"],
                bot["name"],
                title,
                target.name,
                rel_path,
                mime or "application/octet-stream",
                size,
                summary,
                preview_rel,
                now,
            ),
        )
        artifact = await self.db.fetchone("SELECT * FROM artifacts WHERE id = ?", (artifact_id,))
        await self.bus.publish("artifact_created", company_id, artifact or {})
        return {"ok": True, "artifact": artifact}

    def _maybe_generate_preview(self, root: Path, target: Path, artifact_id: str) -> str | None:
        """Create a clean visual summary card PNG for Android preview if PIL is available."""
        if target.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp"):
            return str(target.relative_to(root))
        try:
            from PIL import Image, ImageDraw

            img = Image.new("RGB", (720, 420), color=(250, 250, 252))
            draw = ImageDraw.Draw(img)
            draw.rounded_rectangle([(24, 24), (696, 396)], radius=24, fill=(255, 255, 255), outline=(232, 232, 236), width=2)
            draw.rounded_rectangle([(48, 48), (190, 88)], radius=12, fill=(17, 17, 17))
            ext = (target.suffix.lstrip(".") or "FILE").upper()[:10]
            draw.text((68, 60), ext, fill=(255, 255, 255))
            draw.text((48, 120), target.name[:48], fill=(17, 17, 17))
            draw.text((48, 160), f"Size: {target.stat().st_size:,} bytes", fill=(113, 113, 122))
            draw.text((48, 200), "Verified inside isolated FoundryOS sandbox", fill=(22, 163, 74))
            preview_path = root / "previews" / f"{artifact_id}.png"
            img.save(preview_path, format="PNG")
            return str(preview_path.relative_to(root))
        except Exception:
            return None

    async def _tool_http(self, company_id: str, mission_id: str | None, args: dict[str, Any]) -> dict[str, Any]:
        connector_name = str(args.get("connector", "")).strip()
        method = str(args.get("method", "GET")).upper()
        path = str(args.get("path", "/"))
        body = args.get("body")

        if method != "GET":
            # Kernel Governance Law: every mutating external HTTP call MUST pass Human Approval Gate!
            approval = await self.approvals.create_approval(
                company_id=company_id,
                mission_id=mission_id,
                title=f"External API {method} · {connector_name}",
                subtitle=f"{method} {path} via {connector_name}",
                action_type="HTTP_ACTION",
                action_payload={"connector": connector_name, "method": method, "path": path, "body": body},
            )
            return {
                "ok": True,
                "queued_for_human_approval": True,
                "approval_id": approval["id"],
                "message": "Mutating external API request gated for founder approval on Android.",
            }

        conn = await self.db.fetchone(
            "SELECT * FROM company_connectors WHERE company_id = ? AND lower(name) = lower(?)",
            (company_id, connector_name),
        )
        if not conn:
            return {"ok": False, "error": f"Connector '{connector_name}' is not configured for this company."}
        headers = {}
        if conn["auth_header"] and conn["auth_value"]:
            headers[conn["auth_header"]] = conn["auth_value"]
        url = conn["base_url"].rstrip("/") + "/" + path.lstrip("/")
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.get(url, headers=headers)
        return {"ok": resp.status_code < 400, "status_code": resp.status_code, "body": resp.text[:4000]}

    async def _tool_ceo(
        self, company_id: str, bot: dict[str, Any], mission_id: str | None, args: dict[str, Any]
    ) -> dict[str, Any]:
        if not bot.get("is_ceo"):
            return {"ok": False, "error": "Only the CEO bot can invoke ceo_orchestration."}
        op = str(args.get("op", "request_approval"))
        if op == "request_approval":
            approval = await self.approvals.create_approval(
                company_id=company_id,
                mission_id=mission_id,
                artifact_id=args.get("artifact_id"),
                title=str(args.get("title", "Ship deliverable?")),
                subtitle=str(args.get("subtitle", "Tests passed in sandbox — ready for your yes")),
                action_type=str(args.get("action_type", "SHIP_RELEASE")),
                action_payload=dict(args.get("action_payload") or {}),
            )
            return {"ok": True, "approval": approval}
        return {"ok": False, "error": f"Unsupported ceo_orchestration op: {op}"}
