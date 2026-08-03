#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

if ! command -v kilo >/dev/null 2>&1; then
    printf '%s\n' "kilo is required for route inventory" >&2
    exit 127
fi

catalog=$(mktemp "${TMPDIR:-/tmp}/kilo-models.XXXXXX")
trap 'rm -f "$catalog"' EXIT

# Keep the large catalog in a disposable file; only the bounded summary reaches stdout.
kilo models --verbose --refresh >"$catalog"
python3 "$SCRIPT_DIR/inventory_routes.py" --input "$catalog" "$@"
