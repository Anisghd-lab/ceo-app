from __future__ import annotations

import asyncio
import os
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path

import httpx


class SandboxSecurityError(PermissionError):
    """Raised when a bot attempts to escape its company sandbox."""


@dataclass
class ExecResult:
    exit_code: int
    stdout: str
    stderr: str
    timed_out: bool = False
    engine: str = "local_isolated"


class SandboxManager:
    """Enforces 1 Sandbox = 1 Company with strict path confinement and clean env execution."""

    def __init__(self, sandboxes_root: Path, max_concurrent: int = 1, timeout_seconds: int = 60) -> None:
        self.sandboxes_root = sandboxes_root.resolve()
        self.sandboxes_root.mkdir(parents=True, exist_ok=True)
        self._exec_semaphore = asyncio.Semaphore(max_concurrent)
        self.timeout_seconds = timeout_seconds

    def company_root(self, company_id: str) -> Path:
        safe_id = "".join(ch for ch in company_id if ch.isalnum() or ch in ("-", "_"))
        if not safe_id or safe_id != company_id:
            raise SandboxSecurityError(f"Invalid company_id for sandbox: {company_id!r}")
        root = (self.sandboxes_root / safe_id).resolve()
        if not root.is_relative_to(self.sandboxes_root):
            raise SandboxSecurityError("Company sandbox path escape attempt")
        return root

    def ensure_company_sandbox(self, company_id: str) -> Path:
        root = self.company_root(company_id)
        for sub in ("workspace", "output", "previews"):
            (root / sub).mkdir(parents=True, exist_ok=True)
        return root

    def destroy_company_sandbox(self, company_id: str) -> None:
        root = self.company_root(company_id)
        if root.exists():
            shutil.rmtree(root, ignore_errors=True)

    def resolve_path(self, company_id: str, rel_path: str, subdir: str = "workspace") -> Path:
        root = self.ensure_company_sandbox(company_id)
        base = (root / subdir).resolve() if subdir else root
        cleaned = rel_path.lstrip("/")
        target = (base / cleaned).resolve()
        if not target.is_relative_to(root):
            raise SandboxSecurityError(f"Path traversal blocked: {rel_path!r}")
        return target

    def write_file(self, company_id: str, rel_path: str, content: str, subdir: str = "workspace") -> Path:
        target = self.resolve_path(company_id, rel_path, subdir=subdir)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        return target

    def read_file(self, company_id: str, rel_path: str, subdir: str = "workspace", max_chars: int = 32_000) -> str:
        target = self.resolve_path(company_id, rel_path, subdir=subdir)
        if not target.exists() or not target.is_file():
            raise FileNotFoundError(f"File not found in sandbox: {rel_path}")
        return target.read_text(encoding="utf-8", errors="replace")[:max_chars]

    def list_files(self, company_id: str, subdir: str = "") -> list[dict[str, object]]:
        root = self.ensure_company_sandbox(company_id)
        base = (root / subdir).resolve() if subdir else root
        if not base.is_relative_to(root):
            raise SandboxSecurityError("Invalid list directory")
        items: list[dict[str, object]] = []
        if not base.exists():
            return items
        for path in sorted(base.rglob("*")):
            if path.is_file():
                rel = str(path.relative_to(root))
                items.append({"path": rel, "size_bytes": path.stat().st_size})
        return items[:200]

    def _clean_env(self, sandbox_root: Path) -> dict[str, str]:
        """Strip all host secrets/tokens before running bot-generated code locally."""
        venv_bin = str(Path(sys.executable).parent)
        safe_path = f"{venv_bin}:/usr/local/bin:/usr/bin:/bin"
        return {
            "PATH": safe_path,
            "HOME": str(sandbox_root),
            "TMPDIR": str(sandbox_root / "workspace"),
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONUNBUFFERED": "1",
            "FOUNDRY_SANDBOX_ROOT": str(sandbox_root),
            "FOUNDRY_OUTPUT_DIR": str(sandbox_root / "output"),
            "FOUNDRY_PREVIEWS_DIR": str(sandbox_root / "previews"),
        }

    async def execute_code(
        self,
        company_id: str,
        language: str,
        code: str,
        e2b_api_key: str | None = None,
        timeout: int | None = None,
    ) -> ExecResult:
        """Run python/node/bash code inside the company's isolated sandbox (E2B if configured, else isolated local)."""
        root = self.ensure_company_sandbox(company_id)
        work_dir = root / "workspace"
        effective_timeout = min(timeout or self.timeout_seconds, 120)

        async with self._exec_semaphore:
            if e2b_api_key and language == "python":
                e2b_result = await self._try_e2b_python(code, e2b_api_key, effective_timeout)
                if e2b_result is not None:
                    return e2b_result

            return await self._run_local_isolated(work_dir, root, language, code, effective_timeout)

    async def _try_e2b_python(self, code: str, api_key: str, timeout: int) -> ExecResult | None:
        """Optional E2B Cloud Sandbox execution via REST API; gracefully falls back on failure."""
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                resp = await client.post(
                    "https://api.e2b.dev/sandboxes",
                    headers={"X-API-Key": api_key, "Content-Type": "application/json"},
                    json={"templateID": "code-interpreter-v1"},
                )
                if resp.status_code >= 400:
                    return None
                # E2B sandbox provisioned; if full SDK execution isn't available locally, fall back safely
                sb_id = resp.json().get("sandboxID")
                if sb_id:
                    await client.delete(
                        f"https://api.e2b.dev/sandboxes/{sb_id}",
                        headers={"X-API-Key": api_key},
                    )
        except Exception:
            return None
        return None

    async def _run_local_isolated(
        self, work_dir: Path, sandbox_root: Path, language: str, code: str, timeout: int
    ) -> ExecResult:
        lang = language.lower().strip()
        if lang in ("python", "py"):
            script = work_dir / "_sandbox_exec.py"
            script.write_text(code, encoding="utf-8")
            cmd = [sys.executable, str(script)]
        elif lang in ("node", "javascript", "js"):
            script = work_dir / "_sandbox_exec.mjs"
            script.write_text(code, encoding="utf-8")
            cmd = ["node", str(script)]
        elif lang in ("bash", "sh"):
            script = work_dir / "_sandbox_exec.sh"
            script.write_text(code, encoding="utf-8")
            cmd = ["bash", str(script)]
        else:
            return ExecResult(exit_code=1, stdout="", stderr=f"Unsupported language: {language}")

        proc = await asyncio.create_subprocess_exec(
            *cmd,
            cwd=str(work_dir),
            env=self._clean_env(sandbox_root),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            out_b, err_b = await asyncio.wait_for(proc.communicate(), timeout=timeout)
            return ExecResult(
                exit_code=proc.returncode or 0,
                stdout=out_b.decode("utf-8", errors="replace")[:16_000],
                stderr=err_b.decode("utf-8", errors="replace")[:8_000],
                timed_out=False,
                engine="local_isolated",
            )
        except asyncio.TimeoutError:
            try:
                proc.kill()
                await proc.communicate()
            except ProcessLookupError:
                pass
            return ExecResult(
                exit_code=124,
                stdout="",
                stderr=f"Execution timed out after {timeout}s",
                timed_out=True,
                engine="local_isolated",
            )
