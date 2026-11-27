from __future__ import annotations

import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import aiosqlite


SCHEMA_SQL = """
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;

CREATE TABLE IF NOT EXISTS companies (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    mission_statement TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'PROPOSED',
    sandbox_path TEXT NOT NULL,
    daily_token_budget INTEGER NOT NULL DEFAULT 250000,
    tokens_used_today INTEGER NOT NULL DEFAULT 0,
    ceo_model TEXT NOT NULL DEFAULT 'auto',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS bots (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    role TEXT NOT NULL,
    tagline TEXT NOT NULL DEFAULT '',
    system_prompt TEXT NOT NULL,
    avatar_shape TEXT NOT NULL DEFAULT 'circle',
    avatar_color TEXT NOT NULL DEFAULT '#111111',
    model TEXT NOT NULL DEFAULT 'auto',
    allowed_tools_json TEXT NOT NULL DEFAULT '[]',
    is_ceo INTEGER NOT NULL DEFAULT 0,
    is_qa INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'idle',
    status_text TEXT NOT NULL DEFAULT 'Ready',
    unread_count INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bots_company ON bots(company_id);

CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    sender_type TEXT NOT NULL,
    bot_id TEXT,
    bot_name TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    card_type TEXT,
    card_payload_json TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_messages_company ON messages(company_id, created_at);

CREATE TABLE IF NOT EXISTS missions (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    goal TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    assigned_bot_id TEXT,
    qa_bot_id TEXT,
    qa_score INTEGER,
    qa_notes TEXT NOT NULL DEFAULT '',
    iteration INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_missions_company ON missions(company_id, created_at);

CREATE TABLE IF NOT EXISTS artifacts (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    mission_id TEXT REFERENCES missions(id) ON DELETE SET NULL,
    bot_id TEXT,
    bot_name TEXT NOT NULL DEFAULT '',
    title TEXT NOT NULL,
    filename TEXT NOT NULL,
    rel_path TEXT NOT NULL,
    mime_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    size_bytes INTEGER NOT NULL DEFAULT 0,
    summary TEXT NOT NULL DEFAULT '',
    qa_status TEXT NOT NULL DEFAULT 'PENDING',
    qa_score INTEGER,
    qa_report TEXT NOT NULL DEFAULT '',
    preview_rel_path TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_artifacts_company ON artifacts(company_id, created_at);

CREATE TABLE IF NOT EXISTS approvals (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    mission_id TEXT REFERENCES missions(id) ON DELETE SET NULL,
    artifact_id TEXT REFERENCES artifacts(id) ON DELETE SET NULL,
    title TEXT NOT NULL,
    subtitle TEXT NOT NULL DEFAULT '',
    action_type TEXT NOT NULL,
    action_payload_json TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'PENDING',
    decided_by_device TEXT,
    feedback TEXT NOT NULL DEFAULT '',
    idempotency_key TEXT UNIQUE,
    execution_result_json TEXT,
    created_at TEXT NOT NULL,
    decided_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_approvals_status ON approvals(status, created_at);
CREATE INDEX IF NOT EXISTS idx_approvals_company ON approvals(company_id, created_at);

CREATE TABLE IF NOT EXISTS company_connectors (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    auth_header TEXT NOT NULL DEFAULT 'Authorization',
    auth_value TEXT NOT NULL DEFAULT '',
    live_enabled INTEGER NOT NULL DEFAULT 0,
    allowed_methods_json TEXT NOT NULL DEFAULT '["GET","POST"]',
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_connectors_company ON company_connectors(company_id);

CREATE TABLE IF NOT EXISTS memory_blocks (
    company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    block_key TEXT NOT NULL,
    content TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (company_id, block_key)
);

CREATE TABLE IF NOT EXISTS memory_entries (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    bot_name TEXT NOT NULL DEFAULT 'Memory',
    category TEXT NOT NULL DEFAULT 'fact',
    content TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_memory_company ON memory_entries(company_id, created_at);

CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
    entry_id UNINDEXED,
    company_id UNINDEXED,
    content
);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value_json TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id TEXT,
    actor TEXT NOT NULL,
    event_type TEXT NOT NULL,
    details_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL
);
"""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class Database:
    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path
        self._conn: aiosqlite.Connection | None = None

    async def connect(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        if self.db_path.exists():
            backup = self.db_path.with_suffix(".db.bak")
            try:
                shutil.copy2(self.db_path, backup)
            except OSError:
                pass
        self._conn = await aiosqlite.connect(str(self.db_path))
        self._conn.row_factory = aiosqlite.Row
        await self._conn.executescript(SCHEMA_SQL)
        await self._conn.commit()

    async def close(self) -> None:
        if self._conn is not None:
            await self._conn.close()
            self._conn = None

    @property
    def conn(self) -> aiosqlite.Connection:
        if self._conn is None:
            raise RuntimeError("Database not connected")
        return self._conn

    async def execute(self, sql: str, params: tuple[Any, ...] = ()) -> aiosqlite.Cursor:
        cur = await self.conn.execute(sql, params)
        await self.conn.commit()
        return cur

    async def fetchone(self, sql: str, params: tuple[Any, ...] = ()) -> dict[str, Any] | None:
        async with self.conn.execute(sql, params) as cur:
            row = await cur.fetchone()
            return dict(row) if row is not None else None

    async def fetchall(self, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
        async with self.conn.execute(sql, params) as cur:
            rows = await cur.fetchall()
            return [dict(r) for r in rows]

    async def audit(self, company_id: str | None, actor: str, event_type: str, details: dict[str, Any]) -> None:
        await self.execute(
            "INSERT INTO audit_log (company_id, actor, event_type, details_json, created_at) VALUES (?, ?, ?, ?, ?)",
            (company_id, actor, event_type, json.dumps(details, ensure_ascii=False), utc_now()),
        )

    async def get_setting(self, key: str, default: Any = None) -> Any:
        row = await self.fetchone("SELECT value_json FROM settings WHERE key = ?", (key,))
        if not row:
            return default
        try:
            return json.loads(row["value_json"])
        except json.JSONDecodeError:
            return default

    async def set_setting(self, key: str, value: Any) -> None:
        await self.execute(
            """
            INSERT INTO settings (key, value_json, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at
            """,
            (key, json.dumps(value, ensure_ascii=False), utc_now()),
        )
