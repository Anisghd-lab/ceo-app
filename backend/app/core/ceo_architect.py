from __future__ import annotations

import json
import re
import uuid
from typing import Any

from pydantic import BaseModel, Field

from app.core.agent_runner import GenericAgentRunner
from app.core.approval_gate import ApprovalGate
from app.core.event_bus import EventBus
from app.core.llm_router import MultiLLMRouter, extract_json_object
from app.core.memory_engine import MemoryEngine
from app.core.sandbox_manager import SandboxManager
from app.core.tool_registry import ALL_ATOMIC_TOOLS, ToolRegistry
from app.db.database import Database, utc_now


AVATAR_PALETTE = [
    ("triangle", "#EC4899"),   # Pink
    ("hexagon", "#F97316"),    # Orange
    ("pill", "#10B981"),       # Green
    ("semicircle", "#2563EB"), # Blue
    ("squircle", "#7C3AED"),   # Purple
    ("diamond", "#0891B2"),    # Cyan
    ("star", "#EAB308"),       # Amber
]


class ProposedBotSpec(BaseModel):
    name: str
    role: str
    tagline: str
    system_prompt: str
    allowed_tools: list[str] = Field(default_factory=lambda: ["sandbox_files", "sandbox_exec", "company_memory"])
    is_ceo: bool = False
    is_qa: bool = False
    model: str = "auto"
    avatar_shape: str = "circle"
    avatar_color: str = "#111111"


class CompanyProposal(BaseModel):
    name: str
    mission_statement: str
    strategy_summary: str
    recommended_connectors: list[str] = Field(default_factory=list)
    bots: list[ProposedBotSpec]


class CEOArchitect:
    """Zero-hardcoded Dynamic CEO: reads any user company description, designs a custom team,
    deploys them in an isolated sandbox, orchestrates Producer -> Independent QA -> Human Approval."""

    def __init__(
        self,
        db: Database,
        sandbox: SandboxManager,
        llm: MultiLLMRouter,
        memory: MemoryEngine,
        tools: ToolRegistry,
        runner: GenericAgentRunner,
        approvals: ApprovalGate,
        bus: EventBus,
    ) -> None:
        self.db = db
        self.sandbox = sandbox
        self.llm = llm
        self.memory = memory
        self.tools = tools
        self.runner = runner
        self.approvals = approvals
        self.bus = bus

    async def propose_company(self, description: str, ceo_model: str = "auto") -> CompanyProposal:
        """Ask the CEO LLM to dynamically invent the right org-chart & tools for ANY company description."""
        prompt = (
            "You are the Founder's AI CEO inside FoundryOS (a universal multi-sandbox company OS).\n"
            "The human described a brand-new company they want to launch. Nothing is hardcoded by industry.\n"
            "Design the exact autonomous bot team needed to operate this specific company inside its own isolated sandbox.\n\n"
            f"Human description:\n{description}\n\n"
            f"Available atomic tools you may assign to each bot: {json.dumps(ALL_ATOMIC_TOOLS)}\n"
            "Rules:\n"
            "- Return strictly ONE JSON object.\n"
            "- Include between 3 and 6 specialist bots (NOT counting the CEO, which the kernel adds automatically).\n"
            "- MUST include at least ONE independent Quality/Verification bot (`is_qa: true`) whose sole job is auditing deliverables.\n"
            "- MUST include a Memory / Knowledge bot (`company_memory`) to keep all bots aligned.\n"
            "- Tailor every bot's name, role, tagline, system_prompt, and allowed_tools strictly to the user's description.\n"
            "- List any external APIs/connectors that would be useful (e.g. GitHub, Shopify, Stripe, Notion, etc.) in `recommended_connectors`.\n\n"
            "JSON schema:\n"
            "{\n"
            '  "name": "Short crisp company name",\n'
            '  "mission_statement": "1-sentence mission",\n'
            '  "strategy_summary": "2-sentence operating model explaining how the team works and checks quality before human approval",\n'
            '  "recommended_connectors": ["..."],\n'
            '  "bots": [\n'
            '    {"name": "...", "role": "...", "tagline": "4-6 word action subtitle", "system_prompt": "...", "allowed_tools": [...], "is_qa": false}\n'
            "  ]\n"
            "}"
        )

        resp = await self.llm.complete(
            messages=[
                {"role": "system", "content": "You are a precise organizational architect. Output valid JSON only."},
                {"role": "user", "content": prompt},
            ],
            model_spec=ceo_model,
            json_mode=True,
            temperature=0.35,
            max_tokens=1800,
        )

        parsed = extract_json_object(resp.content) if not resp.is_simulated else None
        if parsed and isinstance(parsed.get("bots"), list) and len(parsed["bots"]) >= 2:
            return self._normalize_proposal(parsed, description, ceo_model)

        # Deterministic dynamic synthesis when no live LLM key is configured yet (adapts directly to user description)
        return self._synthesize_dynamic_proposal(description, ceo_model)

    def _normalize_proposal(self, raw: dict[str, Any], description: str, ceo_model: str) -> CompanyProposal:
        ceo_bot = ProposedBotSpec(
            name="CEO",
            role="Chief Executive & Governor",
            tagline="Sets goals, hands out work",
            system_prompt=(
                f"You are the CEO of {raw.get('name', 'this company')}. Mission: {raw.get('mission_statement', description)}. "
                "Coordinate your team, enforce QA checks, and NEVER ship or publish externally without human approval."
            ),
            allowed_tools=["ceo_orchestration", "company_memory", "sandbox_files"],
            is_ceo=True,
            is_qa=False,
            model=ceo_model,
            avatar_shape="circle",
            avatar_color="#111111",
        )

        bots: list[ProposedBotSpec] = [ceo_bot]
        has_qa = False
        for idx, b in enumerate(raw.get("bots", [])[:7]):
            if not isinstance(b, dict) or not b.get("name"):
                continue
            if str(b.get("name")).strip().lower() == "ceo":
                continue
            shape, color = AVATAR_PALETTE[idx % len(AVATAR_PALETTE)]
            valid_tools = [t for t in (b.get("allowed_tools") or []) if t in ALL_ATOMIC_TOOLS and t != "ceo_orchestration"]
            if not valid_tools:
                valid_tools = ["sandbox_files", "sandbox_exec", "company_memory"]
            is_qa = bool(b.get("is_qa", False))
            if is_qa:
                has_qa = True
            bots.append(
                ProposedBotSpec(
                    name=str(b["name"])[:32],
                    role=str(b.get("role") or b["name"])[:64],
                    tagline=str(b.get("tagline") or "Executes assigned tasks")[:64],
                    system_prompt=str(b.get("system_prompt") or f"Execute {b['name']} duties rigorously."),
                    allowed_tools=valid_tools,
                    is_ceo=False,
                    is_qa=is_qa,
                    model="auto",
                    avatar_shape=shape,
                    avatar_color=color,
                )
            )

        if not has_qa:
            shape, color = AVATAR_PALETTE[len(bots) % len(AVATAR_PALETTE)]
            bots.append(
                ProposedBotSpec(
                    name="Quality Auditor",
                    role="Independent QA & Verification",
                    tagline="Verifies every deliverable",
                    system_prompt="Independently inspect and test every deliverable in the sandbox before approval.",
                    allowed_tools=["sandbox_files", "sandbox_exec", "company_memory"],
                    is_qa=True,
                    avatar_shape=shape,
                    avatar_color=color,
                )
            )

        return CompanyProposal(
            name=str(raw.get("name") or "New Venture")[:48],
            mission_statement=str(raw.get("mission_statement") or description[:180]),
            strategy_summary=str(
                raw.get("strategy_summary")
                or "Your CEO delegates work inside this isolated sandbox, runs independent QA verification, and asks for your yes before anything ships."
            ),
            recommended_connectors=[str(c) for c in (raw.get("recommended_connectors") or [])][:6],
            bots=bots,
        )

    def _synthesize_dynamic_proposal(self, description: str, ceo_model: str) -> CompanyProposal:
        """Dynamic fallback when no API key is set yet: derives tailored roles from any free-text description."""
        clean = " ".join(description.strip().split())
        words = [w.strip(".,!?;:\"'()") for w in clean.split() if len(w.strip(".,!?;:\"'()")) > 3]
        short_title = " ".join(w.capitalize() for w in words[:3]) if words else "Autonomous Venture"
        if len(short_title) > 36:
            short_title = short_title[:36].strip()

        lower = clean.lower()
        connectors: list[str] = []
        if any(k in lower for k in ("vent", "shop", "store", "vend", "gumroad", "shopify", "ecommerce", "produit", "fichier", "ebook", "digital")):
            connectors.extend(["Shopify / WooCommerce API", "Stripe Payment Links", "Export Listing Kit"])
            builder_name, builder_role, builder_tag = "Product Creator", "Digital Asset Production", "Builds & packages assets"
            dist_name, dist_role, dist_tag = "Store Publisher", "Listing & Distribution", "Prepares store listings"
        elif any(k in lower for k in ("mobile", "android", "app", "code", "dev", "saas", "software", "web", "api", "github")):
            connectors.extend(["GitHub API", "E2B Cloud Sandbox"])
            builder_name, builder_role, builder_tag = "Engineer", "Software & App Engineering", "Builds in its own sandbox"
            dist_name, dist_role, dist_tag = "Release Ops", "Packaging & Shipping", "Prepares PRs & releases"
        elif any(k in lower for k in ("content", "video", "media", "insta", "youtube", "blog", "seo", "marketing", "social")):
            connectors.extend(["CMS / Webhook API", "Analytics API"])
            builder_name, builder_role, builder_tag = "Creative Producer", "Content & Asset Studio", "Produces media & copy"
            dist_name, dist_role, dist_tag = "Growth Publisher", "Scheduling & Distribution", "Prepares channel launches"
        else:
            connectors.extend(["Custom REST Webhook", "GitHub / Storage API"])
            builder_name, builder_role, builder_tag = "Lead Specialist", "Core Production Engine", "Builds deliverables in sandbox"
            dist_name, dist_role, dist_tag = "Operations Lead", "Delivery & Publishing", "Handles external delivery"

        raw = {
            "name": short_title,
            "mission_statement": clean[:180],
            "strategy_summary": (
                f"CEO orchestrates a dedicated sandbox for '{short_title}': research & scoping, production in sandbox, "
                "independent QA verification, and founder approval before any external action."
            ),
            "recommended_connectors": connectors,
            "bots": [
                {
                    "name": "Strategist",
                    "role": "Research & Specification",
                    "tagline": "Turns goals into specs",
                    "system_prompt": f"Research market context and turn founder requests for '{clean}' into sharp specs.",
                    "allowed_tools": ["web_research", "sandbox_files", "company_memory"],
                    "is_qa": False,
                },
                {
                    "name": builder_name,
                    "role": builder_role,
                    "tagline": builder_tag,
                    "system_prompt": f"Execute production tasks for '{clean}' using sandbox scripts and register real deliverables.",
                    "allowed_tools": ["sandbox_files", "sandbox_exec", "artifact_publish", "company_memory"],
                    "is_qa": False,
                },
                {
                    "name": "Quality Auditor",
                    "role": "Independent QA & Verification",
                    "tagline": "Audits every deliverable",
                    "system_prompt": f"Audit every file/code generated for '{clean}' inside the sandbox. Reject mediocre or broken work.",
                    "allowed_tools": ["sandbox_files", "sandbox_exec", "company_memory"],
                    "is_qa": True,
                },
                {
                    "name": dist_name,
                    "role": dist_role,
                    "tagline": dist_tag,
                    "system_prompt": f"Prepare verified deliverables for external launch/distribution for '{clean}' under human approval.",
                    "allowed_tools": ["sandbox_files", "http_api_call", "artifact_publish", "company_memory"],
                    "is_qa": False,
                },
                {
                    "name": "Memory",
                    "role": "Shared Company Knowledge",
                    "tagline": "Keeps everyone on the same page",
                    "system_prompt": "Maintain clean, deduplicated company memory blocks and notes across all bots.",
                    "allowed_tools": ["company_memory", "sandbox_files"],
                    "is_qa": False,
                },
            ],
        }
        return self._normalize_proposal(raw, description, ceo_model)

    async def create_and_deploy_company(
        self,
        description: str,
        proposal: CompanyProposal | None = None,
        ceo_model: str = "auto",
    ) -> dict[str, Any]:
        """Create isolated sandbox, persist dynamically spawned bots, and seed initial founder/CEO messages."""
        prop = proposal or await self.propose_company(description, ceo_model=ceo_model)
        company_id = f"cmp_{uuid.uuid4().hex[:10]}"
        sandbox_root = self.sandbox.ensure_company_sandbox(company_id)
        now = utc_now()

        await self.db.execute(
            """
            INSERT INTO companies (
                id, name, description, mission_statement, status, sandbox_path,
                ceo_model, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
            """,
            (company_id, prop.name, description, prop.mission_statement, str(sandbox_root), ceo_model, now, now),
        )

        bots_out: list[dict[str, Any]] = []
        for idx, b in enumerate(prop.bots):
            bot_id = f"bot_{uuid.uuid4().hex[:10]}"
            await self.db.execute(
                """
                INSERT INTO bots (
                    id, company_id, name, role, tagline, system_prompt,
                    avatar_shape, avatar_color, model, allowed_tools_json,
                    is_ceo, is_qa, status, status_text, unread_count, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'idle', ?, 0, ?)
                """,
                (
                    bot_id,
                    company_id,
                    b.name,
                    b.role,
                    b.tagline,
                    b.system_prompt,
                    b.avatar_shape,
                    b.avatar_color,
                    b.model,
                    json.dumps(b.allowed_tools),
                    1 if b.is_ceo else 0,
                    1 if b.is_qa else 0,
                    b.tagline,
                    now,
                ),
            )
            bots_out.append(
                {
                    "id": bot_id,
                    "company_id": company_id,
                    "name": b.name,
                    "role": b.role,
                    "tagline": b.tagline,
                    "avatar_shape": b.avatar_shape,
                    "avatar_color": b.avatar_color,
                    "allowed_tools": b.allowed_tools,
                    "is_ceo": b.is_ceo,
                    "is_qa": b.is_qa,
                    "status": "idle",
                    "status_text": b.tagline,
                }
            )

        # Seed isolated company memory (Letta Core Block + Mem0 entry)
        await self.memory.set_core_block(company_id, "mission", prop.mission_statement)
        await self.memory.set_core_block(company_id, "strategy", prop.strategy_summary)
        await self.memory.remember(
            company_id,
            f"Company '{prop.name}' founded with {len(bots_out)} bots in isolated sandbox.",
            bot_name="Memory",
            category="founding",
        )

        # Seed exact video-style founding messages (Human bubble -> CEO reply -> Company Org Card)
        await self._add_message(company_id, "human", "", description)
        await self._add_message(
            company_id,
            "bot",
            "CEO",
            f"Hiring your company: {len(bots_out)} bots and one human, you. Nothing ships without your yes.",
        )
        await self._add_message(
            company_id,
            "bot",
            "CEO",
            "",
            card_type="company_org",
            card_payload={
                "company_name": prop.name,
                "subtitle": f"1 human · {len(bots_out)} bots · isolated sandbox",
                "recommended_connectors": prop.recommended_connectors,
                "bots": bots_out,
            },
        )
        await self.db.audit(company_id, "CEO", "COMPANY_FOUNDED", {"name": prop.name, "bot_count": len(bots_out)})
        company = await self.db.fetchone("SELECT * FROM companies WHERE id = ?", (company_id,))
        await self.bus.publish("company_created", company_id, {"company": company, "bots": bots_out})
        return {"company": company, "bots": bots_out, "recommended_connectors": prop.recommended_connectors}

    async def handle_founder_message(self, company_id: str, text: str, target_bot_id: str | None = None) -> dict[str, Any]:
        """Handle any chat command from the founder: converse with CEO or automatically launch a full mission."""
        company = await self.db.fetchone("SELECT * FROM companies WHERE id = ?", (company_id,))
        if not company:
            raise LookupError("Company not found")

        await self._add_message(company_id, "human", "", text)

        if target_bot_id:
            bot = await self.db.fetchone("SELECT * FROM bots WHERE id = ? AND company_id = ?", (target_bot_id, company_id))
            if bot and not bot["is_ceo"]:
                res = await self.runner.run_task(company, bot, text)
                reply = res.get("summary") or f"{bot['name']} processed your instruction in the sandbox."
                msg = await self._add_message(company_id, "bot", bot["name"], reply, bot_id=bot["id"])
                return {"mode": "direct_bot", "message": msg}

        # Check if founder is asking for a board update or launching a deliverable mission
        lower = text.lower()
        if any(k in lower for k in ("board meeting", "bilan", "rapport mensuel", "report", "metrics", "kpi")):
            board = await self.generate_board_meeting(company_id)
            return {"mode": "board_meeting", "board": board}

        # Execute end-to-end dynamic mission (Producer -> Independent QA -> Human Approval Gate)
        mission_result = await self.run_mission(company_id, title=text[:64], goal=text)
        return {"mode": "mission", "mission": mission_result}

    async def run_mission(self, company_id: str, title: str, goal: str) -> dict[str, Any]:
        """Orchestrate any dynamic goal: Producer Bot creates real files in sandbox -> Independent QA verifies -> CEO requests Human Approval."""
        company = await self.db.fetchone("SELECT * FROM companies WHERE id = ?", (company_id,))
        if not company:
            raise LookupError("Company not found")

        bots = await self.db.fetchall("SELECT * FROM bots WHERE company_id = ? ORDER BY is_ceo DESC, created_at ASC", (company_id,))
        ceo_bot = next((b for b in bots if b["is_ceo"]), bots[0])
        qa_bot = next((b for b in bots if b["is_qa"]), bots[-1])
        producer_bot = next(
            (b for b in bots if not b["is_ceo"] and not b["is_qa"] and "sandbox_exec" in (b.get("allowed_tools_json") or "")),
            next((b for b in bots if not b["is_ceo"] and not b["is_qa"]), ceo_bot),
        )

        mission_id = f"msn_{uuid.uuid4().hex[:10]}"
        now = utc_now()
        await self.db.execute(
            """
            INSERT INTO missions (id, company_id, title, goal, status, assigned_bot_id, qa_bot_id, iteration, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, 1, ?, ?)
            """,
            (mission_id, company_id, title, goal, producer_bot["id"], qa_bot["id"], now, now),
        )
        await self.bus.publish("mission_started", company_id, {"mission_id": mission_id, "title": title})

        # 1. Producer Bot executes in sandbox
        await self.runner.set_bot_status(company_id, ceo_bot["id"], "working", f"Assigned to {producer_bot['name']}")
        prod_res = await self.runner.run_task(
            company,
            producer_bot,
            f"Mission '{title}': {goal}\nCreate real deliverable file(s) inside ../output/ using sandbox_exec or sandbox_files, then register them with artifact_publish.",
            mission_id=mission_id,
        )

        artifacts: list[dict[str, Any]] = list(prod_res.get("artifacts") or [])
        if prod_res.get("simulated") or not artifacts:
            # Execute a real sandbox Python build script so real verifiable files exist on disk even without live LLM key
            fallback_art = await self._produce_real_sandbox_deliverable(company, producer_bot, mission_id, title, goal)
            if fallback_art:
                artifacts.append(fallback_art)

        # 2. Independent QA Bot verifies every artifact (never same bot as producer)
        await self.db.execute("UPDATE missions SET status = 'QA_REVIEW', updated_at = ? WHERE id = ?", (utc_now(), mission_id))
        await self.runner.set_bot_status(company_id, qa_bot["id"], "working", "Auditing deliverable in sandbox...")

        qa_summary, qa_score, qa_passed = await self._run_independent_qa(company, qa_bot, mission_id, artifacts, goal)

        for art in artifacts:
            await self.db.execute(
                "UPDATE artifacts SET qa_status = ?, qa_score = ?, qa_report = ? WHERE id = ?",
                ("PASSED" if qa_passed else "FAILED", qa_score, qa_summary, art["id"]),
            )
            art["qa_status"] = "PASSED" if qa_passed else "FAILED"
            art["qa_score"] = qa_score
            art["qa_report"] = qa_summary

            # Emit inline Deliverable / PR-style Card in chat (matching video)
            await self._add_message(
                company_id,
                "bot",
                producer_bot["name"],
                f"{producer_bot['name']} prepared deliverable",
                bot_id=producer_bot["id"],
                card_type="artifact",
                card_payload=art,
            )

        await self.memory.remember(
            company_id,
            f"Mission '{title}' audited by {qa_bot['name']}: score {qa_score}/100 ({'PASSED' if qa_passed else 'FAILED'}).",
            bot_name="Memory",
            category="qa",
        )
        await self.runner.set_bot_status(company_id, qa_bot["id"], "idle", f"QA {qa_score}/100")

        # 3. CEO opens Human Approval Gate (exact video flow: "Ready. Shipping needs your yes.")
        primary_art = artifacts[0] if artifacts else None
        await self.db.execute(
            "UPDATE missions SET status = 'PENDING_APPROVAL', qa_score = ?, qa_notes = ?, updated_at = ? WHERE id = ?",
            (qa_score, qa_summary, utc_now(), mission_id),
        )
        approval = await self.approvals.create_approval(
            company_id=company_id,
            mission_id=mission_id,
            artifact_id=primary_art["id"] if primary_art else None,
            title=f"Ship {primary_art['title'] if primary_art else title}?",
            subtitle=f"QA {qa_score}/100 by {qa_bot['name']} · Verified in isolated sandbox",
            action_type="SHIP_RELEASE",
            action_payload={
                "artifact_id": primary_art["id"] if primary_art else None,
                "filename": primary_art["filename"] if primary_art else None,
                "completion_message": f"Shipped! All bots and memory synced for '{title}'.",
            },
        )
        await self.runner.set_bot_status(company_id, ceo_bot["id"], "idle", "Waiting for your yes")

        return {
            "mission_id": mission_id,
            "status": "PENDING_APPROVAL",
            "qa_score": qa_score,
            "qa_passed": qa_passed,
            "artifacts": artifacts,
            "approval": approval,
        }

    async def _produce_real_sandbox_deliverable(
        self,
        company: dict[str, Any],
        producer_bot: dict[str, Any],
        mission_id: str,
        title: str,
        goal: str,
    ) -> dict[str, Any] | None:
        """Run a real isolated Python script in the company sandbox to build a verified PDF/Markdown deliverable."""
        slug = re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")[:32] or "deliverable"
        out_pdf = f"output/{slug}.pdf"
        out_md = f"output/{slug}_listing_pack.md"

        script = f"""
import os, json
from pathlib import Path
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

out_dir = Path(os.environ["FOUNDRY_OUTPUT_DIR"])
out_dir.mkdir(parents=True, exist_ok=True)
pdf_path = out_dir / "{slug}.pdf"

c = canvas.Canvas(str(pdf_path), pagesize=A4)
w, h = A4
c.setTitle({json.dumps(title)})

# Page 1: Cover & Executive Spec
c.setFillColorRGB(0.07, 0.07, 0.07)
c.setFont("Helvetica-Bold", 22)
c.drawString(54, h - 80, {json.dumps(company['name'][:48])})
c.setFont("Helvetica-Bold", 15)
c.drawString(54, h - 115, {json.dumps(title[:62])})
c.setFillColorRGB(0.35, 0.35, 0.40)
c.setFont("Helvetica", 11)
c.drawString(54, h - 145, "Generated inside isolated FoundryOS sandbox by " + {json.dumps(producer_bot['name'])})
c.line(54, h - 165, w - 54, h - 165)

y = h - 200
c.setFillColorRGB(0.1, 0.1, 0.1)
c.setFont("Helvetica-Bold", 12)
c.drawString(54, y, "Mission Brief & Deliverable Scope")
y -= 24
c.setFont("Helvetica", 10.5)
goal_text = {json.dumps(goal)}
for i in range(0, len(goal_text), 82):
    c.drawString(54, y, goal_text[i:i+82])
    y -= 16

y -= 16
c.setFont("Helvetica-Bold", 12)
c.drawString(54, y, "Execution Checklist")
y -= 22
c.setFont("Helvetica", 10.5)
items = [
    "1. Isolated sandbox workspace provisioned (1 Sandbox = 1 Company).",
    "2. Deliverable generated programmatically and saved to output/.",
    "3. Independent Quality Auditor verification scheduled before human gate.",
    "4. Ready for founder Ship / Publish approval on Android.",
]
for item in items:
    c.drawString(64, y, item)
    y -= 18

c.showPage()
c.save()

md_path = out_dir / "{slug}_listing_pack.md"
md_path.write_text(
    f"# {{ {json.dumps(title)} }}\\n\\n"
    f"- **Company**: {company['name']}\\n"
    f"- **Produced by**: {producer_bot['name']} ({producer_bot['role']})\\n"
    f"- **Deliverable**: `{slug}.pdf`\\n"
    f"- **Goal**: {goal}\\n",
    encoding="utf-8",
)
print(json.dumps({{"pdf": str(pdf_path.name), "bytes": pdf_path.stat().st_size}}))
"""
        exec_res = await self.sandbox.execute_code(company["id"], "python", script)
        if exec_res.exit_code == 0:
            reg = await self.tools.execute(
                company["id"],
                {**producer_bot, "allowed_tools_json": json.dumps(["artifact_publish"])},
                "artifact_publish",
                {
                    "rel_path": out_pdf,
                    "title": title[:60],
                    "summary": f"Built in sandbox by {producer_bot['name']} ({out_pdf} + {out_md})",
                },
                mission_id=mission_id,
            )
            if reg.get("ok"):
                return reg["artifact"]
        return None

    async def _run_independent_qa(
        self,
        company: dict[str, Any],
        qa_bot: dict[str, Any],
        mission_id: str,
        artifacts: list[dict[str, Any]],
        goal: str,
    ) -> tuple[str, int, bool]:
        """Programmatic + LLM independent quality verification inside the company sandbox."""
        if not artifacts:
            return ("No deliverable files were registered in the sandbox.", 40, False)

        # Real programmatic integrity verification inside sandbox
        art = artifacts[0]
        verify_code = f"""
import os, json
from pathlib import Path

root = Path(os.environ["FOUNDRY_SANDBOX_ROOT"])
target = root / {json.dumps(art['rel_path'])}
checks = {{
    "exists": target.exists(),
    "non_empty": target.exists() and target.stat().st_size > 100,
    "size_bytes": target.stat().st_size if target.exists() else 0,
    "valid_header": False,
}}
if target.exists():
    head = target.read_bytes()[:8]
    if target.suffix.lower() == ".pdf":
        checks["valid_header"] = head.startswith(b"%PDF")
    else:
        checks["valid_header"] = len(head) > 0
print(json.dumps(checks))
"""
        res = await self.sandbox.execute_code(company["id"], "python", verify_code)
        checks = extract_json_object(res.stdout) or {"exists": False, "non_empty": False, "valid_header": False}
        prog_ok = bool(checks.get("exists") and checks.get("non_empty") and checks.get("valid_header"))
        base_score = 94 if prog_ok else 45

        # Optionally let QA bot LLM review too if live key configured
        qa_llm = await self.runner.run_task(
            company,
            qa_bot,
            (
                f"Independently audit deliverable '{art['filename']}' ({art['size_bytes']} bytes) for goal: {goal}.\n"
                f"Programmatic sandbox verification result: {json.dumps(checks)}.\n"
                "Return final JSON with qa_score (0-100), qa_passed (bool), and a crisp summary."
            ),
            mission_id=mission_id,
        )
        if not qa_llm.get("simulated") and qa_llm.get("summary"):
            score = int(qa_llm.get("qa_score", base_score))
            passed = bool(qa_llm.get("qa_passed", prog_ok)) and prog_ok
            return (qa_llm["summary"], score, passed)

        summary = (
            f"All checks passed in isolated sandbox ({checks.get('size_bytes', 0):,} bytes, header valid)"
            if prog_ok
            else f"Sandbox QA failed: {checks}"
        )
        return (summary, base_score, prog_ok)

    async def generate_board_meeting(self, company_id: str) -> dict[str, Any]:
        """Generate the exact 'Board meeting' consolidated KPI card from the video."""
        company = await self.db.fetchone("SELECT * FROM companies WHERE id = ?", (company_id,))
        if not company:
            raise LookupError("Company not found")

        shipped = await self.db.fetchone(
            "SELECT COUNT(*) AS c FROM approvals WHERE company_id = ? AND status = 'EXECUTED'", (company_id,)
        )
        missions_done = await self.db.fetchone(
            "SELECT COUNT(*) AS c FROM missions WHERE company_id = ?", (company_id,)
        )
        qa_passed = await self.db.fetchone(
            "SELECT COUNT(*) AS c FROM artifacts WHERE company_id = ? AND qa_status = 'PASSED'", (company_id,)
        )
        decisions = await self.db.fetchone(
            "SELECT COUNT(*) AS c FROM approvals WHERE company_id = ? AND status IN ('EXECUTED', 'APPROVED', 'REJECTED')",
            (company_id,),
        )
        mem_count = await self.memory.count(company_id)

        payload = {
            "title": f"Board meeting · {company['name']}",
            "sources_count": mem_count + int(missions_done["c"] if missions_done else 0),
            "metrics": {
                "shipped": int(shipped["c"] if shipped else 0),
                "missions": int(missions_done["c"] if missions_done else 0),
                "qa_passed": int(qa_passed["c"] if qa_passed else 0),
                "decisions_approved": int(decisions["c"] if decisions else 0),
            },
            "tokens_used": int(company["tokens_used_today"]),
            "token_budget": int(company["daily_token_budget"]),
        }

        await self._add_message(
            company_id,
            "bot",
            "CEO",
            "Ready for the board.",
            card_type="board_meeting",
            card_payload=payload,
        )
        await self.bus.publish("board_meeting", company_id, payload)
        return payload

    async def _add_message(
        self,
        company_id: str,
        sender_type: str,
        bot_name: str,
        content: str,
        bot_id: str | None = None,
        card_type: str | None = None,
        card_payload: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        msg_id = f"msg_{uuid.uuid4().hex[:12]}"
        now = utc_now()
        await self.db.execute(
            """
            INSERT INTO messages (id, company_id, sender_type, bot_id, bot_name, content, card_type, card_payload_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                msg_id,
                company_id,
                sender_type,
                bot_id,
                bot_name,
                content,
                card_type,
                json.dumps(card_payload, ensure_ascii=False) if card_payload is not None else None,
                now,
            ),
        )
        msg = {
            "id": msg_id,
            "company_id": company_id,
            "sender_type": sender_type,
            "bot_id": bot_id,
            "bot_name": bot_name,
            "content": content,
            "card_type": card_type,
            "card_payload": card_payload,
            "created_at": now,
        }
        await self.bus.publish("message_created", company_id, msg)
        return msg
