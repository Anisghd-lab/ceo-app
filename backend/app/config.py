from __future__ import annotations

import os
import secrets
from pathlib import Path
from pydantic import BaseModel


class AppConfig(BaseModel):
    base_dir: Path
    data_dir: Path
    sandboxes_dir: Path
    db_path: Path
    pairing_token: str
    host: str = "0.0.0.0"
    port: int = 8765
    max_concurrent_execs: int = 1
    exec_timeout_seconds: int = 60
    max_agent_steps: int = 8
    max_qa_retries: int = 3
    default_daily_token_budget: int = 250_000


def load_config(base_dir: Path | None = None) -> AppConfig:
    root = base_dir or Path(os.environ.get("FOUNDRY_BASE_DIR", "/root/foundry-os/runtime"))
    data_dir = root / "data"
    sandboxes_dir = root / "sandboxes"
    data_dir.mkdir(parents=True, exist_ok=True)
    sandboxes_dir.mkdir(parents=True, exist_ok=True)

    token_file = data_dir / "pairing.token"
    env_token = os.environ.get("FOUNDRY_PAIRING_TOKEN")
    if env_token:
        token = env_token.strip()
    elif token_file.exists():
        token = token_file.read_text(encoding="utf-8").strip()
    else:
        token = f"fos_{secrets.token_urlsafe(18)}"
        token_file.write_text(token, encoding="utf-8")
        try:
            os.chmod(token_file, 0o600)
        except OSError:
            pass

    return AppConfig(
        base_dir=root,
        data_dir=data_dir,
        sandboxes_dir=sandboxes_dir,
        db_path=data_dir / "foundry.db",
        pairing_token=token,
        host=os.environ.get("FOUNDRY_HOST", "0.0.0.0"),
        port=int(os.environ.get("FOUNDRY_PORT", "8765")),
    )
