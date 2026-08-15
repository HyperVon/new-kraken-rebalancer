#!/usr/bin/env bash
set -euo pipefail
cd /Users/charlesv/Projects/new-kraken-rebalancer
if [[ $# -eq 0 ]]; then
  echo 'Usage: run_overhaul.sh --task "<bounded audit request>" [--approve]' >&2
  exit 2
fi
exec ./.agents/.agent-runtime-router/run.py --python \
  .agents/runtime-router/adapters/kilo/route_subagents.py \
  --workflow comprehensive-quality-overhaul \
  --distinct-routes \
  "$@"
