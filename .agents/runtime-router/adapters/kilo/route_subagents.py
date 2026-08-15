#!/usr/bin/env python3
"""ARR-backed bounded Kilo workflow launcher for Kraken."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
import re
import shutil
import sys
import time
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
from agent_runtime_router import TaskRequest, CostClass  # noqa: E402
from agent_runtime_router.quota import apply_quota_evidence  # noqa: E402
from agent_runtime_router.cost import (  # noqa: E402
    TaskCostSummary,
    WorkflowCostReport,
    TaskActualCostSummary,
    WorkflowActualCostReport,
    summarize_workflow_cost,
    summarize_actual_workflow_cost,
)

from adapter import build_adapter, load_json, KILO_MAX_OUTPUT_BYTES  # noqa: E402
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
    _tps_evidence_digest,
    _tps_probe_priority,
)
from quota import collect_quota_evidence  # noqa: E402
from benchmarks import apply_benchmark_quality  # noqa: E402


_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f]")
_SAFE_WORKFLOW = re.compile(r"[^A-Za-z0-9._-]+")


def _workflow_uses_distinct_routes(workflow: str | None) -> bool:
    """Prefer route diversity for every named multi-track workflow.

    A workflow is a fan-out, so silently sending every track to the same model
    defeats the point of independent evidence.  ``--allow-route-reuse`` remains
    an explicit escape hatch for workflows where reuse is intentional.
    """

    return workflow is not None


def _diversity_key(candidate: Any) -> str:
    """Treat free/paid aliases of one model as the same diversity bucket."""

    model = str(getattr(candidate, "model", "")).strip().lower()
    if model.endswith(":free"):
        model = model[:-5]
    return model or str(getattr(candidate, "candidate_id", ""))


def _persist_worker_reports(
    target: Path,
    workflow: str | None,
    results: list[Any],
) -> tuple[str, list[dict[str, Any]]]:
    """Persist only ARR-redacted worker summaries for the parent to inspect.

    The generic launcher intentionally keeps model output out of its canonical
    ``ExecutionReport`` schema.  A target workflow still needs a discoverable
    place for the bounded report, otherwise agents tend to bypass ARR and run
    native same-model commands just to recover the findings.
    """

    slug = _SAFE_WORKFLOW.sub("-", workflow or "manifest").strip("-.") or "manifest"
    run_id = (
        f"{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}-"
        f"{time.time_ns()}-{os.getpid()}"
    )
    directory = (
        target
        / ".agents"
        / "runtime-router"
        / "harnesses"
        / "kilo"
        / "workflows"
        / slug
        / run_id
    )
    directory.mkdir(parents=True, exist_ok=False)
    enriched: list[dict[str, Any]] = []
    for result in results:
        track = _SAFE_WORKFLOW.sub("-", str(result.track)).strip("-.") or "track"
        report = str(result.report or "worker output unavailable")
        report_path = directory / f"{track}.report.txt"
        report_path.write_text(report, encoding="utf-8")
        payload = result.to_execution_report(adapter_status="kilo").to_dict()
        payload.update(
            {
                "report_path": str(report_path.relative_to(target)),
                "report_sha256": hashlib.sha256(report.encode("utf-8")).hexdigest(),
                "report_bytes": len(report.encode("utf-8")),
            }
        )
        enriched.append(payload)
    manifest = {
        "schema_version": 1,
        "workflow": workflow or "manifest",
        "run_id": run_id,
        "reports": [item["report_path"] for item in enriched],
    }
    (directory / "manifest.json").write_text(
        json.dumps(manifest, sort_keys=True, indent=2) + "\n", encoding="utf-8"
    )
    return str(directory.relative_to(target)), enriched


class _NoRouteError(ValueError):
    """Carry bounded route evidence to the structured workflow error."""

    def __init__(self, track: Track, decision: Any) -> None:
        super().__init__(f"no_route:{track.id}")
        self.track = track
        self.decision = decision


def _no_route_payload(track: Track, decision: Any) -> dict[str, Any]:
    """Render safe, bounded rejection evidence for one failed track."""
    evaluations = tuple(getattr(decision, "evaluations", ()))
    counts: Counter[str] = Counter()
    candidates: list[dict[str, Any]] = []
    for evaluation in evaluations:
        reasons = tuple(str(reason) for reason in getattr(evaluation, "reasons", ()))
        counts.update(reasons)
        if len(candidates) < 8:
            candidate = getattr(evaluation, "candidate", None)
            candidate_id = getattr(candidate, "candidate_id", None)
            if isinstance(candidate_id, str):
                candidates.append(
                    {
                        "candidate_id": candidate_id,
                        "reasons": list(reasons[:8]),
                        "effort": (
                            evaluation.effort.value
                            if getattr(evaluation, "effort", None) is not None
                            else None
                        ),
                        "variant": getattr(evaluation, "variant", None),
                    }
                )
    return {
        "status": "INCOMPLETE",
        "error_code": "no_route",
        "track": track.id,
        "profile": track.profile,
        "candidate_count": len(evaluations),
        "fallback_used": bool(getattr(decision, "fallback_used", False)),
        "rejection_counts": dict(
            sorted(counts.items(), key=lambda item: (-item[1], item[0]))[:16]
        ),
        "candidates": candidates,
    }


def _sanitize_prompt(text: str) -> str:
    """Strip control characters that ARR's bounded argv contract rejects."""

    return _CONTROL_CHARS.sub(" ", text).strip()


# Injected into every track prompt so the worker audits the repository it was
# launched inside (its current working directory) instead of guessing a
# subdirectory named after the project (e.g. /workspace/kraken-rebalancer).
_WORKSPACE_DIRECTIVE = (
    " The target repository is your current working directory (the project "
    "root). Audit it directly using relative paths or your cwd; do not cd into "
    "or search for a subdirectory named after the project."
)


def _workflow_tracks(path: Path, workflow: str, parent_task: str) -> list[Track]:
    value = load_json(path)
    workflows = value.get("workflows")
    raw_tracks = workflows.get(workflow) if isinstance(workflows, dict) else None
    if not isinstance(raw_tracks, list) or not raw_tracks:
        raise ValueError("workflow_unknown")
    assembled = (
        f"Parent request: {parent_task}  Track focus: {item.get('task', '')}{_WORKSPACE_DIRECTIVE}"
        for item in raw_tracks
    )
    tracks = load_manifest_tracks(
        {"tracks": [{**item, "task": _sanitize_prompt(text)} for item, text in zip(raw_tracks, assembled)]}
    )
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
        "--allow-route-reuse",
        action="store_true",
        help="reuse a model family across tracks (workflow fan-outs default to distinct routes)",
    )
    parser.add_argument(
        "--free-only",
        action="store_true",
        help="reject paid and unknown-cost candidates for every track",
    )
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument(
        "--prepare-evidence",
        action="store_true",
        help="explicitly refresh bounded TPS/readiness/quality evidence without launching workers",
    )
    parser.add_argument("--approve", action="store_true")
    parser.add_argument(
        "--approve-cost",
        action="store_true",
        help="explicitly authorize estimated task/workflow expenditure for paid routes",
    )
    parser.add_argument("--max-readiness-probes", type=int, default=None)
    parser.add_argument("--json", action="store_true")
    parser.add_argument(
        "--pretty",
        action="store_true",
        help="accepted for harness parity; JSON remains machine-readable",
    )
    parser.add_argument(
        "--worker-timeout",
        type=int,
        default=1800,
        help="per-track worker timeout in seconds (free models can be slow)",
    )
    parser.add_argument("--task", dest="task_option")
    parser.add_argument("task", nargs="?")
    args = parser.parse_args(argv)
    task_text = args.task_option or args.task or os.environ.get("TASK")
    if not task_text:
        parser.error("a task is required")
    if args.prepare_evidence and not args.approve:
        print(json.dumps({"status": "INCOMPLETE", "error_code": "evidence_approval_required"}, sort_keys=True))
        return 2
    if args.prepare_evidence and args.refresh:
        print(json.dumps({"status": "INCOMPLETE", "error_code": "discovery_requires_separate_approval"}, sort_keys=True))
        return 2
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
        distinct_routes = (
            (args.distinct_routes or _workflow_uses_distinct_routes(args.workflow))
            and not args.allow_route_reuse
        )
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
        adapter = build_adapter(target, executable, timeout_seconds=args.worker_timeout)
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
        quota_cache_path = target / ".agents/runtime-router/harnesses/kilo/quota.json"
        quota = collect_quota_evidence(
            candidates,
            provider_policy,
            harness_id="kilo",
            approve=args.approve,
            cache_path=quota_cache_path,
        )
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
            source_digest=_tps_evidence_digest(policy, executable),
            priority_candidates=tuple(tps_priority),
            adapter=adapter,
        )
        prepared: list[LaunchItem] = []
        used: set[str] = set()
        readiness_probes = [readiness_budget * max(1, len(tracks))]
        records: list[dict[str, Any]] = []
        for track in tracks:
            profile = _profile(profiles, track.profile)
            task = _task(track.profile, profile, track.task, candidate=None, sensitive=False)
            profile_candidates = _apply_profile_quality(candidates, profile)
            available = tuple(
                candidate
                for candidate in profile_candidates
                if not distinct_routes or _diversity_key(candidate) not in used
            )
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
                raise _NoRouteError(track, decision)
            selected = decision.selected
            used.add(_diversity_key(selected))
            launch_task, launch_candidates = _prepare_launch_binding(
                task,
                available,
                decision,
                policy=policy,
                tps=tps,
                target=target,
                executable=executable,
                catalog_digest=cache.source_digest,
                approve=args.approve,
            )
            records.append({"track": track.id, "route": selected.candidate_id, "profile": track.profile, "effort": decision.selected_effort.value if decision.selected_effort else None, "variant": decision.selected_variant, "billing": selected.billing})
            prepared.append(LaunchItem(track=track, selection=selected, prompt=track.task, candidates=launch_candidates, policy=policy.routing_policy, task=launch_task, effort=decision.selected_effort, variant=decision.selected_variant))

        cost_summaries: list[TaskCostSummary] = []
        for track, item in zip(tracks, prepared):
            profile = _profile(profiles, track.profile)
            in_tok = int(profile.get("input_tokens", 8000))
            out_tok = int(profile.get("output_tokens", 3000))
            eff_cost = float(item.selection.effective_cost or 0.0)
            if item.selection.billing != "free" and eff_cost == 0.0:
                # Approximate default estimate when rate table is not explicitly bound
                eff_cost = 0.002 if ("mini" in item.selection.candidate_id or "flash" in item.selection.candidate_id or "oss" in item.selection.candidate_id) else 0.008

            free_alt_id = None
            free_alt_qual = None
            if item.selection.billing != "free":
                prof_cands = _apply_profile_quality(candidates, profile)
                free_cands = [
                    c for c in prof_cands
                    if (c.billing == "free" or getattr(c, "cost_class", None) is CostClass.FREE)
                    and c.quality is not None and c.quality >= float(profile.get("minimum", 0))
                ]
                if free_cands:
                    best_free = max(free_cands, key=lambda c: c.quality or 0.0)
                    free_alt_id = best_free.candidate_id
                    free_alt_qual = best_free.quality

            cost_summaries.append(
                TaskCostSummary(
                    task_id=track.id,
                    candidate_id=item.selection.candidate_id,
                    provider=item.selection.provider,
                    billing=item.selection.billing or "unknown",
                    currency="USD",
                    input_tokens=in_tok,
                    output_tokens=out_tok,
                    estimated_cost=eff_cost,
                    free_alternative_candidate_id=free_alt_id,
                    free_alternative_quality=free_alt_qual,
                )
            )

        # Look up account balance if available from quota evidence
        acct_balance = None
        for q_ev in quota.values():
            if q_ev.remaining_balance is not None and q_ev.remaining_balance > 0:
                acct_balance = q_ev.remaining_balance
                break

        cost_report = summarize_workflow_cost(
            args.workflow or "workflow",
            cost_summaries,
            account_balance=acct_balance,
            currency="USD",
        )

        plan = {
            "schema_version": 1,
            "status": "PLAN",
            "records": records,
            "cost_report": cost_report.to_dict(),
            "distinct_routes": distinct_routes,
        }
        if args.prepare_evidence:
            print(json.dumps({**plan, "status": "EVIDENCE_READY", "workers_not_started": True}, sort_keys=True))
            return 0
        if not args.approve:
            print(json.dumps(plan, sort_keys=True))
            return 0
        if cost_report.any_paid and not args.approve_cost:
            print(json.dumps({
                **plan,
                "status": "AWAITING_COST_APPROVAL",
                "message": f"Workflow requires paid execution with estimated total cost of ${cost_report.total_estimated_cost:.4f} USD. Pass --approve-cost to confirm or --free-only to route to free models.",
            }, sort_keys=True))
            return 2
        from agent_runtime_router.supervisor import ExecutionApproval
        results = launch_tracks(
            prepared,
            timeout=args.worker_timeout,
            adapter=adapter,
            approval_factory=lambda item, dispatch, command: ExecutionApproval(f"kraken-{item.track.id}", dispatch.task_id, dispatch.candidate_id, command.sha256),
            max_workers=min(len(prepared), 8),
            max_output_bytes=KILO_MAX_OUTPUT_BYTES,
            start_delay_seconds=float(provider_policy.get("coldStart", {}).get("staggerSeconds", 0)),
            allow_failover=all(item.track.read_only for item in prepared),
            allow_cold_start_retry=all(item.track.read_only for item in prepared),
        )
        report_directory, report_results = _persist_worker_reports(
            target, args.workflow, results
        )
        post_quota = collect_quota_evidence(
            candidates,
            provider_policy,
            harness_id="kilo",
            approve=True,
            cache_path=quota_cache_path,
        )
        ending_balance = None
        for q_ev in post_quota.values():
            if q_ev.remaining_balance is not None and q_ev.remaining_balance > 0:
                ending_balance = q_ev.remaining_balance
                break

        actual_summaries: list[TaskActualCostSummary] = []
        for track, item, result in zip(tracks, prepared, results):
            profile = _profile(profiles, track.profile)
            in_tok = int(profile.get("input_tokens", 8000))
            out_chars = len(str(getattr(result, "report", "") or "").encode("utf-8"))
            act_out_tok = max(1, out_chars // 4)
            eff_cost = float(item.selection.effective_cost or 0.0)
            if item.selection.billing != "free" and eff_cost == 0.0:
                eff_cost = 0.002 if ("mini" in item.selection.candidate_id or "flash" in item.selection.candidate_id or "oss" in item.selection.candidate_id) else 0.008

            est_cost = eff_cost if item.selection.billing != "free" else 0.0
            act_cost = (
                est_cost * (act_out_tok / float(profile.get("output_tokens", 3000)))
                if item.selection.billing != "free"
                else 0.0
            )
            variance = act_cost - est_cost
            actual_summaries.append(
                TaskActualCostSummary(
                    task_id=track.id,
                    candidate_id=item.selection.candidate_id,
                    provider=item.selection.provider,
                    billing=item.selection.billing,
                    currency="USD",
                    input_tokens=in_tok,
                    output_tokens=act_out_tok,
                    estimated_cost=round(est_cost, 6),
                    actual_cost=round(act_cost, 6),
                    variance=round(variance, 6),
                )
            )

        actual_cost_report = summarize_actual_workflow_cost(
            args.workflow or "workflow",
            actual_summaries,
            starting_balance=acct_balance,
            ending_balance=ending_balance,
            currency="USD",
        )

        output = {
            **plan,
            "status": "SUCCEEDED"
            if all(item.exit_code == 0 and not item.failure_kind for item in results)
            else "FAILED",
            "result_directory": report_directory,
            "results": report_results,
            "actual_cost_report": actual_cost_report.to_dict(),
        }
        print(json.dumps(output, sort_keys=True))
        return 0 if output["status"] == "SUCCEEDED" else 2
    except _NoRouteError as exc:
        print(json.dumps(_no_route_payload(exc.track, exc.decision), sort_keys=True))
        return 2
    except Exception as exc:
        code = str(exc).split(":", 1)[0] or "workflow_error"
        if not code.replace("_", "").replace("-", "").isalnum():
            code = "workflow_error"
        print(json.dumps({"status": "INCOMPLETE", "error_code": code}, sort_keys=True))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
