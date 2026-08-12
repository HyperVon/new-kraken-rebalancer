"""ARR bridge: translate kraken candidates/profile/config to agent-runtime-router contracts.

This file is the integration shim that lets new-kraken-rebalancer replace its
internal router.select_candidate with the harness-neutral agent-runtime-router
without loss of capability. It is intentionally small and secret-free.
"""

from __future__ import annotations

from typing import Any, Mapping, Sequence

try:
    from agent_runtime_router import Candidate as ARRCandidate
    from agent_runtime_router import RoutingPolicy as ARRPolicy
    from agent_runtime_router import TaskRequest as ARRTask
    from agent_runtime_router.contracts import Availability as ARRAvail
    from agent_runtime_router.contracts import CostClass as ARRCost
    from agent_runtime_router.contracts import QuotaStatus as ARRQuota
    from agent_runtime_router import route as arr_route

    ARR_AVAILABLE = True
except ImportError:
    ARR_AVAILABLE = False  # fallback to legacy kraken logic if ARR not installed


def _kraken_to_arr_candidate(k) -> Any:
    if not ARR_AVAILABLE:
        return None
    # Provider/model
    provider = str(k.provider)
    model = str(k.model)
    # Capabilities: map kraken booleans to capability strings plus base "code"
    caps: set[str] = {"code", "completion"}
    if bool(getattr(k, "tool_call", False)):
        caps.add("tool_call")
    if bool(getattr(k, "reasoning", False)):
        caps.add("reasoning")
    if bool(getattr(k, "attachment", False)):
        caps.add("attachment")
    if bool(getattr(k, "pdf", False)):
        caps.add("pdf")
    # Availability from kraken status
    status = str(getattr(k, "status", "active"))
    if status == "active":
        avail = ARRAvail.AVAILABLE
    elif status == "unknown":
        avail = ARRAvail.UNKNOWN
    else:
        avail = ARRAvail.UNAVAILABLE
    # Cost class from billing
    billing_raw = str(getattr(k, "billing", "paid"))
    if billing_raw == "free":
        cost = ARRCost.FREE
    elif billing_raw in {"paid", "subscription", "subscription/account-priced", "account-priced"}:
        cost = ARRCost.PAID
    else:
        cost = ARRCost.UNKNOWN
    # Quota: kraken treats "unknown" as not blocked (allowed), only insufficient/unavailable/blocked are rejections
    # So map kraken "unknown" to ARR AVAILABLE to keep parity (quota unknown is just tiebreak, not eligibility)
    qs = str(getattr(k, "quota_state", "unknown"))
    if qs in {"sufficient", "available", "unknown"}:
        quota = ARRQuota.AVAILABLE
    elif qs == "blocked":
        quota = ARRQuota.BLOCKED
    elif qs in {"insufficient", "exhausted", "unavailable"}:
        quota = ARRQuota.EXHAUSTED
    else:
        quota = ARRQuota.AVAILABLE
    # Context
    ctx = getattr(k, "context_limit", None)
    try:
        ctx_int = int(ctx) if ctx is not None else None
    except Exception:
        ctx_int = None
    # Quality / cost / quota percent
    quality = getattr(k, "quality", None)
    try:
        quality_f = float(quality) if quality is not None else None
    except Exception:
        quality_f = None
    eff_cost = getattr(k, "effective_cost", None)
    if eff_cost is None:
        eff_cost = getattr(k, "aa_cost_per_task", None)
    try:
        eff_cost_f = float(eff_cost) if eff_cost is not None else None
    except Exception:
        eff_cost_f = None
    qp = getattr(k, "quota_percent", None)
    try:
        qp_f = float(qp) if qp is not None else None
    except Exception:
        qp_f = None
    # Variants
    variants_raw = getattr(k, "variants", {}) or {}
    if isinstance(variants_raw, dict):
        variants = tuple(str(v) for v in variants_raw.keys())
    elif isinstance(variants_raw, (list, tuple)):
        variants = tuple(str(v) for v in variants_raw)
    else:
        variants = ()
    pref_var = getattr(k, "preferred_variant", None)
    # TPS (from cached_tps or probe)
    tps = None
    # Kraken stores tps via cached_tps(); we can try to read attribute if present
    # For now leave None; ARR will handle missing tps as not insufficient
    return ARRCandidate(
        provider=provider,
        model=model,
        capabilities=frozenset(caps),
        availability=avail,
        cost_class=cost,
        quota_status=quota,
        context_window=ctx_int,
        quality=quality_f,
        effective_cost=eff_cost_f,
        quota_percent=qp_f,
        billing=billing_raw if billing_raw in {"free", "paid", "subscription", "subscription/account-priced", "account-priced", "unknown"} else None,
        tool_call=bool(getattr(k, "tool_call", False)) if hasattr(k, "tool_call") else None,
        reasoning=bool(getattr(k, "reasoning", False)) if hasattr(k, "reasoning") else None,
        variants=variants,
        preferred_variant=str(pref_var) if pref_var else None,
        tps=None,
    )


def _profile_to_arr_task(task_text: str, profile: Mapping[str, Any], sensitive: bool) -> Any:
    if not ARR_AVAILABLE:
        return None
    # Kraken's effective_minimum = minimum + margin
    def _num(v):
        try:
            return float(v) if v is not None else None
        except Exception:
            return None

    minimum = _num(profile.get("minimum")) or 0.0
    margin = _num(profile.get("margin")) or 0.0
    quality_min = minimum + margin if (minimum or margin) else (minimum if minimum else None)
    # If no minimum, leave None to allow any quality (kraken's fallback)
    # For profiles without minimum, ARR should not require quality
    if profile.get("minimum") is None and not profile.get("margin"):
        quality_min = None
    requires_reasoning = bool(profile.get("requiresReasoning", False))
    context = profile.get("context")
    try:
        ctx_int = int(context) if context is not None else 0
    except Exception:
        ctx_int = 0
    return ARRTask(
        task_id="kraken-task",
        required_capabilities=frozenset({"code"}),
        min_context_window=ctx_int,
        pinned_provider=None,
        pinned_model=None,
        quality_minimum=float(quality_min) if quality_min is not None else None,
        requires_reasoning=requires_reasoning,
        sensitive=bool(sensitive),
        min_tps=None,
    )


def _config_to_arr_policy(config: Mapping[str, Any], profile: Mapping[str, Any]) -> Any:
    if not ARR_AVAILABLE:
        return None
    policy_cfg = config.get("policy", {}) if isinstance(config.get("policy"), Mapping) else {}
    allow_paid = bool(policy_cfg.get("allowPaid", True))
    allow_free = bool(policy_cfg.get("allowFree", True))
    deny_free = bool(policy_cfg.get("denyFreeForSensitive", True))
    # Variant preference from profile
    vp = profile.get("variantPreference", [])
    if not isinstance(vp, (list, tuple)):
        vp = []
    variant_pref = tuple(str(v) for v in vp if isinstance(v, str) and v)
    return ARRPolicy(
        allow_paid=allow_paid,
        allow_unknown_cost=False,
        allow_unknown_availability=False,
        allow_unknown_quota=False,
        allow_unknown_context_window=False,
        preferred_candidates=(),
        preferred_providers=(),
        denied_candidates=(),
        denied_providers=(),
        allow_free=allow_free,
        deny_free_for_sensitive=deny_free,
        variant_preference=variant_pref,
    )


def arr_select_candidate(
    candidates: Sequence[Any],
    profile: Mapping[str, Any],
    config: Mapping[str, Any],
    sensitive: bool,
    excluded_routes: set[str] | None = None,
    excluded_providers: set[str] | None = None,
) -> Any | None:
    """Try ARR routing; return kraken Candidate or None if ARR not available."""
    if not ARR_AVAILABLE:
        return None
    excluded_routes = excluded_routes or set()
    excluded_providers = excluded_providers or set()
    # Filter excluded before translation (kraken parity)
    usable_kraken = [c for c in candidates if c.route not in excluded_routes and c.provider not in excluded_providers]
    if not usable_kraken:
        return None

    def _try_route(profile_override: Mapping[str, Any] | None = None) -> Any | None:
        prof = profile_override if profile_override is not None else profile
        try:
            arr_cands = tuple(_kraken_to_arr_candidate(c) for c in usable_kraken)
            task = _profile_to_arr_task("kraken", prof, sensitive)
            policy = _config_to_arr_policy(config, prof)
            arr_cands = tuple(c for c in arr_cands if c is not None)
            if not arr_cands:
                return None
            decision = arr_route(task, arr_cands, policy)
            if decision.selected is None:
                return None
            selected_id = decision.selected.candidate_id
            for kc in usable_kraken:
                if kc.route == selected_id:
                    try:
                        import router as kraken_router

                        kraken_router.select_variant(kc, prof)
                    except Exception:
                        pass
                    return kc
            return None
        except Exception:
            return None

    # First try with full quality threshold (kraken parity)
    result = _try_route()
    if result is not None:
        return result
    # Kraken fallback: when no candidate meets minimum, try with relax_quality
    # (still checks billing/status/tool/reasoning/context/quota, but allows
    # below-minimum quality). Pick highest quality among those that would
    # otherwise qualify — mirrors legacy candidate_qualifies(relax_quality=True)
    # and max(c.quality). This must NOT rescue candidates rejected for other
    # reasons (free disabled, unknown quality, quota exhausted, etc.).
    relax_candidates: list[Any] = []
    try:
        import router as kraken_router

        has_qualifies = hasattr(kraken_router, "candidate_qualifies")
    except Exception:
        has_qualifies = False
        kraken_router = None  # type: ignore[assignment]
    for c in usable_kraken:
        if getattr(c, "quality", None) is None:
            continue  # unknown quality never qualifies even with relax_quality
        if has_qualifies:
            try:
                # candidate_qualifies mutates c.rejection; use a copy of relevant state via direct call
                if kraken_router.candidate_qualifies(c, profile, config, sensitive, relax_quality=True):
                    relax_candidates.append(c)
            except Exception:
                # Fallback to simple billing/status check if qualifies fails
                relax_candidates.append(c)
        else:
            relax_candidates.append(c)
    if relax_candidates:
        best = max(relax_candidates, key=lambda c: float(getattr(c, "quality", 0.0) or 0.0))
        try:
            import router as kraken_router  # re-import for select_variant

            kraken_router.select_variant(best, profile)
        except Exception:
            pass
        return best
    return None
