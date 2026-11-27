from __future__ import annotations

import json
import uuid
from typing import Any

import httpx

from app.core.event_bus import EventBus
from app.db.database import Database, utc_now


class ApprovalGate:
    """Idempotent Human-in-the-Loop Gate with optimistic SQLite locking."""

    def __init__(self, db: Database, bus: EventBus) -> None:
        self.db = db
        self.bus = bus

    async def create_approval(
        self,
        company_id: str,
        title: str,
        subtitle: str,
        action_type: str,
        action_payload: dict[str, Any],
        mission_id: str | None = None,
        artifact_id: str | None = None,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        if idempotency_key:
            existing = await self.db.fetchone(
                "SELECT * FROM approvals WHERE idempotency_key = ?", (idempotency_key,)
            )
            if existing:
                return self._hydrate(existing)

        approval_id = f"apr_{uuid.uuid4().hex[:12]}"
        now = utc_now()
        key = idempotency_key or f"key_{approval_id}"
        await self.db.execute(
            """
            INSERT INTO approvals (
                id, company_id, mission_id, artifact_id, title, subtitle,
                action_type, action_payload_json, status, idempotency_key, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
            """,
            (
                approval_id,
                company_id,
                mission_id,
                artifact_id,
                title,
                subtitle,
                action_type,
                json.dumps(action_payload, ensure_ascii=False),
                key,
                now,
            ),
        )
        await self.db.audit(company_id, "CEO", "APPROVAL_CREATED", {"approval_id": approval_id, "title": title})

        row = await self.db.fetchone("SELECT * FROM approvals WHERE id = ?", (approval_id,))
        hydrated = self._hydrate(row)

        # Post inline approval card into company chat (exact video UX)
        msg_id = f"msg_{uuid.uuid4().hex[:12]}"
        await self.db.execute(
            """
            INSERT INTO messages (id, company_id, sender_type, bot_name, content, card_type, card_payload_json, created_at)
            VALUES (?, ?, 'bot', 'CEO', ?, 'approval', ?, ?)
            """,
            (
                msg_id,
                company_id,
                f"{title} — waiting for your yes.",
                json.dumps(hydrated, ensure_ascii=False),
                now,
            ),
        )

        await self.bus.publish("approval_created", company_id, hydrated)
        await self.bus.send_push_notification(
            title=f"CEO · {title}",
            message=f"{subtitle}. It needs your yes.",
            approval_id=approval_id,
        )
        return hydrated

    async def decide(
        self,
        approval_id: str,
        decision: str,
        device: str = "Android",
        feedback: str = "",
    ) -> dict[str, Any]:
        norm = decision.strip().upper()
        if norm not in ("APPROVED", "REJECTED"):
            raise ValueError("Decision must be APPROVED or REJECTED")

        now = utc_now()
        # Optimistic atomic transition: only ONE concurrent caller can claim PENDING -> APPROVED/REJECTED
        cur = await self.db.execute(
            """
            UPDATE approvals
            SET status = ?, decided_by_device = ?, feedback = ?, decided_at = ?
            WHERE id = ? AND status = 'PENDING'
            """,
            (norm, device, feedback, now, approval_id),
        )

        row = await self.db.fetchone("SELECT * FROM approvals WHERE id = ?", (approval_id,))
        if not row:
            raise LookupError(f"Approval {approval_id} not found")

        if cur.rowcount == 0:
            # Already decided by another device or retry -> idempotent return!
            hydrated = self._hydrate(row)
            hydrated["idempotent_replay"] = True
            return hydrated

        hydrated = self._hydrate(row)
        company_id = hydrated["company_id"]
        exec_result: dict[str, Any] = {"status": norm}

        if norm == "APPROVED":
            exec_result = await self._execute_approved_action(hydrated)
            final_status = "EXECUTED" if exec_result.get("ok", True) else "FAILED"
            await self.db.execute(
                "UPDATE approvals SET status = ?, execution_result_json = ? WHERE id = ?",
                (final_status, json.dumps(exec_result, ensure_ascii=False), approval_id),
            )
            hydrated["status"] = final_status
            hydrated["execution_result"] = exec_result

            if hydrated.get("mission_id"):
                await self.db.execute(
                    "UPDATE missions SET status = ?, updated_at = ? WHERE id = ?",
                    ("COMPLETED" if final_status == "EXECUTED" else "FAILED", utc_now(), hydrated["mission_id"]),
                )

            # Update inline chat message & append confirmation banner matching video ("You approved on Android")
            await self._sync_approval_chat_card(hydrated)
            confirm_id = f"msg_{uuid.uuid4().hex[:12]}"
            await self.db.execute(
                """
                INSERT INTO messages (id, company_id, sender_type, bot_name, content, card_type, card_payload_json, created_at)
                VALUES (?, ?, 'system', 'CEO', ?, 'approval_confirmed', ?, ?)
                """,
                (
                    confirm_id,
                    company_id,
                    f"You approved {hydrated['title']} on {device}",
                    json.dumps({"approval_id": approval_id, "device": device, "result": exec_result}, ensure_ascii=False),
                    utc_now(),
                ),
            )
        else:
            if hydrated.get("mission_id"):
                await self.db.execute(
                    "UPDATE missions SET status = 'REJECTED', qa_notes = ?, updated_at = ? WHERE id = ?",
                    (feedback or "Rejected by founder", utc_now(), hydrated["mission_id"]),
                )
            await self._sync_approval_chat_card(hydrated)

        await self.db.audit(
            company_id,
            f"Human({device})",
            f"APPROVAL_{hydrated['status']}",
            {"approval_id": approval_id, "feedback": feedback, "result": exec_result},
        )
        await self.bus.publish("approval_updated", company_id, hydrated)
        hydrated["idempotent_replay"] = False
        return hydrated

    async def _sync_approval_chat_card(self, approval: dict[str, Any]) -> None:
        msgs = await self.db.fetchall(
            "SELECT id, card_payload_json FROM messages WHERE company_id = ? AND card_type = 'approval'",
            (approval["company_id"],),
        )
        for m in msgs:
            try:
                payload = json.loads(m["card_payload_json"] or "{}")
            except json.JSONDecodeError:
                continue
            if payload.get("id") == approval["id"]:
                await self.db.execute(
                    "UPDATE messages SET card_payload_json = ? WHERE id = ?",
                    (json.dumps(approval, ensure_ascii=False), m["id"]),
                )

    async def _execute_approved_action(self, approval: dict[str, Any]) -> dict[str, Any]:
        action_type = approval["action_type"]
        payload = approval.get("action_payload") or {}
        company_id = approval["company_id"]

        if action_type == "HTTP_ACTION":
            connector_name = payload.get("connector")
            method = str(payload.get("method", "POST")).upper()
            path = str(payload.get("path", ""))
            body = payload.get("body")
            conn = await self.db.fetchone(
                "SELECT * FROM company_connectors WHERE company_id = ? AND lower(name) = lower(?)",
                (company_id, str(connector_name or "")),
            )
            if not conn:
                return {"ok": True, "mode": "export_kit", "note": f"Connector '{connector_name}' not configured; deliverable ready for manual export."}
            if not conn["live_enabled"]:
                return {"ok": True, "mode": "dry_run", "note": f"Dry-run logged for {method} {conn['base_url']}{path} (enable Live Mode in connector settings to dispatch)."}
            headers = {"Content-Type": "application/json"}
            if conn["auth_header"] and conn["auth_value"]:
                headers[conn["auth_header"]] = conn["auth_value"]
            url = conn["base_url"].rstrip("/") + "/" + path.lstrip("/")
            try:
                async with httpx.AsyncClient(timeout=20.0) as client:
                    resp = await client.request(method, url, headers=headers, json=body)
                return {"ok": resp.status_code < 400, "mode": "live", "status_code": resp.status_code, "body_preview": resp.text[:500]}
            except Exception as exc:
                return {"ok": False, "mode": "live", "error": str(exc)}

        if action_type == "DEPLOY_TEAM":
            await self.db.execute(
                "UPDATE companies SET status = 'ACTIVE', updated_at = ? WHERE id = ?",
                (utc_now(), company_id),
            )
            return {"ok": True, "mode": "deployed", "note": "Company sandbox & bots activated."}

        # Default (PUBLISH_ARTIFACT / SHIP_RELEASE / CUSTOM)
        return {
            "ok": True,
            "mode": "shipped",
            "note": payload.get("completion_message") or "Shipped and archived in company ledger.",
        }

    def _hydrate(self, row: dict[str, Any] | None) -> dict[str, Any]:
        if not row:
            return {}
        out = dict(row)
        try:
            out["action_payload"] = json.loads(out.pop("action_payload_json") or "{}")
        except json.JSONDecodeError:
            out["action_payload"] = {}
        raw_res = out.pop("execution_result_json", None)
        if raw_res:
            try:
                out["execution_result"] = json.loads(raw_res)
            except json.JSONDecodeError:
                out["execution_result"] = {"raw": raw_res}
        else:
            out["execution_result"] = None
        return out
