"""ARR bridge: thin, harness-local translation to agent-runtime-router.

Responsibilities retained in Kraken (adapter):
- catalog/AA/quota discovery (router.build_candidates etc.)
- source-data translation (Candidate/profile/config -> ARR contracts)
- dynamic TPS probing (side-effectful, outside ARR)
- Kilo launch and presentation wording (router.report, variant)

ARR owns:
- portable eligibility, ranking, fallback, structured rejection reasons
- unknown-evidence policies and tie-breaks

Design constraints (PR #241 fixes):
- Call ARR exactly once per routing decision and retain its RouteDecision.
- Convert ARR reasons to Kraken wording only after the decision.
- Catch integration exceptions only at the adapter boundary; surface them as
  fail-closed ARRIntegrationError values with chained causes.
- No local fallback ranking or duplicate eligibility engine.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Sequence

from router_errors import ARRIntegrationError

ARR_REQUIRED_VERSION = "1.4.0"

try:
    from agent_runtime_router import __version__ as ARR_VERSION
    from agent_runtime_router import Candidate as ARRCandidate
    from agent_runtime_router import RoutingPolicy as ARRPolicy
    from agent_runtime_router import TaskRequest as ARRTask
    from agent_runtime_router.contracts import Availability as ARRAvail
    from agent_runtime_router.contracts import CostClass as ARRCost
    from agent_runtime_router.contracts import QuotaStatus as ARRQuota
    from agent_runtime_router import route as arr_route
    from agent_runtime_router.errors import RouterInputError

    if ARR_VERSION != ARR_REQUIRED_VERSION:
        raise ImportError(
            f"agent-runtime-router {ARR_REQUIRED_VERSION} required, found {ARR_VERSION}"
        )
    ARR_AVAILABLE = True
except ImportError as e:
    ARR_AVAILABLE = False
    _ARR_IMPORT_ERROR = e


@dataclass(frozen=True)
class ARRSelection:
    """One ARR decision plus the Kraken candidates used to make it."""

    candidate: Any | None
    decision: Any | None
    usable: tuple[Any, ...]


# Rejection translation: ARR reason -> Kraken wording for report / error detail
def _arr_reason_to_kraken(reason: str, candidate: Any, profile: Mapping[str, Any]) -> str:
    if reason == "cost:free_disallowed":
        return "free routes disabled by policy"
    if reason == "cost:free_blocked_for_sensitive":
        return "free routes disabled for sensitive work"
    if reason == "cost:paid_disallowed":
        return "paid routes disabled by policy"
    if reason == "cost:unknown_disallowed":
        return "paid routes disabled by policy"
    if reason == "quality:unknown":
        return "capability quality is unknown and cannot be assessed"
    if reason.startswith("quality:below_minimum:"):
        try:
            payload = reason.split(":", 2)[2]
            q_str, m_str = payload.split("<", 1)
            return f"quality score {float(q_str):g} is below {float(m_str):g}"
        except Exception:
            pass
        return reason
    if reason.startswith("quality:secondary_below:"):
        # quality:secondary_below:metric:val<thresh  -> "metric is below thresh"
        try:
            parts = reason.split(":", 3)
            # parts[2]=metric, parts[3]="val<thresh"
            metric = parts[2]
            val_thresh = parts[3]
            _val, thresh = val_thresh.split("<", 1)
            return f"{metric} is below {thresh}"
        except Exception:
            return reason
    if reason == "capability:tool_call_unavailable":
        return "tool calling is not advertised"
    if reason == "capability:reasoning_required":
        return "reasoning support is not advertised"
    if reason in ("context:insufficient", "context:unknown_disallowed"):
        return "context window is too small"
    if reason.startswith("availability:"):
        return "catalog status is not active"
    if reason.startswith("quota:"):
        # preserve Kraken phrasing
        qs = getattr(candidate, "quota_state", "unknown")
        if qs in {"insufficient", "unavailable", "blocked", "exhausted"}:
            # normalize exhausted -> insufficient for Kraken wording
            if qs == "exhausted":
                qs = "insufficient"
            return f"quota state is {qs}"
        if reason == "quota:exhausted":
            return "quota state is insufficient"
        if reason == "quota:blocked":
            return "quota state is blocked"
        return f"quota state is {qs}"
    if reason.startswith("capability:missing:"):
        cap = reason.split(":", 2)[2]
        if cap == "tool_call":
            return "tool calling is not advertised"
        if cap == "reasoning":
            return "reasoning support is not advertised"
        return f"capability {cap} is not advertised"
    return reason


def _kraken_to_arr_candidate(k: Any) -> Any:
    # Translate Kraken Candidate -> ARR Candidate with accurate unknown evidence
    provider = str(k.provider)
    model = str(k.model)
    caps: set[str] = {"code", "completion"}
    if bool(getattr(k, "tool_call", False)):
        caps.add("tool_call")
    if bool(getattr(k, "reasoning", False)):
        caps.add("reasoning")
    if bool(getattr(k, "attachment", False)):
        caps.add("attachment")
    if bool(getattr(k, "pdf", False)):
        caps.add("pdf")

    status = str(getattr(k, "status", "active"))
    if status == "active":
        avail = ARRAvail.AVAILABLE
    elif status == "unknown":
        avail = ARRAvail.UNKNOWN
    else:
        avail = ARRAvail.UNAVAILABLE

    billing_raw = str(getattr(k, "billing", "paid"))
    if billing_raw == "free":
        cost = ARRCost.FREE
    elif billing_raw in {"paid", "subscription", "subscription/account-priced", "account-priced"}:
        cost = ARRCost.PAID
    else:
        cost = ARRCost.UNKNOWN

    qs = str(getattr(k, "quota_state", "unknown"))
    if qs in {"sufficient", "available"}:
        quota = ARRQuota.AVAILABLE
    elif qs == "blocked":
        quota = ARRQuota.BLOCKED
    elif qs in {"insufficient", "exhausted", "unavailable"}:
        quota = ARRQuota.EXHAUSTED
    else:  # unknown -> preserve ARR UNKNOWN for tie-break
        quota = ARRQuota.UNKNOWN

    # Context: 0, None, or invalid -> UNKNOWN (None)
    ctx_raw = getattr(k, "context_limit", None)
    ctx_int: int | None
    try:
        ctx_val = int(ctx_raw) if ctx_raw is not None else None
        if ctx_val is not None and ctx_val <= 0:
            ctx_int = None
        else:
            ctx_int = ctx_val
    except Exception:
        ctx_int = None

    quality = getattr(k, "quality", None)
    try:
        quality_f = float(quality) if quality is not None else None
    except Exception:
        quality_f = None

    eff = getattr(k, "effective_cost", None)
    if eff is None:
        eff = getattr(k, "aa_cost_per_task", None)
    try:
        eff_f = float(eff) if eff is not None else None
    except Exception:
        eff_f = None

    qp = getattr(k, "quota_percent", None)
    try:
        qp_f = float(qp) if qp is not None else None
    except Exception:
        qp_f = None

    variants_raw = getattr(k, "variants", {}) or {}
    if isinstance(variants_raw, dict):
        variants = tuple(str(v) for v in variants_raw.keys())
    elif isinstance(variants_raw, (list, tuple)):
        variants = tuple(str(v) for v in variants_raw)
    else:
        variants = ()
    pref = getattr(k, "preferred_variant", None)

    # Named secondary evidences: AA evaluations -> quality_metrics
    qm = None
    aa = getattr(k, "aa", None)
    if isinstance(aa, Mapping):
        ev = aa.get("evaluations")
        if isinstance(ev, Mapping):
            metrics: dict[str, float] = {}
            for mk, mv in ev.items():
                try:
                    if isinstance(mv, (int, float)) and not isinstance(mv, bool):
                        import math
                        if math.isfinite(float(mv)):
                            metrics[str(mk)] = float(mv)
                except Exception:
                    continue
            if metrics:
                qm = metrics

    return ARRCandidate(
        provider=provider,
        model=model,
        capabilities=frozenset(caps),
        availability=avail,
        cost_class=cost,
        quota_status=quota,
        context_window=ctx_int,
        quality=quality_f,
        effective_cost=eff_f,
        quota_percent=qp_f,
        billing=billing_raw if billing_raw in {"free","paid","subscription","subscription/account-priced","account-priced","unknown"} else None,
        tool_call=bool(getattr(k, "tool_call", False)) if hasattr(k, "tool_call") else None,
        reasoning=bool(getattr(k, "reasoning", False)) if hasattr(k, "reasoning") else None,
        variants=variants,
        preferred_variant=str(pref) if pref else None,
        tps=getattr(k, "tps", None),
        quality_metrics=qm,
    )


def _profile_to_arr_task(task_text: str, profile: Mapping[str, Any], sensitive: bool) -> Any:
    def _num(v: Any) -> float | None:
        try:
            return float(v) if v is not None else None
        except Exception:
            return None
    minimum = _num(profile.get("minimum")) or 0.0
    margin = _num(profile.get("margin")) or 0.0
    qmin = minimum + margin if (minimum or margin) else (minimum if minimum else None)
    if profile.get("minimum") is None and not profile.get("margin"):
        qmin = None
    requires_reasoning = bool(profile.get("requiresReasoning", False))
    context = profile.get("context")
    try:
        ctx_needed = int(context) if context is not None else 0
        if ctx_needed <= 0:
            ctx_needed = 0
    except Exception:
        ctx_needed = 0
    # Secondary thresholds
    secondary_raw = profile.get("secondary")
    secondary = None
    if isinstance(secondary_raw, Mapping):
        sec: dict[str, float] = {}
        for mk, thresh in secondary_raw.items():
            try:
                if isinstance(thresh, (int, float)) and not isinstance(thresh, bool):
                    import math
                    if math.isfinite(float(thresh)):
                        sec[str(mk)] = float(thresh)
            except Exception:
                continue
        if sec:
            secondary = sec

    return ARRTask(
        task_id="kraken-task",
        required_capabilities=frozenset({"code"}),
        min_context_window=ctx_needed,
        pinned_provider=None,
        pinned_model=None,
        quality_minimum=float(qmin) if qmin is not None else None,
        requires_reasoning=requires_reasoning,
        sensitive=bool(sensitive),
        min_tps=None,
        secondary_thresholds=secondary,
    )


def _config_to_arr_policy(config: Mapping[str, Any], profile: Mapping[str, Any], candidates: Sequence[Any]) -> Any:
    policy_cfg = config.get("policy", {}) if isinstance(config.get("policy"), Mapping) else {}
    allow_paid = bool(policy_cfg.get("allowPaid", True))
    allow_free = bool(policy_cfg.get("allowFree", True))
    deny_free = bool(policy_cfg.get("denyFreeForSensitive", True))

    # Free allowlist: provider/model IDs where allowFree is true (per-provider)
    providers = config.get("providers", {}) if isinstance(config.get("providers"), Mapping) else {}
    allowlist: list[str] = []
    for prov_id, prov_cfg in providers.items():
        if (
            isinstance(prov_cfg, Mapping)
            and bool(prov_cfg.get("enabled", True))
            and bool(prov_cfg.get("allowFree", False))
        ):
            allowlist.append(str(prov_id))
    # Also include any candidate that has free_allowed=True (per-candidate)
    for c in candidates:
        if bool(getattr(c, "free_allowed", False)):
            # Allowlist by provider is already covered, but also add candidate route
            route = str(getattr(c, "route", ""))
            if route and route not in allowlist and getattr(c, "provider", "") not in allowlist:
                # Prefer provider-level allowlist; candidate route allowlist is more specific
                allowlist.append(route)

    vp = profile.get("variantPreference", [])
    if not isinstance(vp, (list, tuple)):
        vp = []
    variant_pref = tuple(str(v) for v in vp if isinstance(v, str) and v)

    # Unknown status/quota/context were eligible in Kraken and remain explicit
    # ARR opt-ins. Unknown billing was historically treated as paid, so it is
    # allowed only when the legacy paid policy is enabled.
    return ARRPolicy(
        allow_paid=allow_paid,
        allow_unknown_cost=allow_paid,
        allow_unknown_availability=True,  # unknown status was eligible (legacy)
        allow_unknown_quota=True,  # unknown quota eligible but deprioritized (ARR tie-break)
        allow_unknown_context_window=True,  # 0 -> None eligible (legacy)
        preferred_candidates=(),
        preferred_providers=(),
        denied_candidates=(),
        denied_providers=(),
        allow_free=allow_free,
        deny_free_for_sensitive=deny_free,
        variant_preference=variant_pref,
        free_allowlist=tuple(allowlist),
        allow_quality_fallback=True,  # Kraken fallback: below-minimum highest quality
    )


def _usable_candidates(
    candidates: Sequence[Any],
    excluded_routes: set[str] | None = None,
    excluded_providers: set[str] | None = None,
) -> tuple[Any, ...]:
    excluded_routes = excluded_routes or set()
    excluded_providers = excluded_providers or set()
    return tuple(
        c
        for c in candidates
        if c.route not in excluded_routes and c.provider not in excluded_providers
    )


def arr_route_decision(
    candidates: Sequence[Any],
    profile: Mapping[str, Any],
    config: Mapping[str, Any],
    sensitive: bool,
    excluded_routes: set[str] | None = None,
    excluded_providers: set[str] | None = None,
) -> ARRSelection:
    """Translate inputs and invoke ARR exactly once for this decision."""
    if not ARR_AVAILABLE:
        raise ARRIntegrationError(
            "agent-runtime-router is not installed or has the wrong version; "
            "run .kilo/model-router/setup.sh"
        ) from _ARR_IMPORT_ERROR

    try:
        usable = _usable_candidates(candidates, excluded_routes, excluded_providers)
    except Exception as e:
        raise ARRIntegrationError(f"candidate filtering failed: {e}") from e
    if not usable:
        return ARRSelection(candidate=None, decision=None, usable=())

    try:
        arr_cands = tuple(_kraken_to_arr_candidate(c) for c in usable)
        task = _profile_to_arr_task("kraken", profile, sensitive)
        policy = _config_to_arr_policy(config, profile, usable)
    except Exception as e:
        raise ARRIntegrationError(f"ARR translation failed: {e}") from e

    try:
        decision = arr_route(task, arr_cands, policy)
    except RouterInputError as e:
        raise ARRIntegrationError(f"ARR routing rejected translated input: {e}") from e
    except Exception as e:
        raise ARRIntegrationError(f"ARR routing failed: {e}") from e

    if decision.selected is None:
        return ARRSelection(candidate=None, decision=decision, usable=usable)

    try:
        selected_id = decision.selected.candidate_id
    except Exception as e:
        raise ARRIntegrationError("ARR returned a malformed selected candidate") from e
    for kc in usable:
        if kc.route == selected_id:
            # Keep the user-visible candidate compatible with the existing
            # launcher while retaining the decision's fallback evidence.
            kc.fallback_used = bool(getattr(decision, "fallback_used", False))
            return ARRSelection(candidate=kc, decision=decision, usable=usable)

    raise ARRIntegrationError(
        f"ARR selected unknown candidate {selected_id!r}; refusing to continue"
    )


def arr_select_candidate(
    candidates: Sequence[Any],
    profile: Mapping[str, Any],
    config: Mapping[str, Any],
    sensitive: bool,
    excluded_routes: set[str] | None = None,
    excluded_providers: set[str] | None = None,
) -> ARRSelection:
    """Return the single ARR decision used by the caller.

    No local ranking or eligibility fallback is performed here. A no-route
    decision is represented by ``selection.candidate is None``; integration
    failures raise ``ARRIntegrationError`` and must not be treated as no-route.
    """
    return arr_route_decision(
        candidates,
        profile,
        config,
        sensitive,
        excluded_routes,
        excluded_providers,
    )


def arr_rejection_summary(
    decision: Any,
    candidates: Sequence[Any],
    profile: Mapping[str, Any],
) -> dict[str, int]:
    """Translate one already-computed ARR decision to Kraken wording."""
    if decision is None:
        return {}
    id_to_kraken = {c.route: c for c in candidates}
    reasons: dict[str, int] = {}
    for ev in decision.evaluations:
        if ev.eligible:
            continue
        kc = id_to_kraken.get(ev.candidate.candidate_id)
        if kc is None:
            continue
        for r in ev.reasons:
            kraken_msg = _arr_reason_to_kraken(r, kc, profile)
            reasons[kraken_msg] = reasons.get(kraken_msg, 0) + 1

    return reasons
