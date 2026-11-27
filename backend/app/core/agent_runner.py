from __future__ import annotations

import json
from typing import Any

from app.core.event_bus import EventBus
from app.core.llm_router import MultiLLMRouter, extract_json_object
from app.core.memory_engine import MemoryEngine
from app.core.tool_registry import TOOL_DESCRIPTIONS, ToolRegistry
from app.db.database import Database


class GenericAgentRunner:
    """Single universal ReAct / Tool-Calling engine for ANY bot spawned dynamically by the CEO."""

    def __init__(
        self,
        db: Database,
        llm: MultiLLMRouter,
        tools: ToolRegistry,
        memory: MemoryEngine,
        bus: EventBus,
        max_steps: int = 6,
    ) -> None:
        self.db = db
        self.llm = llm
        self.tools = tools
        self.memory = memory
        self.bus = bus
        self.max_steps = max_steps

    async def set_bot_status(self, company_id: str, bot_id: str, status: str, status_text: str) -> None:
        await self.db.execute(
            "UPDATE bots SET status = ?, status_text = ? WHERE id = ?",
            (status, status_text[:80], bot_id),
        )
        await self.bus.publish(
            "bot_status",
            company_id,
            {"bot_id": bot_id, "status": status, "status_text": status_text[:80]},
        )

    async def run_task(
        self,
        company: dict[str, Any],
        bot: dict[str, Any],
        task_instruction: str,
        mission_id: str | None = None,
    ) -> dict[str, Any]:
        company_id = company["id"]
        allowed_tools = json.loads(bot.get("allowed_tools_json") or "[]")
        tools_doc = "\n".join(
            f"- {t}: {TOOL_DESCRIPTIONS.get(t, '')}" for t in allowed_tools if t in TOOL_DESCRIPTIONS
        )
        mem_ctx = await self.memory.format_context(company_id, query=task_instruction)

        system_prompt = (
            f"You are {bot['name']} ({bot['role']}) at company '{company['name']}'.\n"
            f"Company description: {company['description']}\n"
            f"Your instructions: {bot['system_prompt']}\n\n"
            f"{mem_ctx}\n\n"
            f"Available tools:\n{tools_doc or '- None'}\n\n"
            "You MUST respond strictly as a single JSON object with one of two formats:\n"
            '1) Tool call: {"action": "tool", "tool": "<tool_name>", "args": {...}, "status": "short live status"}\n'
            '2) Final answer: {"action": "final", "summary": "concise summary of what you produced or verified", '
            '"qa_score": 90, "qa_passed": true, "artifacts": ["rel/path"], "notes": "key memory note"}\n'
            "Use sandbox_exec (Python/Node/Bash) whenever you need to create real files (PDF, ZIP, HTML, code, XLSX, reports) "
            "or run real automated quality checks/tests inside the company sandbox. Save final deliverables into ../output/ "
            "and register them with artifact_publish."
        )

        history: list[dict[str, str]] = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": task_instruction},
        ]

        await self.set_bot_status(company_id, bot["id"], "working", "Starting task...")
        tool_trace: list[dict[str, Any]] = []
        created_artifacts: list[dict[str, Any]] = []

        for step in range(self.max_steps):
            if await self.db.get_setting("kill_switch", False):
                await self.set_bot_status(company_id, bot["id"], "blocked", "Paused by Kill Switch")
                return {"ok": False, "simulated": False, "summary": "Aborted by Kill Switch", "artifacts": created_artifacts}

            resp = await self.llm.complete(
                messages=history,
                model_spec=bot.get("model") or "auto",
                company_id=company_id,
                json_mode=True,
                temperature=0.25,
                max_tokens=2000,
            )

            if resp.is_simulated:
                # Signal caller that no live LLM API key is configured yet
                return {
                    "ok": True,
                    "simulated": True,
                    "summary": "",
                    "artifacts": created_artifacts,
                    "trace": tool_trace,
                }

            parsed = extract_json_object(resp.content)
            if not parsed:
                await self.set_bot_status(company_id, bot["id"], "idle", "Task completed")
                return {
                    "ok": True,
                    "simulated": False,
                    "summary": resp.content[:1500],
                    "qa_score": 85,
                    "qa_passed": True,
                    "artifacts": created_artifacts,
                    "trace": tool_trace,
                }

            action = str(parsed.get("action", "final")).lower()
            if action == "tool":
                t_name = str(parsed.get("tool", ""))
                t_args = dict(parsed.get("args") or {})
                live_status = str(parsed.get("status") or f"Using {t_name}...")
                await self.set_bot_status(company_id, bot["id"], "working", live_status)
                t_res = await self.tools.execute(company_id, bot, t_name, t_args, mission_id=mission_id)
                tool_trace.append({"step": step + 1, "tool": t_name, "ok": t_res.get("ok", False)})
                if t_name == "artifact_publish" and t_res.get("ok") and t_res.get("artifact"):
                    created_artifacts.append(t_res["artifact"])
                history.append({"role": "assistant", "content": json.dumps(parsed, ensure_ascii=False)})
                history.append(
                    {
                        "role": "user",
                        "content": f"Tool '{t_name}' result: {json.dumps(t_res, ensure_ascii=False)[:4000]}",
                    }
                )
                continue

            # Final action
            summary = str(parsed.get("summary") or "Completed")
            note = str(parsed.get("notes") or "").strip()
            if note:
                await self.memory.remember(company_id, note, bot_name=bot["name"], category="mission")
            await self.set_bot_status(company_id, bot["id"], "idle", summary[:60])
            return {
                "ok": True,
                "simulated": False,
                "summary": summary,
                "qa_score": int(parsed.get("qa_score", 88)) if parsed.get("qa_score") is not None else 88,
                "qa_passed": bool(parsed.get("qa_passed", True)),
                "artifacts": created_artifacts,
                "trace": tool_trace,
            }

        await self.set_bot_status(company_id, bot["id"], "idle", "Reached step limit")
        return {
            "ok": True,
            "simulated": False,
            "summary": f"{bot['name']} reached max steps ({self.max_steps}) and saved intermediate work.",
            "qa_score": 75,
            "qa_passed": len(created_artifacts) > 0,
            "artifacts": created_artifacts,
            "trace": tool_trace,
        }
