#!/usr/bin/env python3
"""Plan and launch bounded subagents with independently selected routes."""

from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import copy
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
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
REPORT_FAILURE_KIND = "report_contract"
REPORT_PROTOCOL_MARKERS = ("tool_code", "ctx_", "compress {")
DEFAULT_REPORT_SUBDIRECTORY = Path(".cache") / "kilo" / "model-router" / "reports"


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
    used_routes: set[str] = set()
    require_distinct_routes = workflows.requires_distinct_routes(workflow)

    for track in tracks:
        requested_profile = str(track.get("profile", "auto"))
        profile_name, profile = router.profile_config(config, requested_profile, track["task"])
        track_candidates = copy.deepcopy(candidates)
        sensitive = router.is_sensitive(track["task"], profile_name)
        try:
            selected = router.select_candidate(
                track_candidates,
                profile,
                config,
                sensitive,
                excluded_routes=used_routes if require_distinct_routes else None,
            )
        except router.RouterError:
            if not require_distinct_routes:
                raise
            warnings.append(f"route diversity unavailable for track {track['id']}; reused the best available route")
            selected = router.select_candidate(track_candidates, profile, config, sensitive)
        used_routes.add(selected.route)
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
        "route_diversity_required": require_distinct_routes,
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


def extract_report(stdout: str | bytes, stderr: str | bytes) -> tuple[str, bool]:
    streams = (stdout, stderr)
    text_blocks: list[str] = []
    for stream in streams:
        if isinstance(stream, bytes):
            stream = stream.decode(errors="replace")
        for line in stream.splitlines():
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(event, Mapping) or event.get("type") != "text":
                continue
            part = event.get("part")
            text = part.get("text") if isinstance(part, Mapping) else event.get("text")
            if isinstance(text, str) and text.strip():
                text_blocks.append(text)

    report = compact_output("\n".join(text_blocks)) if text_blocks else ""
    usable = bool(report) and not any(marker in report.lower() for marker in REPORT_PROTOCOL_MARKERS)
    if usable:
        return report, True
    fallback = compact_output(
        "\n".join(
            stream.decode(errors="replace") if isinstance(stream, bytes) else stream
            for stream in streams
        )
    )
    return fallback, False


def launch_worker(
    item: Mapping[str, Any], timeout: int, allow_auto: bool, workspace: Path | None = None
) -> dict[str, Any]:
    track = item["track"]
    selection = item["selection"]
    workspace = workspace or router.ROOT
    command = [
        "kilo",
        "run",
        "--dir",
        str(workspace),
        "--model",
        str(selection["route"]),
        "--format",
        "json",
        "--title",
        f"routed-{track['id']}",
    ]
    variant = track.get("variant")
    if variant:
        command.extend(["--variant", str(variant)])
    if allow_auto:
        command.append("--auto")
    command.append(item["prompt"])
    started = time.monotonic()
    try:
        completed = subprocess.run(
            command,
            cwd=workspace,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        output, usable_report = extract_report(completed.stdout, completed.stderr)
        exit_code = completed.returncode
        failure_kind = availability.failure_kind(f"{completed.stdout}\n{completed.stderr}") if exit_code else None
        if exit_code == 0 and not usable_report:
            exit_code = 1
            failure_kind = REPORT_FAILURE_KIND
            output = output or "worker returned no final text report"
        return {
            "track": track["id"],
            "route": selection["route"],
            "exit_code": exit_code,
            "duration_seconds": round(time.monotonic() - started, 1),
            "report": output,
            "failure_kind": failure_kind,
        }
    except subprocess.TimeoutExpired as error:
        stdout = error.stdout.decode(errors="replace") if isinstance(error.stdout, bytes) else (error.stdout or "")
        stderr = error.stderr.decode(errors="replace") if isinstance(error.stderr, bytes) else (error.stderr or "")
        output, _ = extract_report(stdout, stderr)
        return {
            "track": track["id"],
            "route": selection["route"],
            "exit_code": None,
            "duration_seconds": round(time.monotonic() - started, 1),
            "report": f"worker timed out after {timeout}s\n{output}".strip(),
            "failure_kind": "provider_unavailable",
        }


def launch_with_failover(
    item: Mapping[str, Any], timeout: int, allow_auto: bool, workspace: Path | None = None
) -> dict[str, Any]:
    current = dict(item)
    attempted_routes: set[str] = set()
    excluded_providers: set[str] = set()
    failovers: list[dict[str, Any]] = []
    for _ in range(MAX_FAILOVER_ATTEMPTS):
        result = launch_worker(current, timeout, allow_auto, workspace)
        attempted_routes.add(result["route"])
        result["attempted_routes"] = sorted(attempted_routes)
        result["failovers"] = failovers
        if result["exit_code"] == 0:
            return result

        kind = result.get("failure_kind")
        track = current["track"]
        if not kind or not bool(current["selection"].get("read_only", True)):
            return result
        if kind not in {"rate_limit", "credits", "provider_unavailable", "authentication", REPORT_FAILURE_KIND}:
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
    def launch_isolated(item: Mapping[str, Any]) -> dict[str, Any]:
        read_only = bool(item["selection"].get("read_only", True))
        with worker_workspace(read_only) as workspace:
            return launch_with_failover(item, timeout, allow_auto, workspace)

    with concurrent.futures.ThreadPoolExecutor(max_workers=min(max_workers, len(prepared))) as executor:
        futures = [executor.submit(launch_isolated, item) for item in prepared]
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


def default_report_dir() -> Path:
    configured = os.environ.get("KILO_MODEL_ROUTER_REPORT_DIR")
    return Path(configured).expanduser() if configured else Path.home() / DEFAULT_REPORT_SUBDIRECTORY


def _route_parts(route: str) -> tuple[str, str]:
    provider, separator, model = route.partition("/")
    return (provider, model) if separator else (provider, "unknown")


def _safe_report_label(value: str) -> str:
    label = re.sub(r"[^A-Za-z0-9_.-]+", "-", value).strip("-")
    return label[:48] or "workflow"


def _safe_scope(files: Sequence[str]) -> list[str]:
    return ["<absolute-path>" if Path(path).is_absolute() else path for path in files]


def _atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=path.parent,
        prefix=f".{path.name}.",
        delete=False,
    ) as temporary:
        temporary.write(content)
        temporary.flush()
        os.fsync(temporary.fileno())
        temporary_path = Path(temporary.name)
    os.replace(temporary_path, path)


def _ignore_read_only_files(_: str, names: list[str]) -> list[str]:
    ignored = {".git", ".gradle", "build", "node_modules", "logs", "rebalancer-config.json"}
    return [
        name
        for name in names
        if name in ignored
        or name.startswith(".env")
        or name.endswith((".db", ".sqlite", ".sqlite3"))
    ]


@contextlib.contextmanager
def worker_workspace(read_only: bool) -> Any:
    if not read_only:
        yield router.ROOT
        return
    with tempfile.TemporaryDirectory(prefix="kilo-routed-") as directory:
        workspace = Path(directory) / "repository"
        shutil.copytree(router.ROOT, workspace, ignore=_ignore_read_only_files)
        yield workspace


def _report_track(item: Mapping[str, Any], planned: Mapping[str, Any], result: Mapping[str, Any]) -> dict[str, Any]:
    track = item["track"]
    route = str(result.get("route", planned["route"]))
    actual_provider, actual_model = _route_parts(route)
    cost = planned.get("cost", {})
    capability = planned.get("capability", {})
    quota = planned.get("quota", {})
    return {
        "track": track["id"],
        "scope": _safe_scope(track.get("files", [])),
        "track_role": _safe_report_label(str(planned.get("agent", DEFAULT_AGENT))),
        "read_only": bool(planned.get("read_only", True)),
        "profile": planned.get("profile"),
        "planned": {
            "route": planned.get("route"),
            "provider": planned.get("provider"),
            "model": planned.get("model"),
            "billing": planned.get("billing"),
            "effective_cost": cost.get("effective"),
            "cost_source": cost.get("source"),
            "aa_cost_per_task": cost.get("aa_cost_per_task"),
            "estimated_token_cost": cost.get("estimated_token_cost"),
            "capability": capability.get("score"),
            "capability_source": capability.get("source"),
            "quota_state": quota.get("state"),
            "quota_remaining_percent": quota.get("remaining_percent"),
            "aa": planned.get("aa"),
        },
        "used": {
            "route": route,
            "provider": actual_provider,
            "model": actual_model,
            "attempted_routes": list(result.get("attempted_routes", [route])),
        },
        "result": {
            "status": "passed" if result.get("exit_code") == 0 else "failed",
            "exit_code": result.get("exit_code"),
            "duration_seconds": result.get("duration_seconds"),
            "failure_kind": result.get("failure_kind"),
            "failovers": [
                {
                    "from": failover.get("from"),
                    "reason": failover.get("reason"),
                    "cooldown_seconds": failover.get("cooldown_seconds"),
                }
                for failover in result.get("failovers", [])
            ],
        },
    }


def _markdown_report(payload: Mapping[str, Any]) -> str:
    lines = [
        "# Routed Worker Report",
        "",
        f"- Run: `{payload['run_id']}`",
        f"- Completed: `{payload['completed_at_utc']}`",
        f"- Workflow: `{payload['workflow']}`",
        f"- Artificial Analysis data: `{payload['artificial_analysis']}`",
        f"- Distinct routes required: `{payload['route_diversity_required']}`",
        f"- Tracks: `{len(payload['tracks'])}`",
        "",
        "Persistent reports intentionally omit parent prompts, worker report text, credentials, and raw provider errors.",
        "",
    ]
    for track in payload["tracks"]:
        planned = track["planned"]
        used = track["used"]
        result = track["result"]
        scope = ", ".join(f"`{path}`" for path in track["scope"]) or "minimum track paths"
        remaining = planned["quota_remaining_percent"]
        remaining_text = f"{remaining:.1f}" if isinstance(remaining, (int, float)) else "unknown"
        lines.extend(
            [
                f"## {track['track']}",
                f"- Scope: {scope}",
                f"- Profile / track role: `{track['profile']}` / `{track['track_role']}`",
                f"- Planned: `{planned['provider']}/{planned['model']}` ({planned['billing']})",
                f"- Used: `{used['provider']}/{used['model']}`",
                f"- Capability / quota: `{planned['capability']}` / `{planned['quota_state']}` "
                f"({remaining_text}% remaining)",
                f"- Cost basis: `{planned['effective_cost']}` ({planned['cost_source']})",
                f"- Result: `{result['status']}` in `{result['duration_seconds']}s`",
                f"- Failovers: `{len(result['failovers'])}`",
                "",
            ]
        )
    return "\n".join(lines)


def write_run_report(
    plan: Mapping[str, Any],
    prepared: Sequence[Mapping[str, Any]],
    results: Sequence[Mapping[str, Any]],
    report_dir: str | Path | None = None,
) -> dict[str, str]:
    run_id = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"-{uuid.uuid4().hex[:8]}"
    tracks_by_id = {item["track"]["id"]: item for item in prepared}
    results_by_id = {result["track"]: result for result in results}
    tracks = [
        _report_track(tracks_by_id[planned["track"]], planned, results_by_id[planned["track"]])
        for planned in plan["tracks"]
    ]
    payload = {
        "schema": 1,
        "run_id": run_id,
        "completed_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "workflow": plan.get("workflow") or "custom-manifest",
        "artificial_analysis": plan.get("aa"),
        "route_diversity_required": bool(plan.get("route_diversity_required", False)),
        "tracks": tracks,
    }
    directory = Path(report_dir).expanduser() if report_dir else default_report_dir()
    label = _safe_report_label(str(payload["workflow"]))
    stem = f"{run_id}-{label}"
    json_path = directory / f"{stem}.json"
    markdown_path = directory / f"{stem}.md"
    _atomic_write(json_path, json.dumps(payload, indent=2) + "\n")
    _atomic_write(markdown_path, _markdown_report(payload))
    return {"json": str(json_path), "markdown": str(markdown_path), "run_id": run_id}


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
    parser.add_argument("--report-dir", help="directory for secret-free run reports")
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
    try:
        route_report = write_run_report(plan, prepared, results, args.report_dir)
    except OSError:
        route_report = None
        print("Warning: unable to write the secret-free routed worker report", file=sys.stderr)
    if args.json:
        print(json.dumps({**plan, "workers": results, "route_report": route_report}, indent=2))
    else:
        print_plan(plan, False)
        print_results(results, False)
        if route_report:
            print(f"\nRoute report: {route_report['markdown']}")
    return 0 if all(result["exit_code"] == 0 for result in results) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except router.RouterError as error:
        print(f"route-subagents: {error}", file=sys.stderr)
        raise SystemExit(2)
