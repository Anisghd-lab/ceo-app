from __future__ import annotations

import asyncio
import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.sandbox_manager import SandboxSecurityError
from app.main import FoundryState, create_app
from app.config import load_config


@pytest.fixture
async def state(tmp_path: Path):
    os.environ["FOUNDRY_PAIRING_TOKEN"] = "test_token_123"
    cfg = load_config(base_dir=tmp_path)
    st = FoundryState(cfg)
    await st.db.connect()
    yield st
    await st.db.close()


async def test_dynamic_zero_hardcoded_companies_and_isolation(state: FoundryState):
    c1 = await state.ceo.create_and_deploy_company(
        "Une entreprise qui cree des fichiers numeriques PDF de qualite, les verifie et les prepare pour la vente en ligne"
    )
    c2 = await state.ceo.create_and_deploy_company(
        "Un studio de developpement d'applications mobiles Android open-source avec tests et releases GitHub"
    )

    id1 = c1["company"]["id"]
    id2 = c2["company"]["id"]
    assert id1 != id2

    # Distinct dynamically generated teams, both enforcing an independent QA bot
    names1 = {b["name"] for b in c1["bots"]}
    names2 = {b["name"] for b in c2["bots"]}
    assert names1 != names2
    assert any(b["is_qa"] for b in c1["bots"])
    assert any(b["is_qa"] for b in c2["bots"])

    # Strict 1 Sandbox = 1 Company filesystem isolation
    state.sandbox.write_file(id1, "secret.txt", "company1_only")
    with pytest.raises(SandboxSecurityError):
        state.sandbox.read_file(id2, f"../../{id1}/workspace/secret.txt")

    # Isolated memory per company
    await state.memory.remember(id1, "Pricing tier is 29 EUR for PDF packs", bot_name="Memory")
    mem2 = await state.memory.recall(id2, query="Pricing")
    assert all("29 EUR" not in m["content"] for m in mem2)


async def test_sandbox_exec_strips_host_secrets(state: FoundryState, monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-super-secret-host-key")
    monkeypatch.setenv("GH_TOKEN", "ghp_super_secret")
    c = await state.ceo.create_and_deploy_company("Studio de recherche automatise")
    cid = c["company"]["id"]

    res = await state.sandbox.execute_code(
        cid,
        "python",
        "import os; print(os.environ.get('OPENAI_API_KEY', 'MISSING'), os.environ.get('GH_TOKEN', 'MISSING'))",
    )
    assert res.exit_code == 0
    assert "MISSING MISSING" in res.stdout
    assert "sk-super-secret" not in res.stdout


async def test_end_to_end_mission_real_pdf_qa_and_idempotent_approval(state: FoundryState):
    c = await state.ceo.create_and_deploy_company(
        "Entreprise de guides numeriques tech haute qualite avec verification QA avant mise en vente"
    )
    cid = c["company"]["id"]

    result = await state.ceo.run_mission(
        cid,
        title="Guide Architecture IA 2026",
        goal="Produire un guide PDF structure pret a vendre et le verifier integralement",
    )
    assert result["status"] == "PENDING_APPROVAL"
    assert result["qa_passed"] is True
    assert result["qa_score"] >= 90
    assert len(result["artifacts"]) == 1

    art = result["artifacts"][0]
    pdf_path = state.sandbox.resolve_path(cid, art["rel_path"], subdir="")
    assert pdf_path.exists()
    assert pdf_path.read_bytes().startswith(b"%PDF")
    assert art["preview_rel_path"] is not None
    assert state.sandbox.resolve_path(cid, art["preview_rel_path"], subdir="").exists()

    # Concurrency / Idempotency stress test: 10 simultaneous Android/Desktop "Ship" taps
    approval_id = result["approval"]["id"]
    decisions = await asyncio.gather(
        *[
            state.approvals.decide(approval_id, "APPROVED", device=f"Android-{i}")
            for i in range(10)
        ]
    )
    executed_once = [d for d in decisions if not d.get("idempotent_replay")]
    replays = [d for d in decisions if d.get("idempotent_replay")]
    assert len(executed_once) == 1
    assert len(replays) == 9
    assert all(d["status"] in ("APPROVED", "EXECUTED") for d in decisions)

    # Board meeting reflects shipped work
    board = await state.ceo.generate_board_meeting(cid)
    assert board["metrics"]["shipped"] == 1
    assert board["metrics"]["qa_passed"] == 1
    assert board["metrics"]["decisions_approved"] == 1


async def test_mutating_http_tool_requires_human_approval_and_kill_switch(state: FoundryState):
    c = await state.ceo.create_and_deploy_company("Boutique produits numeriques avec API externe")
    cid = c["company"]["id"]
    bot = next(b for b in c["bots"] if "http_api_call" in b["allowed_tools"])
    bot_row = await state.db.fetchone("SELECT * FROM bots WHERE id = ?", (bot["id"],))

    res = await state.tools.execute(
        cid,
        bot_row,
        "http_api_call",
        {"connector": "Shopify", "method": "POST", "path": "/products.json", "body": {"title": "Pack v1"}},
    )
    assert res["ok"] is True
    assert res["queued_for_human_approval"] is True

    # Activate Global Kill Switch -> tools immediately blocked
    await state.db.set_setting("kill_switch", True)
    blocked = await state.tools.execute(cid, bot_row, "sandbox_files", {"op": "list"})
    assert blocked["ok"] is False
    assert "Kill Switch" in blocked["error"]


def test_fastapi_rest_endpoints(tmp_path: Path):
    os.environ["FOUNDRY_PAIRING_TOKEN"] = "api_secret_tok"
    app = create_app(base_dir=tmp_path)
    headers = {"X-Foundry-Token": "api_secret_tok"}

    with TestClient(app) as client:
        # Health is public
        h = client.get("/api/health")
        assert h.status_code == 200 and h.json()["ok"] is True

        # Auth enforced
        unauth = client.get("/api/companies")
        assert unauth.status_code == 401

        # Propose + deploy dynamic company
        prop = client.post(
            "/api/companies/propose",
            headers=headers,
            json={"description": "Agence creation ebooks & templates numeriques verifies"},
        )
        assert prop.status_code == 200
        proposal = prop.json()["proposal"]
        assert len(proposal["bots"]) >= 4

        created = client.post(
            "/api/companies",
            headers=headers,
            json={"description": "Agence creation ebooks & templates numeriques verifies", "proposal": proposal},
        )
        assert created.status_code == 200
        cid = created.json()["company"]["id"]

        # Chat mission triggers real artifact + approval
        chat = client.post(
            f"/api/companies/{cid}/chat",
            headers=headers,
            json={"text": "Cree notre premier pack de templates PDF pro v1.0"},
        )
        assert chat.status_code == 200
        art_id = chat.json()["mission"]["artifacts"][0]["id"]
        apr_id = chat.json()["mission"]["approval"]["id"]

        # Download & preview artifact endpoints work
        dl = client.get(f"/api/artifacts/{art_id}/download", headers=headers)
        assert dl.status_code == 200 and dl.content.startswith(b"%PDF")
        pv = client.get(f"/api/artifacts/{art_id}/preview", headers=headers)
        assert pv.status_code == 200

        # Approve via Android endpoint
        dec = client.post(
            f"/api/approvals/{apr_id}/decide",
            headers=headers,
            json={"decision": "APPROVED", "device": "Pixel Android"},
        )
        assert dec.status_code == 200
        assert dec.json()["approval"]["status"] == "EXECUTED"
