from __future__ import annotations

import uuid
from typing import Any

from app.db.database import Database, utc_now


class MemoryEngine:
    """Mem0 + Letta-inspired isolated memory per company using SQLite Core Blocks + FTS5 Archival."""

    def __init__(self, db: Database) -> None:
        self.db = db

    async def set_core_block(self, company_id: str, block_key: str, content: str) -> None:
        await self.db.execute(
            """
            INSERT INTO memory_blocks (company_id, block_key, content, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(company_id, block_key) DO UPDATE
            SET content = excluded.content, updated_at = excluded.updated_at
            """,
            (company_id, block_key.strip()[:64], content[:8000], utc_now()),
        )

    async def get_core_blocks(self, company_id: str) -> dict[str, str]:
        rows = await self.db.fetchall(
            "SELECT block_key, content FROM memory_blocks WHERE company_id = ? ORDER BY block_key",
            (company_id,),
        )
        return {r["block_key"]: r["content"] for r in rows}

    async def remember(
        self, company_id: str, content: str, bot_name: str = "Memory", category: str = "fact"
    ) -> dict[str, Any]:
        entry_id = f"mem_{uuid.uuid4().hex[:12]}"
        now = utc_now()
        clean_content = content.strip()[:4000]
        await self.db.execute(
            """
            INSERT INTO memory_entries (id, company_id, bot_name, category, content, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (entry_id, company_id, bot_name, category, clean_content, now),
        )
        await self.db.execute(
            "INSERT INTO memory_fts (entry_id, company_id, content) VALUES (?, ?, ?)",
            (entry_id, company_id, clean_content),
        )
        return {"id": entry_id, "company_id": company_id, "category": category, "content": clean_content, "created_at": now}

    async def recall(self, company_id: str, query: str = "", limit: int = 8) -> list[dict[str, Any]]:
        tokens = [t for t in "".join(c if c.isalnum() else " " for c in query).split() if len(t) > 1]
        if tokens:
            fts_query = " OR ".join(f'"{t}"' for t in tokens[:8])
            rows = await self.db.fetchall(
                """
                SELECT e.id, e.bot_name, e.category, e.content, e.created_at
                FROM memory_fts f
                JOIN memory_entries e ON e.id = f.entry_id
                WHERE f.company_id = ? AND memory_fts MATCH ?
                ORDER BY bm25(memory_fts)
                LIMIT ?
                """,
                (company_id, fts_query, limit),
            )
            if rows:
                return rows

        return await self.db.fetchall(
            """
            SELECT id, bot_name, category, content, created_at
            FROM memory_entries
            WHERE company_id = ?
            ORDER BY created_at DESC
            LIMIT ?
            """,
            (company_id, limit),
        )

    async def count(self, company_id: str) -> int:
        row = await self.db.fetchone(
            "SELECT COUNT(*) AS cnt FROM memory_entries WHERE company_id = ?",
            (company_id,),
        )
        return int(row["cnt"]) if row else 0

    async def format_context(self, company_id: str, query: str = "") -> str:
        blocks = await self.get_core_blocks(company_id)
        memories = await self.recall(company_id, query=query, limit=6)
        parts: list[str] = []
        if blocks:
            parts.append("## Core Company Memory")
            for k, v in blocks.items():
                parts.append(f"- [{k}]: {v}")
        if memories:
            parts.append("## Recent Company Notes")
            for m in memories:
                parts.append(f"- ({m['category']} by {m['bot_name']}): {m['content']}")
        return "\n".join(parts)
