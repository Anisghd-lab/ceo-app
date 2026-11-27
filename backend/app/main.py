from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Query, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import build_router
from app.config import AppConfig, load_config
from app.core.agent_runner import GenericAgentRunner
from app.core.approval_gate import ApprovalGate
from app.core.ceo_architect import CEOArchitect
from app.core.event_bus import EventBus
from app.core.llm_router import MultiLLMRouter
from app.core.memory_engine import MemoryEngine
from app.core.sandbox_manager import SandboxManager
from app.core.tool_registry import ToolRegistry
from app.db.database import Database


class FoundryState:
    def __init__(self, config: AppConfig) -> None:
        self.config = config
        self.db = Database(config.db_path)
        self.sandbox = SandboxManager(
            config.sandboxes_dir,
            max_concurrent=config.max_concurrent_execs,
            timeout_seconds=config.exec_timeout_seconds,
        )
        self.bus = EventBus(self.db)
        self.memory = MemoryEngine(self.db)
        self.llm = MultiLLMRouter(self.db)
        self.approvals = ApprovalGate(self.db, self.bus)
        self.tools = ToolRegistry(self.db, self.sandbox, self.memory, self.approvals, self.bus)
        self.runner = GenericAgentRunner(
            self.db, self.llm, self.tools, self.memory, self.bus, max_steps=config.max_agent_steps
        )
        self.ceo = CEOArchitect(
            self.db, self.sandbox, self.llm, self.memory, self.tools, self.runner, self.approvals, self.bus
        )


def create_app(base_dir: Path | None = None) -> FastAPI:
    config = load_config(base_dir=base_dir)
    state = FoundryState(config)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        await state.db.connect()
        try:
            yield
        finally:
            await state.db.close()

    app = FastAPI(title="FoundryOS Universal Kernel", version="0.1.0", lifespan=lifespan)
    app.state.foundry = state
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.include_router(build_router(state))

    @app.websocket("/ws")
    async def websocket_endpoint(ws: WebSocket, token: str = Query(default="")) -> None:
        if token != state.config.pairing_token:
            await ws.close(code=4401)
            return
        await state.bus.register(ws)
        try:
            while True:
                msg = await ws.receive_text()
                if msg == "ping":
                    await ws.send_text('{"type":"pong"}')
        except WebSocketDisconnect:
            await state.bus.unregister(ws)
        except Exception:
            await state.bus.unregister(ws)

    return app


app = create_app()
