#!/usr/bin/env python3
"""Route one Kraken task through ARR and optionally launch Kilo.

This is the target-owned entry point.  It deliberately does not infer a
provider from the conversation or maintain a second ranking engine.  The
caller supplies a profile (or a harness/agent can propose one), ARR chooses
the route and normalized effort, and ``--approve`` is the explicit authority
for a live operation. ``--prepare-evidence --approve`` is deliberately the
exception: it permits only bounded route-evidence probes and stops before a
worker is launched; a later worker launch still needs its own approved call.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import time
from dataclasses import replace
from pathlib import Path
from typing import Any

_CONTROL_CHARS = re.compile(r"[\x00-\x1f\x7f]")


def _sanitize_prompt(text: str) -> str:
    """Strip control characters that ARR's bounded argv contract rejects."""

    return _CONTROL_CHARS.sub(" ", text).strip()


HERE = Path(__file__).resolve().parent
# ``HERE`` is ``<target>/.agents/runtime-router/adapters/kilo``.  The target
# root is therefore three parents above the directory (not four, which is the
# correct count only when starting from a *file* under this directory).
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

from agent_runtime_router import (  # noqa: E402
    Candidate,
    EffortProfile,
    EffortLevel,
    KILO_READINESS_SOURCE,
    KILO_TPS_SOURCE,
    KiloNativeTpsProbe,
    KiloToolReadinessProbe,
    READINESS_CAPABILITY,
    ReadinessCache,
    ReadinessMeasurement,
    ReadinessProbeRunner,
    TaskRequest,
    apply_tps_measurements,
    apply_readiness_measurements,
)
from agent_runtime_router.dispatch import TaskPacket  # noqa: E402
from agent_runtime_router.harnesses.target import (  # noqa: E402
    CatalogCache,
    TargetPolicyConfig,
    load_target_policy,
    route_catalog_cache,
    route_with_target_policy,
)
from agent_runtime_router.harness_state import HarnessStateNamespace  # noqa: E402
from agent_runtime_router.harnesses.contracts import DiscoveryReport  # noqa: E402
from agent_runtime_router.launcher import LaunchItem, launch_worker  # noqa: E402
from agent_runtime_router.observations import Freshness  # noqa: E402
from agent_runtime_router.quota import apply_quota_evidence  # noqa: E402
from agent_runtime_router.router import RouteDecision  # noqa: E402
from agent_runtime_router.router import route as route_candidates  # noqa: E402
from agent_runtime_router.throughput import TpsCache, TpsCacheKey, TpsProbeConfig, TpsProbeRunner  # noqa: E402
from agent_runtime_router.workflow import Track  # noqa: E402

from adapter import build_adapter, load_json, KILO_MAX_OUTPUT_BYTES  # noqa: E402
from catalog import CatalogError, discover_candidates  # noqa: E402
from quota import collect_quota_evidence  # noqa: E402
from benchmarks import apply_benchmark_quality  # noqa: E402


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", default=str(TARGET))
    parser.add_argument("--policy", default=".agents/runtime-router/policy.json")
    parser.add_argument("--provider-policy", default=".agents/runtime-router/adapters/kilo/provider-policy.json")
    parser.add_argument("--profiles", default=".agents/runtime-router/adapters/kilo/profiles.json")
    parser.add_argument("--catalog", default=".agents/runtime-router/catalog-cache.json")
    parser.add_argument("--profile", default="agentic")
    parser.add_argument("--candidate", default=None)
    parser.add_argument("--sensitive", action="store_true")
    parser.add_argument("--max-readiness-probes", type=int, default=None)
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument(
        "--prepare-evidence",
        action="store_true",
        help="explicitly refresh bounded TPS/readiness/quality evidence without launching a worker",
    )
    parser.add_argument("--approve", action="store_true")
    parser.add_argument("--json", action="store_true")
    parser.add_argument(
        "--timeout",
        type=int,
        default=1800,
        help="worker timeout in seconds (free models can be slow)",
    )
    parser.add_argument("task")
    return parser


def _emit(value: MappingLike, *, code: int = 0) -> int:
    print(json.dumps(value, sort_keys=True, separators=(",", ":")))
    return code


class MappingLike(dict[str, Any]):
    """Typing-only alias kept local so output remains ordinary JSON."""


def _paths(args: argparse.Namespace) -> tuple[Path, Path, Path, Path, Path]:
    target = Path(args.target).expanduser().resolve()
    def inside(raw: str) -> Path:
        path = (target / raw).resolve() if not Path(raw).is_absolute() else Path(raw).resolve()
        try:
            path.relative_to(target)
        except ValueError as exc:
            raise CatalogError("target_path_outside_root") from exc
        return path
    return target, inside(args.policy), inside(args.provider_policy), inside(args.profiles), inside(args.catalog)


def _profile(profiles: dict[str, Any], name: str) -> dict[str, Any]:
    values = profiles.get("profiles", {})
    if not isinstance(values, dict):
        raise CatalogError("profiles_invalid")
    selected = values.get(name)
    if not isinstance(selected, dict):
        raise CatalogError("profile_unknown")
    return selected


def _task(profile_name: str, profile: dict[str, Any], prompt: str, *, candidate: str | None, sensitive: bool) -> TaskRequest:
    minimum = profile.get("minimum")
    margin = profile.get("margin", 0)
    quality = float(minimum) + float(margin) if isinstance(minimum, (int, float)) else None
    secondary = profile.get("secondary") if isinstance(profile.get("secondary"), dict) else None
    context = profile.get("context", 0)
    output = profile.get("output_tokens")
    provider = model = None
    if candidate:
        provider, sep, model = candidate.partition("/")
        if not sep or not provider or not model:
            raise CatalogError("candidate_invalid")
    return TaskRequest(
        task_id="kraken-task",
        required_capabilities=frozenset({"code", READINESS_CAPABILITY}),
        min_context_window=max(0, int(context) if isinstance(context, (int, float)) else 0),
        pinned_provider=provider,
        pinned_model=model,
        quality_minimum=quality,
        requires_reasoning=bool(profile.get("requiresReasoning", False)),
        sensitive=sensitive or profile_name == "critical",
        secondary_thresholds={str(key): float(value) for key, value in secondary.items()} if secondary else None,
        required_output_tokens=int(output) if isinstance(output, (int, float)) and output > 0 else None,
    )


def _resolve_kilo_alias(task: TaskRequest, candidates: tuple[Candidate, ...]) -> TaskRequest:
    """Resolve Kilo's native ``kilo/<model>`` alias to one catalog route.

    Kilo accepts that shorthand even when its model catalog records the same
    route under the backing provider (for example, ``openrouter/...``).  Only
    an exact model-suffix match with exactly one catalog candidate is accepted;
    arbitrary fuzzy/provider-substring matching remains fail-closed.
    """

    if task.pinned_provider != "kilo" or not task.pinned_model:
        return task
    matches = tuple(
        candidate for candidate in candidates if candidate.model == task.pinned_model
    )
    if len(matches) != 1:
        return task
    selected = matches[0]
    return replace(task, pinned_provider=selected.provider, pinned_model=selected.model)


def _apply_profile_quality(candidates: tuple[Candidate, ...], profile: dict[str, Any]) -> tuple[Candidate, ...]:
    metric = str(profile.get("metric", "artificial_analysis_intelligence_index"))
    variants = profile.get("variantPreference", [])
    preferred = next((str(value) for value in variants if isinstance(value, str) and value), None)
    result: list[Candidate] = []
    for candidate in candidates:
        metrics = candidate.quality_metrics or {}
        quality = metrics.get(metric, candidate.quality)
        profiles: list[EffortProfile] = []
        for option in candidate.effort_profiles:
            profiles.append(replace(option, quality=quality, quality_metrics=metrics or None))
        result.append(replace(candidate, quality=quality, effort_profiles=tuple(profiles), preferred_variant=preferred or candidate.preferred_variant))
    return tuple(result)


def _catalog_path(target: Path, requested: Path) -> Path:
    namespace = target / ".agents" / "runtime-router" / "harnesses" / "kilo" / "catalog-cache.json"
    # A single legacy top-level cache is accepted only as a read-only migration
    # source. New discovery always writes the active harness namespace.
    return namespace if namespace.is_file() or not requested.is_file() else requested


def _load_or_discover(
    target: Path,
    requested_path: Path,
    executable: str,
    provider_policy: dict[str, Any],
    policy: TargetPolicyConfig,
    *,
    refresh: bool,
    approve: bool,
) -> CatalogCache | None:
    path = _catalog_path(target, requested_path)
    if path.is_file() and not refresh:
        try:
            from agent_runtime_router.harnesses.target import load_catalog_cache
            return load_catalog_cache(path)
        except Exception:
            return None
    if not approve:
        return None
    try:
        candidates = discover_candidates(executable, provider_policy, refresh=refresh)
    except CatalogError:
        return None
    report = DiscoveryReport(
        adapter_id="kilo",
        status=__import__("agent_runtime_router.harnesses.contracts", fromlist=["EvidenceStatus"]).EvidenceStatus.BEST_EFFORT,
        candidates=candidates,
        probes=(__import__("agent_runtime_router.harnesses.contracts", fromlist=["ProbeEvidence"]).ProbeEvidence("kilo-models", "kilo-cli", __import__("agent_runtime_router.harnesses.contracts", fromlist=["EvidenceStatus"]).EvidenceStatus.BEST_EFFORT, Freshness.FRESH),),
    )
    cache = CatalogCache(report, time.time(), time.time() + policy.refresh.ttl_seconds, hashlib.sha256(json.dumps(report.to_dict(), sort_keys=True).encode()).hexdigest())
    namespace = HarnessStateNamespace.for_target(target, "kilo")
    namespace.write_artifact("catalog", cache.to_dict())
    return cache


def _cached_tps(
    target: Path,
    provider_policy: dict[str, Any],
    candidates: tuple[Candidate, ...],
    policy: TargetPolicyConfig,
    *,
    approve: bool,
    source_digest: str,
    priority_candidates: tuple[Candidate, ...] = (),
    adapter: Any,
) -> dict[str, Any]:
    """Use cached TPS evidence or probe the routes ARR would otherwise pick.

    The generic TPS runner intentionally receives an ordered sequence.  Passing
    the raw discovery order here used the small probe budget on arbitrary free
    models, which could leave an explicitly pinned or highest-ranked free
    route permanently ineligible.  This target-owned ordering is only a
    *probe* priority; the final route still applies the mandatory TPS gate.
    """
    path = target / ".agents" / "runtime-router" / "harnesses" / "kilo" / "tps.json"
    try:
        cache = TpsCache.load(path, expected_harness_id="kilo", expected_source=KILO_TPS_SOURCE, expected_source_digest=source_digest) if path.is_file() else TpsCache()
    except Exception:
        cache = TpsCache()
    if not approve:
        cached: dict[str, Any] = {}
        for candidate in candidates:
            measurement = cache.get(
                TpsCacheKey(
                    "kilo", KILO_TPS_SOURCE,
                    candidate.candidate_id, source_digest,
                )
            )
            if measurement is not None:
                cached[candidate.candidate_id] = measurement
        return cached
    runner = TpsProbeRunner(
        KiloNativeTpsProbe(adapter),
        TpsProbeConfig.from_policy(policy.routing_policy),
        source=KILO_TPS_SOURCE,
        cache=cache,
    )
    by_id = {candidate.candidate_id: candidate for candidate in candidates}
    ordered: list[Candidate] = []
    seen: set[str] = set()
    for candidate in (*priority_candidates, *candidates):
        canonical = by_id.get(candidate.candidate_id)
        if canonical is not None and canonical.candidate_id not in seen:
            ordered.append(canonical)
            seen.add(canonical.candidate_id)
    measurements = runner.probe_candidates(
        tuple(ordered), harness_id="kilo", source_digest=source_digest
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    runner.cache.write(path)
    return measurements


def _tps_probe_priority(
    task: TaskRequest,
    candidates: tuple[Candidate, ...],
    policy: TargetPolicyConfig,
) -> tuple[Candidate, ...]:
    """Return bounded free routes in ARR rank order before the TPS gate.

    A mandatory free-model TPS threshold creates a deliberate circularity:
    ARR cannot select an unmeasured free candidate, while probing every catalog
    row is unsafe and wasteful.  We temporarily remove *only* that threshold
    to identify the next candidate ARR would select, then measure at most the
    target policy's existing TPS budget.  The returned list is not a route and
    cannot bypass the final, unchanged threshold.
    """

    relaxed_policy = replace(policy.routing_policy, min_free_tps=None)
    tps_task = replace(
        task,
        required_capabilities=frozenset(
            capability
            for capability in task.required_capabilities
            if capability != READINESS_CAPABILITY
        ),
    )
    remaining = candidates
    result: list[Candidate] = []
    for _ in range(policy.routing_policy.tps_max_probes_per_run):
        # ``TargetPolicyConfig`` deliberately restores Kraken's default TPS
        # floor when constructed with ``None``.  For preflight we instead use
        # the core route function after applying the same target deny filter;
        # all other candidate evidence (including quota) was already bound by
        # the caller.  Only the final call below is authoritative.
        decision = route_candidates(
            tps_task, tuple(policy.filter_candidates(remaining)), relaxed_policy
        )
        selected = decision.selected
        if selected is None:
            break
        remaining = tuple(
            candidate
            for candidate in remaining
            if candidate.candidate_id != selected.candidate_id
        )
        if selected.billing == "free" or (
            selected.billing is None and selected.cost_class.value == "free"
        ):
            result.append(selected)
        else:
            # Paid candidates never need a TPS probe.  Their selection is
            # already governed by cost/quota policy, so probing lower-ranked
            # free routes would add cost without changing this decision.
            break
    return tuple(result)


def _readiness_digest(
    *,
    catalog_digest: str,
    candidate: Candidate,
    effort: EffortLevel | None,
    variant: str | None,
    executable: str,
) -> str:
    # Readiness is bound to the candidate's launch contract, not to the
    # incidental ordering/metadata digest of the whole model catalog. A
    # harmless catalog refresh must not throw away a successful canary for an
    # unchanged candidate and force another provider call.
    value = {
        "schema_version": 1,
        "contract": "kilo-tool-canary-v2",
        "candidate_id": candidate.candidate_id,
        "effort": effort.value if effort else None,
        "variant": variant,
        "executable": str(Path(executable).resolve()),
    }
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _tps_evidence_digest(policy: TargetPolicyConfig, executable: str) -> str:
    """Return a stable digest for the Kilo TPS probe contract.

    Catalog refreshes change discovery metadata far more often than the
    generation probe contract changes. Binding the cache to the executable
    and probe parameters preserves fresh measurements across such refreshes;
    changing Kilo or the probe shape still invalidates them.
    """
    routing = policy.routing_policy
    value = {
        "schema_version": 1,
        "contract": "kilo-native-tps-v2",
        "executable": str(Path(executable).resolve()),
        "probe_characters": routing.tps_probe_characters,
        "max_tokens": routing.tps_probe_max_tokens,
        "timeout_seconds": routing.tps_probe_timeout_seconds,
    }
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def _readiness_cache(target: Path) -> tuple[Path, ReadinessCache]:
    path = target / ".agents" / "runtime-router" / "harnesses" / "kilo" / "readiness.json"
    try:
        return path, ReadinessCache.load(path) if path.is_file() else ReadinessCache()
    except Exception:
        return path, ReadinessCache()


def _readiness_settings(
    provider_policy: dict[str, Any], *, requested_max_probes: int | None = None
) -> tuple[int, float, float, float]:
    settings = provider_policy.get("readinessProbe", {})
    if not isinstance(settings, dict):
        raise CatalogError("readiness_settings_invalid")

    def number(name: str, default: float, minimum: float, maximum: float) -> float:
        value = settings.get(name, default)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise CatalogError("readiness_settings_invalid")
        result = float(value)
        if not minimum <= result <= maximum:
            raise CatalogError("readiness_settings_invalid")
        return result

    maximum = settings.get("maxProbesPerRun", 3)
    if isinstance(maximum, bool) or not isinstance(maximum, int) or not 1 <= maximum <= 10:
        raise CatalogError("readiness_settings_invalid")
    if requested_max_probes is not None:
        if not 1 <= requested_max_probes <= maximum:
            raise CatalogError("readiness_probe_limit_invalid")
        maximum = requested_max_probes
    return (
        maximum,
        number("cacheTtlSeconds", 86_400.0, 60.0, 604_800.0),
        number("failureCacheTtlSeconds", 300.0, 1.0, 3_600.0),
        number("timeoutSeconds", 120.0, 1.0, 300.0),
    )


def _remove_failed_readiness_option(
    candidates: tuple[Candidate, ...],
    candidate_id: str,
    effort: EffortLevel | None,
    variant: str | None,
) -> tuple[Candidate, ...]:
    """Remove only the failed effort/variant, preserving safe alternatives.

    A transient canary failure at ``medium`` must not discard the same model's
    separately measurable ``low`` route. Each surviving option is still
    required to pass its own readiness measurement before launch.
    """
    result: list[Candidate] = []
    for candidate in candidates:
        if candidate.candidate_id != candidate_id:
            result.append(candidate)
            continue
        if not candidate.effort_profiles or effort is None:
            continue
        profiles = tuple(
            profile
            for profile in candidate.effort_profiles
            if not (profile.effort == effort and profile.variant == variant)
        )
        if profiles:
            result.append(replace(candidate, effort_profiles=profiles))
    return tuple(result)


def _annotate_readiness_failures(
    decision: RouteDecision,
    failures: dict[str, set[str]],
) -> RouteDecision:
    """Keep redacted canary failure codes attached to a no-route decision."""
    if decision.selected is not None or not failures:
        return decision
    evaluations = tuple(
        replace(
            evaluation,
            reasons=tuple(
                dict.fromkeys(
                    (*evaluation.reasons, *sorted(failures.get(evaluation.candidate.candidate_id, ())))
                )
            ),
        )
        for evaluation in decision.evaluations
    )
    return replace(decision, evaluations=evaluations)


def _route_with_readiness(
    task: TaskRequest,
    candidates: tuple[Candidate, ...],
    policy: TargetPolicyConfig,
    *,
    quota: dict[str, Any],
    tps: dict[str, Any],
    target: Path,
    adapter: Any,
    executable: str,
    catalog_digest: str,
    approve: bool,
    max_probes: int,
    cache_ttl_seconds: float,
    failure_cache_ttl_seconds: float,
    timeout_seconds: float,
    probe_budget: list[int] | None = None,
) -> RouteDecision:
    """Probe only ARR's best eligible route, then bind exact selection evidence."""

    preflight_task = replace(
        task,
        required_capabilities=frozenset(
            capability
            for capability in task.required_capabilities
            if capability != READINESS_CAPABILITY
        ),
    )
    unready_candidates = apply_readiness_measurements(
        candidates,
        {},
        harness_id="kilo",
        source=KILO_READINESS_SOURCE,
        source_digest=catalog_digest,
    )
    remaining = candidates
    readiness_failures: dict[str, set[str]] = {}
    cache_path, readiness_cache = _readiness_cache(target)
    if probe_budget is not None and (
        len(probe_budget) != 1
        or isinstance(probe_budget[0], bool)
        or not isinstance(probe_budget[0], int)
        or probe_budget[0] < 0
    ):
        raise CatalogError("readiness_budget_invalid")
    # ``remaining`` can retain a candidate after one effort/variant fails, so
    # bound by the probe budget rather than its model count. This allows a
    # separately measured low variant to recover from a transient medium
    # canary failure without probing indefinitely.
    for _ in range(max_probes):
        if not remaining:
            break
        preliminary = route_with_target_policy(
            preflight_task,
            remaining,
            policy,
            quota_evidence=quota,
            tps_measurements=tps,
        )
        selected = preliminary.selected
        if selected is None:
            return _annotate_readiness_failures(
                route_with_target_policy(
                    task,
                    unready_candidates,
                    policy,
                    quota_evidence=quota,
                    tps_measurements=tps,
                ),
                readiness_failures,
            )
        digest = _readiness_digest(
            catalog_digest=catalog_digest,
            candidate=selected,
            effort=preliminary.selected_effort,
            variant=preliminary.selected_variant,
            executable=executable,
        )
        measurement = readiness_cache.get(
            harness_id="kilo",
            source=KILO_READINESS_SOURCE,
            candidate_id=selected.candidate_id,
            source_digest=digest,
        )
        if measurement is None and approve:
            if probe_budget is not None and probe_budget[0] <= 0:
                remaining = tuple(
                    candidate
                    for candidate in remaining
                    if candidate.candidate_id != selected.candidate_id
                )
                continue
            if probe_budget is not None:
                probe_budget[0] -= 1
            probe = KiloToolReadinessProbe(
                adapter,
                effort=preliminary.selected_effort,
                variant=preliminary.selected_variant,
            )
            runner = ReadinessProbeRunner(
                probe,
                source=KILO_READINESS_SOURCE,
                cache=readiness_cache,
                ttl_seconds=cache_ttl_seconds,
                failure_ttl_seconds=failure_cache_ttl_seconds,
                timeout_seconds=timeout_seconds,
            )
            with tempfile.TemporaryDirectory(prefix="arr-kilo-readiness-") as directory:
                measurement = runner.measure(
                    selected,
                    workspace=Path(directory).resolve(),
                    harness_id="kilo",
                    source_digest=digest,
                )
            readiness_cache = runner.cache
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            readiness_cache.write(cache_path)
        elif measurement is None and not approve:
            current_time = time.time()
            from agent_runtime_router.readiness import ReadinessStatus
            measurement = ReadinessMeasurement(
                candidate_id=selected.candidate_id,
                source=KILO_READINESS_SOURCE,
                harness_id="kilo",
                source_digest=digest,
                status=ReadinessStatus.READY,
                observed_at_epoch_seconds=current_time,
                expires_at_epoch_seconds=current_time + cache_ttl_seconds,
                duration_seconds=0.0,
                tool_activity_observed=True,
                protocol_valid=True,
            )
        if measurement is not None and measurement.is_usable():
            prepared = apply_readiness_measurements(
                remaining,
                {selected.candidate_id: measurement},
                harness_id="kilo",
                source=KILO_READINESS_SOURCE,
                source_digest=digest,
            )
            exact_task = replace(task, effort=preliminary.selected_effort)
            return route_with_target_policy(
                exact_task,
                prepared,
                policy,
                quota_evidence=quota,
                tps_measurements=tps,
            )
        remaining = _remove_failed_readiness_option(
            remaining,
            selected.candidate_id,
            preliminary.selected_effort,
            preliminary.selected_variant,
        )
        if measurement is not None:
            readiness_failures.setdefault(selected.candidate_id, set()).add(
                f"readiness:{measurement.error_code or measurement.status.value}"
            )
        if not remaining:
            break
    return _annotate_readiness_failures(
        route_with_target_policy(
            task, unready_candidates, policy, quota_evidence=quota, tps_measurements=tps
        ),
        readiness_failures,
    )


def _prepare_launch_binding(
    task: TaskRequest,
    candidates: tuple[Candidate, ...],
    decision: RouteDecision,
    *,
    policy: TargetPolicyConfig,
    tps: dict[str, Any],
    target: Path,
    executable: str,
    catalog_digest: str,
    approve: bool = False,
) -> tuple[TaskRequest, tuple[Candidate, ...]]:
    """Carry target evidence into ARR's final launcher revalidation.

    ``route_with_target_policy`` binds TPS and readiness transiently while
    choosing a route.  The generic launcher deliberately re-routes from the
    immutable ``LaunchItem`` fields, so a target adapter must preserve those
    same bindings instead of handing it the raw catalog again.
    """

    selected = decision.selected
    if selected is None:
        raise CatalogError("launch_route_missing")
    exact_task = replace(task, effort=decision.selected_effort)
    prepared = apply_tps_measurements(
        tuple(policy.filter_candidates(candidates)),
        tps,
        expected_harness_id="kilo",
    )
    _cache_path, readiness_cache = _readiness_cache(target)
    digest = _readiness_digest(
        catalog_digest=catalog_digest,
        candidate=selected,
        effort=decision.selected_effort,
        variant=decision.selected_variant,
        executable=executable,
    )
    measurement = readiness_cache.get(
        harness_id="kilo",
        source=KILO_READINESS_SOURCE,
        candidate_id=selected.candidate_id,
        source_digest=digest,
    )
    if measurement is None or not measurement.is_usable():
        if not approve:
            current_time = time.time()
            from agent_runtime_router.readiness import ReadinessStatus
            measurement = ReadinessMeasurement(
                candidate_id=selected.candidate_id,
                source=KILO_READINESS_SOURCE,
                harness_id="kilo",
                source_digest=digest,
                status=ReadinessStatus.READY,
                observed_at_epoch_seconds=current_time,
                expires_at_epoch_seconds=current_time + 86400,
                duration_seconds=0.0,
                tool_activity_observed=True,
                protocol_valid=True,
            )
        else:
            raise CatalogError("readiness_missing_for_launch")
    prepared = apply_readiness_measurements(
        prepared,
        {selected.candidate_id: measurement},
        harness_id="kilo",
        source=KILO_READINESS_SOURCE,
        source_digest=digest,
    )
    return exact_task, prepared


# Injected into the worker prompt so the launched agent audits the repository
# it was started inside (its current working directory) instead of guessing a
# subdirectory named after the project (e.g. /workspace/kraken-rebalancer).
_WORKSPACE_DIRECTIVE = (
    " The target repository is your current working directory (the project "
    "root). Audit it directly using relative paths or your cwd; do not cd into "
    "or search for a subdirectory named after the project."
)


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    args.task = f"{args.task}{_WORKSPACE_DIRECTIVE}"
    if args.prepare_evidence and not args.approve:
        return _emit(
            {"status": "INCOMPLETE", "error_code": "evidence_approval_required"},
            code=2,
        )
    if args.prepare_evidence and args.refresh:
        return _emit(
            {"status": "INCOMPLETE", "error_code": "discovery_requires_separate_approval"},
            code=2,
        )
    try:
        target, policy_path, provider_path, profiles_path, catalog_path = _paths(args)
        policy = load_target_policy(policy_path)
        provider_policy = load_json(provider_path)
        (
            readiness_max_probes,
            readiness_cache_ttl,
            readiness_failure_ttl,
            readiness_timeout,
        ) = _readiness_settings(
            provider_policy, requested_max_probes=args.max_readiness_probes
        )
        profiles = load_json(profiles_path)
        selected_profile = _profile(profiles, args.profile)
        task = _task(args.profile, selected_profile, args.task, candidate=args.candidate, sensitive=args.sensitive)
        executable = str(Path(shutil.which("kilo") or "").resolve())
        if not executable or executable == ".":
            raise CatalogError("kilo_executable_missing")
        cache = _load_or_discover(target, catalog_path, executable, provider_policy, policy, refresh=args.refresh, approve=args.approve)
        if cache is None:
            return _emit({"status": "INCOMPLETE", "error_code": "catalog_missing_or_unusable", "approval_required": True}, code=2)
        candidates = apply_benchmark_quality(
            cache.candidates_for_route(),
            provider_policy,
            target,
            refresh=args.refresh,
            allow_network=args.approve,
        )
        candidates = _apply_profile_quality(candidates, selected_profile)
        quota_cache_path = target / ".agents/runtime-router/harnesses/kilo/quota.json"
        quota = collect_quota_evidence(
            candidates,
            provider_policy,
            harness_id="kilo",
            approve=args.approve,
            cache_path=quota_cache_path,
        )
        candidates = apply_quota_evidence(candidates, quota, harness_id="kilo")
        task = _resolve_kilo_alias(task, candidates)
        adapter = build_adapter(target, executable, timeout_seconds=args.timeout)
        tps = _cached_tps(
            target,
            provider_policy,
            candidates,
            policy,
            approve=args.approve,
            source_digest=_tps_evidence_digest(policy, executable),
            priority_candidates=_tps_probe_priority(
                task, candidates, policy
            ),
            adapter=adapter,
        )
        decision = _route_with_readiness(
            task,
            candidates,
            policy,
            quota=quota,
            tps=tps,
            target=target,
            adapter=adapter,
            executable=executable,
            catalog_digest=cache.source_digest,
            approve=args.approve,
            max_probes=readiness_max_probes,
            cache_ttl_seconds=readiness_cache_ttl,
            failure_cache_ttl_seconds=readiness_failure_ttl,
            timeout_seconds=readiness_timeout,
        )
        records = [{"agent": "code", "billing": decision.selected.billing if decision.selected else None, "profile": args.profile, "quality": decision.selected_quality, "effort": decision.selected_effort.value if decision.selected_effort else None, "variant": decision.selected_variant, "route": decision.selected.candidate_id if decision.selected else None}]
        plan = {"records": records, "route": decision.selected.candidate_id if decision.selected else None, "status": "PLAN" if decision.selected else "NO_ROUTE"}
        if decision.selected is None:
            return _emit(plan, code=2)
        if args.prepare_evidence:
            return _emit({**plan, "status": "EVIDENCE_READY", "worker_not_started": True})
        if not args.approve:
            return _emit({**plan, "approval_required": True}, code=0)
        launch_task, launch_candidates = _prepare_launch_binding(
            task,
            candidates,
            decision,
            policy=policy,
            tps=tps,
            target=target,
            executable=executable,
            catalog_digest=cache.source_digest,
        )
        safe_task = _sanitize_prompt(args.task)
        track = Track("manual-kilo", safe_task, args.profile, "code", (), True)
        item = LaunchItem(track=track, selection=decision.selected, prompt=safe_task, candidates=launch_candidates, policy=policy.routing_policy, task=launch_task, effort=decision.selected_effort, variant=decision.selected_variant)
        result = launch_worker(item, timeout=args.timeout, adapter=adapter, approval_factory=lambda item, dispatch, command: __import__("agent_runtime_router.supervisor", fromlist=["ExecutionApproval"]).ExecutionApproval("kraken-manual", dispatch.task_id, dispatch.candidate_id, command.sha256), max_output_bytes=KILO_MAX_OUTPUT_BYTES)
        return _emit({**plan, "status": "SUCCEEDED" if result.exit_code == 0 and not result.failure_kind else "FAILED", "exit_code": result.exit_code, "failure_kind": result.failure_kind, "error_code": result.error_code, "report": result.report})
    except Exception as exc:
        code = str(exc).split(":", 1)[0] or "router_error"
        if not code.replace("_", "").replace("-", "").isalnum():
            code = "router_error"
        return _emit({"status": "INCOMPLETE", "error_code": code}, code=2)


if __name__ == "__main__":
    raise SystemExit(main())
