#!/usr/bin/env python3
"""ARR-backed bounded Kilo workflow launcher for Kraken."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from dataclasses import replace
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
# See ``run_arr_task.py``: this is a directory-relative calculation.
TARGET = HERE.parents[3]


def _requested_target() -> Path:
    """Resolve an explicit --target before selecting its receipt runtime."""

    for index, argument in enumerate(sys.argv[1:]):
        if argument == "--target" and index + 2 <= len(sys.argv[1:]):
            return Path(sys.argv[index + 2]).expanduser().resolve()
        if argument.startswith("--target="):
            return Path(argument.split("=", 1)[1]).expanduser().resolve()
    return TARGET


def _use_receipt_runtime() -> None:
    """Keep direct invocations on the selected target's installed ARR version."""

    runtime = _requested_target() / ".agents" / ".agent-runtime-router" / "venv"
    python = runtime / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if python.is_file() and Path(sys.executable).resolve() != python.resolve():
        os.execv(str(python), [str(python), str(Path(__file__).resolve()), *sys.argv[1:]])


if __name__ == "__main__":
    _use_receipt_runtime()

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from agent_runtime_router.dispatch import TaskPacket  # noqa: E402
from agent_runtime_router.harnesses.target import load_catalog_cache, load_target_policy, route_with_target_policy  # noqa: E402
from agent_runtime_router.launcher import LaunchItem, launch_tracks  # noqa: E402
from agent_runtime_router.workflow import Track, load_manifest_tracks  # noqa: E402
from agent_runtime_router import TaskRequest  # noqa: E402
from agent_runtime_router.quota import apply_quota_evidence  # noqa: E402

from adapter import build_adapter, load_json  # noqa: E402
from run_arr_task import (  # noqa: E402
    _apply_profile_quality,
    _cached_tps,
    _catalog_path,
    _load_or_discover,
    _profile,
    _readiness_settings,
    _route_with_readiness,
    _prepare_launch_binding,
    _task,
    _tps_probe_priority,
)
from quota import collect_quota_evidence  # noqa: E402
from benchmarks import apply_benchmark_quality  # noqa: E402


def _workflow_tracks(path: Path, workflow: str, parent_task: str) -> list[Track]:
    value = load_json(path)
    workflows = value.get("workflows")
    raw_tracks = workflows.get(workflow) if isinstance(workflows, dict) else None
    if not isinstance(raw_tracks, list) or not raw_tracks:
        raise ValueError("workflow_unknown")
    tracks = load_manifest_tracks({"tracks": [{**item, "task": f"Parent request:\n{parent_task}\n\nTrack focus:\n{item.get('task', '')}"} for item in raw_tracks]})
    return tracks


def _free_only_policy(policy: Any) -> Any:
    """Apply the launcher-level free-route restriction without changing target files."""

    return replace(
        policy,
        routing_policy=replace(
            policy.routing_policy,
            allow_paid=False,
            allow_unknown_cost=False,
            allow_free=True,
        ),
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", default=str(TARGET))
    parser.add_argument("--manifest")
    parser.add_argument("--workflow")
    parser.add_argument("--distinct-routes", action="store_true")
    parser.add_argument(
        "--free-only",
        action="store_true",
        help="reject paid and unknown-cost candidates for every track",
    )
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--approve", action="store_true")
    parser.add_argument("--max-readiness-probes", type=int, default=None)
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--task", dest="task_option")
    parser.add_argument("task", nargs="?")
    args = parser.parse_args(argv)
    task_text = args.task_option or args.task
    if not task_text:
        parser.error("a task is required")
    target = Path(args.target).resolve()
    try:
        policy = load_target_policy(target / ".agents/runtime-router/policy.json")
        if args.free_only:
            policy = _free_only_policy(policy)
        provider_policy = load_json(target / ".agents/runtime-router/adapters/kilo/provider-policy.json")
        (
            readiness_budget,
            readiness_cache_ttl,
            readiness_failure_ttl,
            readiness_timeout,
        ) = _readiness_settings(
            provider_policy, requested_max_probes=args.max_readiness_probes
        )
        profiles = load_json(target / ".agents/runtime-router/adapters/kilo/profiles.json")
        if args.manifest:
            manifest_path = (target / args.manifest).resolve()
            manifest_path.relative_to(target)
            tracks = load_manifest_tracks(load_json(manifest_path))
        elif args.workflow:
            tracks = _workflow_tracks(target / ".agents/runtime-router/adapters/kilo/workflows.json", args.workflow, task_text)
        else:
            raise ValueError("manifest_or_workflow_required")
        catalog_path = _catalog_path(target, target / ".agents/runtime-router/catalog-cache.json")
        executable = shutil.which("kilo")
        if args.refresh and executable:
            # Refresh is an explicit metadata/discovery request.  It may
            # produce a plan without launching workers; ``--approve`` remains
            # required for execution and for any provider task.
            cache = _load_or_discover(target, catalog_path, executable, provider_policy, policy, refresh=True, approve=args.approve)
        else:
            try:
                cache = load_catalog_cache(catalog_path)
            except Exception as exc:
                raise ValueError("catalog_missing_or_unusable") from exc
        if cache is None:
            raise ValueError("catalog_missing_or_unusable")
        executable = str(Path(shutil.which("kilo") or "").resolve())
        if not executable or executable == ".":
            raise ValueError("kilo_executable_missing")
        adapter = build_adapter(target, executable)
        # Keep the catalog evidence untouched until each track's profile is
        # known.  A workflow can mix coding, review, and critical tracks; the
        # legacy router evaluated each profile's primary/secondary metrics
        # independently rather than reusing the first track's metric.
        candidates = apply_benchmark_quality(
            policy.filter_candidates(cache.candidates_for_route()),
            provider_policy,
            target,
            refresh=args.refresh,
            allow_network=args.approve,
        )
        quota = collect_quota_evidence(candidates, provider_policy, harness_id="kilo", approve=args.approve)
        candidates = apply_quota_evidence(candidates, quota, harness_id="kilo")
        # Probe only the highest-ranked free route(s) needed by this workflow,
        # rather than whichever catalog rows happened to be listed first.
        tps_priority: list[Any] = []
        for track in tracks:
            profile = _profile(profiles, track.profile)
            track_task = _task(
                track.profile, profile, track.task, candidate=None, sensitive=False
            )
            tps_priority.extend(
                _tps_probe_priority(
                    track_task,
                    _apply_profile_quality(candidates, profile),
                    policy,
                )
            )
        tps = _cached_tps(
            target,
            provider_policy,
            candidates,
            policy,
            approve=args.approve,
            source_digest=cache.source_digest,
            priority_candidates=tuple(tps_priority),
            adapter=adapter,
        )
        prepared: list[LaunchItem] = []
        used: set[str] = set()
        readiness_probes = [readiness_budget]
        records: list[dict[str, Any]] = []
        for track in tracks:
            profile = _profile(profiles, track.profile)
            task = _task(track.profile, profile, track.task, candidate=None, sensitive=False)
            profile_candidates = _apply_profile_quality(candidates, profile)
            available = tuple(candidate for candidate in profile_candidates if not args.distinct_routes or candidate.candidate_id not in used)
            decision = _route_with_readiness(
                task,
                available,
                policy,
                quota=quota,
                tps=tps,
                target=target,
                adapter=adapter,
                executable=executable,
                catalog_digest=cache.source_digest,
                approve=args.approve,
                max_probes=readiness_budget,
                cache_ttl_seconds=readiness_cache_ttl,
                failure_cache_ttl_seconds=readiness_failure_ttl,
                timeout_seconds=readiness_timeout,
                probe_budget=readiness_probes,
            )
            if decision.selected is None:
                raise ValueError(f"no_route:{track.id}")
            selected = decision.selected
            used.add(selected.candidate_id)
            launch_task, launch_candidates = _prepare_launch_binding(
                task,
                available,
                decision,
                policy=policy,
                tps=tps,
                target=target,
                executable=executable,
                catalog_digest=cache.source_digest,
            )
            records.append({"track": track.id, "route": selected.candidate_id, "profile": track.profile, "effort": decision.selected_effort.value if decision.selected_effort else None, "variant": decision.selected_variant, "billing": selected.billing})
            prepared.append(LaunchItem(track=track, selection=selected, prompt=track.task, candidates=launch_candidates, policy=policy.routing_policy, task=launch_task, effort=decision.selected_effort, variant=decision.selected_variant))
        plan = {"schema_version": 1, "status": "PLAN", "records": records}
        if not args.approve:
            print(json.dumps(plan, sort_keys=True))
            return 0
        from agent_runtime_router.supervisor import ExecutionApproval
        results = launch_tracks(
            prepared,
            timeout=900,
            adapter=adapter,
            approval_factory=lambda item, dispatch, command: ExecutionApproval(f"kraken-{item.track.id}", dispatch.task_id, dispatch.candidate_id, command.sha256),
            max_workers=min(len(prepared), 8),
            start_delay_seconds=float(provider_policy.get("coldStart", {}).get("staggerSeconds", 0)),
            allow_failover=all(item.track.read_only for item in prepared),
            allow_cold_start_retry=all(item.track.read_only for item in prepared),
        )
        output = {**plan, "status": "SUCCEEDED" if all(item.exit_code == 0 and not item.failure_kind for item in results) else "FAILED", "results": [item.to_execution_report(adapter_status="kilo").to_dict() for item in results]}
        print(json.dumps(output, sort_keys=True))
        return 0 if output["status"] == "SUCCEEDED" else 2
    except Exception as exc:
        code = str(exc).split(":", 1)[0] or "workflow_error"
        if not code.replace("_", "").replace("-", "").isalnum():
            code = "workflow_error"
        print(json.dumps({"status": "INCOMPLETE", "error_code": code}, sort_keys=True))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
