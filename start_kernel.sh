#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR/backend"

if [ ! -d ".venv" ]; then
  uv venv .venv
  uv pip install --python .venv/bin/python -e .
fi

export FOUNDRY_BASE_DIR="${FOUNDRY_BASE_DIR:-$ROOT_DIR/runtime}"
mkdir -p "$FOUNDRY_BASE_DIR/data"

echo "=== Starting CEO Universal Kernel ==="
echo "API: http://0.0.0.0:${FOUNDRY_PORT:-8765}"
if [ -f "$FOUNDRY_BASE_DIR/data/pairing.token" ]; then
  echo "Pairing Token: $(cat "$FOUNDRY_BASE_DIR/data/pairing.token")"
else
  echo "Pairing Token will be created in: $FOUNDRY_BASE_DIR/data/pairing.token"
fi

exec .venv/bin/uvicorn app.main:app --host "${FOUNDRY_HOST:-0.0.0.0}" --port "${FOUNDRY_PORT:-8765}"
