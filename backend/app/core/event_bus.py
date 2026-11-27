from __future__ import annotations

import asyncio
import json
from typing import Any

import httpx
from fastapi import WebSocket

from app.db.database import Database, utc_now


class EventBus:
    """Real-time WebSocket broadcaster + optional ntfy push notifier for Android."""

    def __init__(self, db: Database) -> None:
        self.db = db
        self._connections: set[WebSocket] = set()
        self._lock = asyncio.Lock()

    async def register(self, ws: WebSocket) -> None:
        await ws.accept()
        async with self._lock:
            self._connections.add(ws)

    async def unregister(self, ws: WebSocket) -> None:
        async with self._lock:
            self._connections.discard(ws)

    async def publish(self, event_type: str, company_id: str | None, payload: dict[str, Any]) -> None:
        envelope = {
            "type": event_type,
            "company_id": company_id,
            "payload": payload,
            "timestamp": utc_now(),
        }
        raw = json.dumps(envelope, ensure_ascii=False)
        dead: list[WebSocket] = []
        async with self._lock:
            targets = list(self._connections)
        for ws in targets:
            try:
                await ws.send_text(raw)
            except Exception:
                dead.append(ws)
        if dead:
            async with self._lock:
                for ws in dead:
                    self._connections.discard(ws)

    async def send_push_notification(self, title: str, message: str, approval_id: str | None = None) -> None:
        """Optional ntfy push wake-up for Android background delivery."""
        ntfy_topic = await self.db.get_setting("ntfy_topic", "")
        if not ntfy_topic:
            return
        url = ntfy_topic if ntfy_topic.startswith("http") else f"https://ntfy.sh/{ntfy_topic.strip('/')}"
        headers = {
            "Title": title[:120],
            "Priority": "high" if approval_id else "default",
            "Tags": "rocket,robot" if approval_id else "robot",
        }
        try:
            async with httpx.AsyncClient(timeout=6.0) as client:
                await client.post(url, content=message.encode("utf-8"), headers=headers)
        except Exception:
            pass
