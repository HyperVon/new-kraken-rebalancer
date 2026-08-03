#!/usr/bin/env python3
"""Plan and launch bounded subagents with independently selected routes."""

from __future__ import annotations

import argparse
import concurrent.futures
import copy
import json
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Mapping, Sequence

import availability
import router
import workflows


MAX_TRACKS = 8
DEFAULT_AGENT = "explore"
DEFAULT_TIMEOUT = 900
MAX_REPORT_LINES = 12
MAX_FAILOVER_ATTEMPTS = 3


def load_manifest(path: Path) -> list[dict[str, Any]]:
    try:
        manifest = router.parse_json_text(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise router.RouterError(f"cannot read subagent manifest {path}: {error}") from error
    tracks = manifest.get("tracks") if isinstance(manifest, Mapping) else None
    if not isinstance(tracks, list) or not tracks:
        raise router.RouterError("subagent manifest must contain a non-empty tracks array")
    if len(tracks) > MAX_TRACKS:
        raise router.RouterError(f"subagent manifest exceeds the {MAX_TRACKS}-track limit")

    seen: set[str] = set()
    validated: list[dict[str, Any]] = []
    for index, track in enumerate(tracks, start=1):
        if not isinstance(track, Mapping):
            raise router.RouterError(f"track {index} must be an object")
        track_id = str(track.get("id", "")).strip()
        task = str(track.get("task", "")).strip()
        if not track_id or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", track_id):
            raise router.RouterError(f"track {index} has an invalid id")
        if track_id in seen:
            raise router.RouterError(f"duplicate track id: {track_id}")
        if not task:
            raise router.RouterError(f"track {track_id} has no task")
        files = track.get("files", [])
        if not isinstance(files, list) or not all(isinstance(path, str) for path in files):
            raise router.RouterError(f"track {track_id} files must be a string array")
        seen.add(track_id)
        validated.append(dict(track, id=track_id, task=task, files=files))
    return validated


def worker_prompt(track: Mapping[str, Any], route: str, allow_edits: bool) -> str:
    files = track.get("files", [])
    scope = ", ".join(files) if files else "only the minimum paths needed for this track"
    read_only = bool(track.get("read_only", True)) and not allow_edits
    guardrails = (
        "Do not edit files, run Gradle, start servers, or spawn further agents."
        if read_only
        else "Edit only the explicitly owned paths and do not run unrelated builds or servers."
    )
    return f"""You are a bounded subagent launched by a parent agent.

Track: {track['id']}
Selected route: {route}
Owned paths: {scope}

{guardrails}
Work only on the requested track. Do not redo other tracks or the parent task.
Use only the native file and search tools available in this session. Do not emit
tool-call markup, `ctx_*` commands, or compression requests in your report.
Return a compact report of at most {MAX_REPORT_LINES} lines and 5 findings, with
path:line references where applicable. State what you checked, the result, and
any remaining uncertainty.

Task:
{track['task']}
"""


def build_plan(
    manifest_path: Path | None,
    workflow: str | None,
    parent_task: str | None,
    config_path: str | None,
    refresh: bool,
    allow_edits: bool,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    if workflow:
        if not parent_task:
            raise router.RouterError("--task is required with --workflow")
        try:
            tracks = workflows.build_tracks(workflow, parent_task)
        except KeyError as error:
            raise router.RouterError(f"unknown routed workflow: {error.args[0]}") from error
        manifest_label = f"workflow:{workflow}"
    elif manifest_path:
        tracks = load_manifest(manifest_path)
        manifest_label = str(manifest_path)
    else:
        raise router.RouterError("provide either --manifest or --workflow")
    config = router.load_config(Path(config_path).expanduser() if config_path else router.DEFAULT_CONFIG_PATH)
    raw_models, warnings = router.fetch_catalog(config, refresh)
    aa_models, aa_status = router.load_artificial_analysis(config, refresh)
    quota_snapshot = availability.snapshot(config)
    warnings.extend(quota_snapshot["warnings"])
    candidates = router.build_candidates(raw_models, config, aa_models, quota_snapshot)
    records: list[dict[str, Any]] = []
    prepared: list[dict[str, Any]] = []

    for track in tracks:
        requested_profile = str(track.get("profile", "auto"))
        profile_name, profile = router.profile_config(config, requested_profile, track["task"])
        track_candidates = copy.deepcopy(candidates)
        sensitive = router.is_sensitive(track["task"], profile_name)
        selected = router.select_candidate(track_candidates, profile, config, sensitive)
        selection = router.report(selected, profile_name, profile, aa_status, sensitive)
        selection.update(
            {
                "track": track["id"],
                "agent": str(track.get("agent", DEFAULT_AGENT)),
                "files": track.get("files", []),
                "read_only": bool(track.get("read_only", True)) and not allow_edits,
            }
        )
        records.append(selection)
        prepared.append(
            {
                "track": track,
                "selection": selection,
                "prompt": worker_prompt(track, selection["route"], allow_edits),
                "candidates": track_candidates,
                "profile": profile,
                "config": config,
                "sensitive": sensitive,
                "allow_edits": allow_edits,
            }
        )

    return {
        "manifest": manifest_label,
        "workflow": workflow,
        "aa": aa_status,
        "warnings": warnings,
        "tracks": records,
    }, prepared


def compact_output(output: str | bytes) -> str:
    if isinstance(output, bytes):
        output = output.decode(errors="replace")
    ansi = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
    lines = [ansi.sub("", line).strip() for line in output.splitlines()]
    lines = [line for line in lines if line]
    return "\n".join(lines[-MAX_REPORT_LINES:])


def launch_worker(item: Mapping[str, Any], timeout: int, allow_auto: bool) -> dict[str, Any]:
    track = item["track"]
    selection = item["selection"]
    command = [
        "kilo",
        "run",
        "--model",
        str(selection["route"]),
        "--agent",
        str(selection["agent"]),
        "--title",
        f"routed-{track['id']}",
        item["prompt"],
    ]
    if allow_auto:
        command.insert(-1, "--auto")
    variant = track.get("variant")
    if variant:
        command[4:4] = ["--variant", str(variant)]
    started = time.monotonic()
    try:
        completed = subprocess.run(
            command,
            cwd=router.ROOT,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        output = compact_output(f"{completed.stdout}\n{completed.stderr}")
        return {
            "track": track["id"],
            "route": selection["route"],
            "exit_code": completed.returncode,
            "duration_seconds": round(time.monotonic() - started, 1),
            "report": output,
            "failure_kind": availability.failure_kind(output) if completed.returncode else None,
        }
    except subprocess.TimeoutExpired as error:
        stdout = error.stdout.decode(errors="replace") if isinstance(error.stdout, bytes) else (error.stdout or "")
        stderr = error.stderr.decode(errors="replace") if isinstance(error.stderr, bytes) else (error.stderr or "")
        output = compact_output(f"{stdout}\n{stderr}")
        return {
            "track": track["id"],
            "route": selection["route"],
            "exit_code": None,
            "duration_seconds": round(time.monotonic() - started, 1),
            "report": f"worker timed out after {timeout}s\n{output}".strip(),
            "failure_kind": "provider_unavailable",
        }


def launch_with_failover(item: Mapping[str, Any], timeout: int, allow_auto: bool) -> dict[str, Any]:
    current = dict(item)
    attempted_routes: set[str] = set()
    excluded_providers: set[str] = set()
    failovers: list[dict[str, Any]] = []
    for _ in range(MAX_FAILOVER_ATTEMPTS):
        result = launch_worker(current, timeout, allow_auto)
        attempted_routes.add(result["route"])
        result["attempted_routes"] = sorted(attempted_routes)
        result["failovers"] = failovers
        if result["exit_code"] == 0:
            return result

        kind = result.get("failure_kind")
        track = current["track"]
        if not kind or not bool(current["selection"].get("read_only", True)):
            return result
        if kind not in {"rate_limit", "credits", "provider_unavailable", "authentication"}:
            return result

        route = str(result["route"])
        provider = str(current["selection"]["provider"])
        cooldown = availability.record_failure(current["config"], route, provider, kind, result["report"])
        excluded_providers.add(provider)
        failovers.append({"from": route, "reason": kind, "cooldown_seconds": cooldown})
        candidates = copy.deepcopy(current["candidates"])
        try:
            next_candidate = router.select_candidate(
                candidates,
                current["profile"],
                current["config"],
                current["sensitive"],
                excluded_routes=attempted_routes,
                excluded_providers=excluded_providers,
            )
        except router.RouterError:
            result["failovers"] = failovers
            return result
        next_selection = router.report(
            next_candidate,
            current["selection"]["profile"],
            current["profile"],
            current["selection"]["aa"],
            current["sensitive"],
        )
        next_selection.update(
            {
                "track": track["id"],
                "agent": current["selection"]["agent"],
                "files": track.get("files", []),
                "read_only": current["selection"].get("read_only", True),
            }
        )
        current = dict(
            current,
            selection=next_selection,
            prompt=worker_prompt(track, next_selection["route"], current["allow_edits"]),
        )
    return result


def launch_workers(prepared: Sequence[Mapping[str, Any]], max_workers: int, timeout: int, allow_auto: bool) -> list[dict[str, Any]]:
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(max_workers, len(prepared))) as executor:
        futures = [executor.submit(launch_with_failover, item, timeout, allow_auto) for item in prepared]
        return [future.result() for future in futures]


def print_plan(plan: Mapping[str, Any], as_json: bool) -> None:
    if as_json:
        print(json.dumps(plan, indent=2))
        return
    print(f"Artificial Analysis data: {plan['aa']}")
    for warning in plan.get("warnings", []):
        print(f"Warning: {warning}")
    for track in plan["tracks"]:
        cost = track["cost"]
        capability = track["capability"]
        score = capability["score"] if capability["score"] is not None else "unknown"
        effective = cost["effective"]
        cost_text = f"${effective:.6f}" if effective is not None else "unknown"
        quota = track["quota"]
        remaining = quota["remaining_percent"]
        remaining_text = f"{remaining:.1f}%" if remaining is not None else "unknown"
        print(
            f"{track['track']}: {track['route']} | {track['profile']} | {track['billing']} | "
            f"{cost_text} | capability {score} | quota {quota['state']} ({remaining_text})"
        )


def print_results(results: Sequence[Mapping[str, Any]], as_json: bool) -> None:
    if as_json:
        print(json.dumps({"workers": list(results)}, indent=2))
        return
    for result in results:
        state = "passed" if result["exit_code"] == 0 else f"failed ({result['exit_code']})"
        print(f"\n[{result['track']}] {state} via {result['route']} in {result['duration_seconds']}s")
        if result["report"]:
            print(result["report"])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--manifest", help="JSON track manifest")
    source.add_argument("--workflow", choices=workflows.available_workflows(), help="skill workflow preset")
    parser.add_argument("--task", help="parent request used by a workflow preset")
    parser.add_argument("--config", help="router config path")
    parser.add_argument("--refresh", action="store_true", help="refresh Kilo and AA metadata")
    parser.add_argument("--run", action="store_true", help="launch workers after producing the route plan")
    parser.add_argument("--json", action="store_true", help="print machine-readable output")
    parser.add_argument("--max-workers", type=int, default=4)
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    parser.add_argument("--allow-edits", action="store_true", help="allow manifest tracks to edit owned paths")
    parser.add_argument("--auto", action="store_true", help="pass Kilo's dangerous auto-approval flag")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 1 <= args.max_workers <= MAX_TRACKS:
        raise router.RouterError(f"--max-workers must be between 1 and {MAX_TRACKS}")
    if args.timeout <= 0:
        raise router.RouterError("--timeout must be positive")
    manifest_path = Path(args.manifest).expanduser() if args.manifest else None
    plan, prepared = build_plan(manifest_path, args.workflow, args.task, args.config, args.refresh, args.allow_edits)
    if not args.run:
        print_plan(plan, args.json)
        return 0
    results = launch_workers(prepared, args.max_workers, args.timeout, args.auto)
    if args.json:
        print(json.dumps({**plan, "workers": results}, indent=2))
    else:
        print_plan(plan, False)
        print_results(results, False)
    return 0 if all(result["exit_code"] == 0 for result in results) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except router.RouterError as error:
        print(f"route-subagents: {error}", file=sys.stderr)
        raise SystemExit(2)
