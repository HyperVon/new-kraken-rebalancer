#!/usr/bin/env bash
set -euo pipefail
# Reproducible ARR runtime setup for .kilo/model-router
# - Python >=3.11 required (ARR requirement)
# - Creates project-local venv at .kilo/model-router/.venv
# - Installs pinned ARR source revision (no PyPI)
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv"
REQ_FILE="$SCRIPT_DIR/requirements.txt"
PY_MIN="3.11"

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 not found in PATH" >&2
  exit 1
fi
PY_VERSION="$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
# Simple version check
python3 -c "import sys; assert sys.version_info >= (3,11), 'Python >=3.11 required, found %s' % sys.version" || {
  echo "ERROR: Python $PY_MIN+ required, found $PY_VERSION" >&2
  exit 1
}
if [[ ! -f "$REQ_FILE" ]]; then
  echo "ERROR: requirements file missing at $REQ_FILE" >&2
  exit 1
fi
echo "[setup] Creating venv at $VENV_DIR (Python $PY_VERSION)"
python3 -m venv "$VENV_DIR"
# shellcheck source=/dev/null
source "$VENV_DIR/bin/activate"
echo "[setup] Upgrading pip"
pip install --upgrade pip >/dev/null
echo "[setup] Installing pinned ARR from $REQ_FILE"
pip install -r "$REQ_FILE"
echo "[setup] Verifying ARR installation"
python -c "import agent_runtime_router; print(f'ARR {agent_runtime_router.__version__} installed')"
echo "[setup] Done. Use $VENV_DIR/bin/python for routing."
