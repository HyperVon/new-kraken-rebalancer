#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf '%s\n' \
    'Usage: run_routed_agent.sh --agent <name> --model <provider/model> --prompt <text> [--variant <effort>]' \
    'Runs a Kilo agent profile with an explicit, caller-selected model route.'
}

agent=''
model=''
prompt=''
variant=''

while (($#)); do
  case "$1" in
    --agent) agent=${2:?}; shift 2 ;;
    --model) model=${2:?}; shift 2 ;;
    --prompt) prompt=${2:?}; shift 2 ;;
    --variant) variant=${2:?}; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done

[[ -n "$agent" && -n "$model" && -n "$prompt" ]] || { usage >&2; exit 2; }
[[ "$model" == */* ]] || { printf 'Model must use provider/model format.\n' >&2; exit 2; }

agent_mode=$(kilo debug agent "$agent" | python3 -c 'import json, sys; print(json.load(sys.stdin)["mode"])')
[[ "$agent_mode" != subagent ]] || {
  printf 'Agent %s is subagent-only; use a mode: all profile to avoid CLI fallback.\n' "$agent" >&2
  exit 2
}

args=(run --auto --format json --agent "$agent" --model "$model")
[[ -n "$variant" ]] && args+=(--variant "$variant")
exec kilo "${args[@]}" "$prompt"
