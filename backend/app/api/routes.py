from __future__ import annotations

import json
import uuid
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from app.core.ceo_architect import CompanyProposal, ProposedBotSpec
from app.db.database import utc_now


class ProposeCompanyRequest(BaseModel):
    description: str = Field(min_length=5, max_length=2000)
    ceo_model: str = "auto"


class DeployCompanyRequest(BaseModel):
    description: str = Field(min_length=5, max_length=2000)
    ceo_model: str = "auto"
    proposal: CompanyProposal | None = None


class ChatRequest(BaseModel):
    text: str = Field(min_length=1, max_length=4000)
    target_bot_id: str | None = None


class MissionRequest(BaseModel):
    title: str = Field(min_length=2, max_length=120)
    goal: str = Field(min_length=5, max_length=4000)


class ApprovalDecisionRequest(BaseModel):
    decision: str  # APPROVED | REJECTED
    device: str = "Android"
    feedback: str = ""


class ConnectorCreateRequest(BaseModel):
    name: str = Field(min_length=2, max_length=64)
    base_url: str = Field(min_length=8, max_length=300)
    auth_header: str = "Authorization"
    auth_value: str = ""
    live_enabled: bool = False


class BotModelUpdateRequest(BaseModel):
    model: str


class SettingsUpdateRequest(BaseModel):
    llm_providers: dict[str, Any] | None = None
    e2b_api_key: str | None = None
    ntfy_topic: str | None = None
    kill_switch: bool | None = None


def build_router(state: Any) -> APIRouter:
    router = APIRouter(prefix="/api")

    async def verify_token(
        x_foundry_token: str | None = Header(default=None),
        token: str | None = Query(default=None),
    ) -> None:
        supplied = x_foundry_token or token
        if supplied != state.config.pairing_token:
            raise HTTPException(status_code=401, detail="Invalid FoundryOS pairing token")

    @router.get("/health")
    async def health() -> dict[str, Any]:
        return {
            "ok": True,
            "service": "FoundryOS Universal Kernel",
            "version": "0.1.0",
            "kill_switch": bool(await state.db.get_setting("kill_switch", False)),
            "providers": await state.llm.available_providers(),
        }

    @router.get("/companies", dependencies=[Depends(verify_token)])
    async def list_companies() -> dict[str, Any]:
        companies = await state.db.fetchall("SELECT * FROM companies ORDER BY created_at DESC")
        enriched: list[dict[str, Any]] = []
        for c in companies:
            bots = await state.db.fetchall("SELECT * FROM bots WHERE company_id = ? ORDER BY is_ceo DESC, created_at ASC", (c["id"],))
            pending = await state.db.fetchone(
                "SELECT COUNT(*) AS cnt FROM approvals WHERE company_id = ? AND status = 'PENDING'", (c["id"],)
            )
            artifacts = await state.db.fetchone(
                "SELECT COUNT(*) AS cnt FROM artifacts WHERE company_id = ?", (c["id"],)
            )
            enriched.append(
                {
                    **c,
                    "bots_count": len(bots),
                    "pending_approvals": int(pending["cnt"]) if pending else 0,
                    "artifacts_count": int(artifacts["cnt"]) if artifacts else 0,
                    "bots": [_hydrate_bot(b) for b in bots],
                }
            )
        return {"companies": enriched}

    @router.post("/companies/propose", dependencies=[Depends(verify_token)])
    async def propose_company(req: ProposeCompanyRequest) -> dict[str, Any]:
        proposal = await state.ceo.propose_company(req.description, ceo_model=req.ceo_model)
        return {"proposal": proposal.model_dump()}

    @router.post("/companies", dependencies=[Depends(verify_token)])
    async def create_company(req: DeployCompanyRequest) -> dict[str, Any]:
        return await state.ceo.create_and_deploy_company(
            description=req.description,
            proposal=req.proposal,
            ceo_model=req.ceo_model,
        )

    @router.delete("/companies/{company_id}", dependencies=[Depends(verify_token)])
    async def delete_company(company_id: str) -> dict[str, Any]:
        await state.db.execute("DELETE FROM companies WHERE id = ?", (company_id,))
        state.sandbox.destroy_company_sandbox(company_id)
        await state.db.audit(company_id, "Human", "COMPANY_DELETED", {})
        return {"ok": True, "deleted": company_id}

    @router.get("/companies/{company_id}", dependencies=[Depends(verify_token)])
    async def get_company_detail(company_id: str) -> dict[str, Any]:
        company = await state.db.fetchone("SELECT * FROM companies WHERE id = ?", (company_id,))
        if not company:
            raise HTTPException(status_code=404, detail="Company not found")
        bots = await state.db.fetchall("SELECT * FROM bots WHERE company_id = ? ORDER BY is_ceo DESC, created_at ASC", (company_id,))
        messages = await state.db.fetchall("SELECT * FROM messages WHERE company_id = ? ORDER BY created_at ASC LIMIT 120", (company_id,))
        connectors = await state.db.fetchall(
            "SELECT id, company_id, name, base_url, auth_header, live_enabled, created_at FROM company_connectors WHERE company_id = ?",
            (company_id,),
        )
        return {
            "company": company,
            "bots": [_hydrate_bot(b) for b in bots],
            "messages": [_hydrate_message(m) for m in messages],
            "connectors": connectors,
            "memory_count": await state.memory.count(company_id),
        }

    @router.post("/companies/{company_id}/chat", dependencies=[Depends(verify_token)])
    async def send_chat(company_id: str, req: ChatRequest) -> dict[str, Any]:
        try:
            return await state.ceo.handle_founder_message(company_id, req.text, target_bot_id=req.target_bot_id)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @router.post("/companies/{company_id}/missions", dependencies=[Depends(verify_token)])
    async def start_mission(company_id: str, req: MissionRequest) -> dict[str, Any]:
        try:
            return await state.ceo.run_mission(company_id, title=req.title, goal=req.goal)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @router.post("/companies/{company_id}/board", dependencies=[Depends(verify_token)])
    async def board_meeting(company_id: str) -> dict[str, Any]:
        try:
            return await state.ceo.generate_board_meeting(company_id)
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @router.patch("/companies/{company_id}/bots/{bot_id}/model", dependencies=[Depends(verify_token)])
    async def update_bot_model(company_id: str, bot_id: str, req: BotModelUpdateRequest) -> dict[str, Any]:
        await state.db.execute("UPDATE bots SET model = ? WHERE id = ? AND company_id = ?", (req.model, bot_id, company_id))
        return {"ok": True, "bot_id": bot_id, "model": req.model}

    @router.post("/companies/{company_id}/connectors", dependencies=[Depends(verify_token)])
    async def add_connector(company_id: str, req: ConnectorCreateRequest) -> dict[str, Any]:
        cid = f"con_{uuid.uuid4().hex[:10]}"
        await state.db.execute(
            """
            INSERT INTO company_connectors (id, company_id, name, base_url, auth_header, auth_value, live_enabled, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (cid, company_id, req.name, req.base_url, req.auth_header, req.auth_value, 1 if req.live_enabled else 0, utc_now()),
        )
        return {"ok": True, "id": cid, "name": req.name, "live_enabled": req.live_enabled}

    @router.get("/artifacts", dependencies=[Depends(verify_token)])
    async def list_all_artifacts(company_id: str | None = None) -> dict[str, Any]:
        if company_id:
            rows = await state.db.fetchall("SELECT * FROM artifacts WHERE company_id = ? ORDER BY created_at DESC", (company_id,))
        else:
            rows = await state.db.fetchall("SELECT * FROM artifacts ORDER BY created_at DESC LIMIT 100")
        return {"artifacts": rows}

    @router.get("/artifacts/{artifact_id}/download", dependencies=[Depends(verify_token)])
    async def download_artifact(artifact_id: str) -> FileResponse:
        art = await state.db.fetchone("SELECT * FROM artifacts WHERE id = ?", (artifact_id,))
        if not art:
            raise HTTPException(status_code=404, detail="Artifact not found")
        target = state.sandbox.resolve_path(art["company_id"], art["rel_path"], subdir="")
        if not target.exists():
            raise HTTPException(status_code=404, detail="Artifact file missing from sandbox")
        return FileResponse(path=str(target), filename=art["filename"], media_type=art["mime_type"])

    @router.get("/artifacts/{artifact_id}/preview", dependencies=[Depends(verify_token)])
    async def preview_artifact(artifact_id: str) -> FileResponse:
        art = await state.db.fetchone("SELECT * FROM artifacts WHERE id = ?", (artifact_id,))
        if not art or not art.get("preview_rel_path"):
            raise HTTPException(status_code=404, detail="Preview not available")
        target = state.sandbox.resolve_path(art["company_id"], art["preview_rel_path"], subdir="")
        if not target.exists():
            raise HTTPException(status_code=404, detail="Preview file missing")
        return FileResponse(path=str(target), media_type="image/png")

    @router.get("/approvals", dependencies=[Depends(verify_token)])
    async def list_approvals(status: str | None = None, company_id: str | None = None) -> dict[str, Any]:
        sql = "SELECT * FROM approvals WHERE 1=1"
        params: list[Any] = []
        if status:
            sql += " AND status = ?"
            params.append(status.upper())
        if company_id:
            sql += " AND company_id = ?"
            params.append(company_id)
        sql += " ORDER BY created_at DESC LIMIT 100"
        rows = await state.db.fetchall(sql, tuple(params))
        return {"approvals": [state.approvals._hydrate(r) for r in rows]}

    @router.post("/approvals/{approval_id}/decide", dependencies=[Depends(verify_token)])
    async def decide_approval(approval_id: str, req: ApprovalDecisionRequest) -> dict[str, Any]:
        try:
            res = await state.approvals.decide(
                approval_id=approval_id,
                decision=req.decision,
                device=req.device,
                feedback=req.feedback,
            )
            return {"approval": res}
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @router.get("/settings", dependencies=[Depends(verify_token)])
    async def get_settings() -> dict[str, Any]:
        providers = await state.llm.get_providers_config()
        masked = {
            k: {
                "configured": bool(v.get("api_key")),
                "model": v.get("model", ""),
                "base_url": v.get("base_url", ""),
            }
            for k, v in providers.items()
        }
        return {
            "llm_providers": masked,
            "e2b_configured": bool(await state.db.get_setting("e2b_api_key", "")),
            "ntfy_topic": await state.db.get_setting("ntfy_topic", ""),
            "kill_switch": bool(await state.db.get_setting("kill_switch", False)),
        }

    @router.put("/settings", dependencies=[Depends(verify_token)])
    async def update_settings(req: SettingsUpdateRequest) -> dict[str, Any]:
        if req.llm_providers is not None:
            existing = await state.db.get_setting("llm_providers", {}) or {}
            for prov, cfg in req.llm_providers.items():
                cur = dict(existing.get(prov, {}))
                cur.update({k: v for k, v in cfg.items() if v is not None})
                existing[prov] = cur
            await state.db.set_setting("llm_providers", existing)
        if req.e2b_api_key is not None:
            await state.db.set_setting("e2b_api_key", req.e2b_api_key.strip())
        if req.ntfy_topic is not None:
            await state.db.set_setting("ntfy_topic", req.ntfy_topic.strip())
        if req.kill_switch is not None:
            await state.db.set_setting("kill_switch", bool(req.kill_switch))
            await state.bus.publish("kill_switch", None, {"active": bool(req.kill_switch)})
        return await get_settings()

    @router.post("/kill-switch", dependencies=[Depends(verify_token)])
    async def toggle_kill_switch(active: bool = Query(...)) -> dict[str, Any]:
        await state.db.set_setting("kill_switch", active)
        await state.db.audit(None, "Human", "KILL_SWITCH", {"active": active})
        await state.bus.publish("kill_switch", None, {"active": active})
        return {"ok": True, "kill_switch": active}

    return router


def _hydrate_bot(row: dict[str, Any]) -> dict[str, Any]:
    out = dict(row)
    try:
        out["allowed_tools"] = json.loads(out.pop("allowed_tools_json") or "[]")
    except json.JSONDecodeError:
        out["allowed_tools"] = []
    out["is_ceo"] = bool(out["is_ceo"])
    out["is_qa"] = bool(out["is_qa"])
    return out


def _hydrate_message(row: dict[str, Any]) -> dict[str, Any]:
    out = dict(row)
    raw = out.pop("card_payload_json", None)
    if raw:
        try:
            out["card_payload"] = json.loads(raw)
        except json.JSONDecodeError:
            out["card_payload"] = None
    else:
        out["card_payload"] = None
    return out
