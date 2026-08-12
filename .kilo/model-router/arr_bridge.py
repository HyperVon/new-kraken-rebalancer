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


# ---------------------------------------------------------------------------
# Legacy-free helpers (no import of router.candidate_qualifies)
# ---------------------------------------------------------------------------

def _effective_minimum(profile: Mapping[str, Any]) -> float:
    def _num(v):
        try:
            return float(v) if v is not None else None
        except Exception:
            return None
    minimum = _num(profile.get("minimum")) or 0.0
    margin = _num(profile.get("margin")) or 0.0
    return minimum + margin


def _kraken_non_quality_eligible(candidate: Any, profile: Mapping[str, Any], config: Mapping[str, Any], sensitive: bool) -> tuple[bool, str | None]:
    """Check if kraken candidate passes all gates except quality threshold.

    Returns (eligible, rejection_reason). Mirrors legacy candidate_qualifies
    with relax_quality=True but without importing router.
    """
    policy = config.get("policy", {}) if isinstance(config.get("policy"), Mapping) else {}
    # quota
    quota_state = str(getattr(candidate, "quota_state", "unknown"))
    if quota_state in {"insufficient", "unavailable", "blocked"}:
        return False, f"quota state is {quota_state}"
    # status
    status = str(getattr(candidate, "status", "active"))
    if status not in {"active", "unknown"}:
        return False, "catalog status is not active"
    # tool_call
    if not bool(getattr(candidate, "tool_call", True)):
        return False, "tool calling is not advertised"
    # reasoning
    if bool(profile.get("requiresReasoning")) and not bool(getattr(candidate, "reasoning", False)):
        return False, "reasoning support is not advertised"
    # context
    try:
        ctx_needed = int(profile.get("context", 0) or 0)
    except Exception:
        ctx_needed = 0
    ctx_limit = getattr(candidate, "context_limit", None)
    if ctx_limit is not None and ctx_needed and int(ctx_limit) < ctx_needed:
        return False, "context window is too small"
    # billing
    billing = str(getattr(candidate, "billing", "paid"))
    free_allowed = bool(getattr(candidate, "free_allowed", False))
    if billing == "free" and not (bool(policy.get("allowFree", False)) or free_allowed):
        return False, "free routes disabled by policy"
    if billing == "free" and sensitive and bool(policy.get("denyFreeForSensitive", True)):
        return False, "free routes disabled for sensitive work"
    if billing != "free" and not bool(policy.get("allowPaid", True)):
        return False, "paid routes disabled by policy"
    # quality unknown is still a rejection even when relaxed
    if getattr(candidate, "quality", None) is None:
        return False, "capability quality is unknown and cannot be assessed"
    # secondary checks are skipped when relax_quality=True (kraken parity)
    return True, None


def _arr_reason_to_kraken(reason: str, candidate: Any, profile: Mapping[str, Any]) -> str:
    """Map ARR evaluation reason to Kraken's expected rejection string."""
    # Direct mappings
    if reason == "cost:free_disallowed":
        return "free routes disabled by policy"
    if reason == "cost:free_blocked_for_sensitive":
        return "free routes disabled for sensitive work"
    if reason == "cost:paid_disallowed":
        return "paid routes disabled by policy"
    if reason == "quality:unknown":
        return "capability quality is unknown and cannot be assessed"
    if reason.startswith("quality:below_minimum:"):
        # ARR: quality:below_minimum:30<40  -> Kraken: quality score 30 is below 40
        try:
            payload = reason.split(":", 2)[2]  # "30<40"
            q_str, min_str = payload.split("<", 1)
            q = float(q_str)
            m = float(min_str)
            return f"quality score {q:g} is below {m:g}"
        except Exception:
            # fallback to effective minimum
            q = getattr(candidate, "quality", 0)
            m = _effective_minimum(profile)
            try:
                return f"quality score {float(q):g} is below {float(m):g}"
            except Exception:
                return f"quality score {q} is below {m}"
    if reason == "capability:tool_call_unavailable":
        return "tool calling is not advertised"
    if reason == "capability:reasoning_required":
        return "reasoning support is not advertised"
    if reason == "context:insufficient" or reason == "context:unknown_disallowed":
        return "context window is too small"
    if reason == "availability:unavailable":
        return "catalog status is not active"
    if reason.startswith("availability:"):
        return "catalog status is not active"
    if reason.startswith("quota:"):
        # quota:exhausted / quota:blocked -> map to quota state message if possible
        qs = getattr(candidate, "quota_state", "unknown")
        if qs in {"insufficient", "exhausted", "unavailable", "blocked"}:
            # normalize to kraken wording
            if qs == "exhausted":
                qs = "insufficient"
            return f"quota state is {qs}"
        if reason == "quota:exhausted":
            return "quota state is insufficient"
        if reason == "quota:blocked":
            return "quota state is blocked"
        return f"quota state is {qs}"
    if reason.startswith("capability:missing:"):
        # generic capability miss; map to tool/reasoning if known else keep
        cap = reason.split(":", 2)[2] if ":" in reason else reason
        if cap == "tool_call":
            return "tool calling is not advertised"
        if cap == "reasoning":
            return "reasoning support is not advertised"
        return f"capability {cap} is not advertised"
    if reason.startswith("tps:"):
        return "tps is insufficient"
    # fallback: return raw but ensure it contains expected substrings for test
    return reason


def arr_rejection_summary(
    candidates: Sequence[Any],
    profile: Mapping[str, Any],
    config: Mapping[str, Any],
    sensitive: bool,
    excluded_routes: set[str] | None = None,
    excluded_providers: set[str] | None = None,
) -> dict[str, int]:
    """Build Kraken-compatible top rejection reasons via ARR evaluations.

    Returns dict of Kraken rejection string -> count, for error detail.
    """
    if not ARR_AVAILABLE:
        return {}
    excluded_routes = excluded_routes or set()
    excluded_providers = excluded_providers or set()
    usable = [c for c in candidates if c.route not in excluded_routes and c.provider not in excluded_providers]
    if not usable:
        return {}
    try:
        arr_cands = tuple(_kraken_to_arr_candidate(c) for c in usable)
        task = _profile_to_arr_task("kraken", profile, sensitive)
        policy = _config_to_arr_policy(config, profile)
        arr_cands = tuple(c for c in arr_cands if c is not None)
        if not arr_cands:
            return {}
        decision = arr_route(task, arr_cands, policy)
        reasons: dict[str, int] = {}
        # Build map from candidate_id to kraken candidate for message mapping
        id_to_kraken = {c.route: c for c in usable}
        for ev in decision.evaluations:
            if ev.eligible:
                continue
            cid = ev.candidate.candidate_id
            kc = id_to_kraken.get(cid)
            if kc is None:
                continue
            for r in ev.reasons:
                kraken_msg = _arr_reason_to_kraken(r, kc, profile)
                reasons[kraken_msg] = reasons.get(kraken_msg, 0) + 1
        # If ARR gave no reasons (should not), fallback to non-quality check
        if not reasons:
            for c in usable:
                ok, rej = _kraken_non_quality_eligible(c, profile, config, sensitive)
                if not ok and rej:
                    reasons[rej] = reasons.get(rej, 0) + 1
                elif not ok:
                    reasons["no candidate satisfies policy"] = reasons.get("no candidate satisfies policy", 0) + 1
                else:
                    # check quality below minimum specifically
                    q = getattr(c, "quality", None)
                    if q is not None:
                        m = _effective_minimum(profile)
                        if q < m:
                            msg = f"quality score {q:g} is below {m:g}"
                            reasons[msg] = reasons.get(msg, 0) + 1
        return reasons
    except Exception:
        # fallback to direct kraken checks
        reasons: dict[str, int] = {}
        for c in usable:
            ok, rej = _kraken_non_quality_eligible(c, profile, config, sensitive)
            if not ok and rej:
                reasons[rej] = reasons.get(rej, 0) + 1
            else:
                q = getattr(c, "quality", None)
                if q is None:
                    msg = "capability quality is unknown and cannot be assessed"
                    reasons[msg] = reasons.get(msg, 0) + 1
                else:
                    m = _effective_minimum(profile)
                    if q < m:
                        msg = f"quality score {q:g} is below {m:g}"
                        reasons[msg] = reasons.get(msg, 0) + 1
        return reasons


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
    for c in usable_kraken:
        if getattr(c, "quality", None) is None:
            continue  # unknown quality never qualifies even with relax_quality
        ok, _ = _kraken_non_quality_eligible(c, profile, config, sensitive)
        if ok:
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
