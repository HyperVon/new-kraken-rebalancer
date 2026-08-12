"""Parity and characterization tests for ARR migration (PR #241 fixes).

Covers the 9 required cases plus secondary, free allowlist, unknown semantics,
fallback, exception handling, clean install, and real config profile semantics.
Compares behavior against legacy where applicable, then encodes expected ARR-owned behavior.
"""

import importlib.util
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name("router.py")
SPEC = importlib.util.spec_from_file_location("model_router_parity", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.path.insert(0, str(MODULE_PATH.parent))
sys.modules[SPEC.name] = MODULE  # for arr_bridge import inside router
SPEC.loader.exec_module(MODULE)

import arr_bridge  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def candidate(route, billing="paid", quality=None, aa_cost=None, extra_aa=None, **overrides):
    provider, model = route.split("/", 1)
    value = MODULE.Candidate(
        route=route,
        provider=provider,
        model=model,
        name=model,
        status=overrides.get("status", "active"),
        input_cost=1.0,
        output_cost=2.0,
        cache_read_cost=None,
        context_limit=overrides.get("context_limit", 128000),
        output_limit=16000,
        tool_call=overrides.get("tool_call", True),
        reasoning=overrides.get("reasoning", True),
        attachment=False,
        pdf=False,
        billing=billing,
    )
    # Allow overriding free_allowed, quota_state, etc.
    for k in ("free_allowed", "quota_state", "quota_percent", "status", "context_limit", "tool_call", "reasoning"):
        if k in overrides:
            setattr(value, k, overrides[k])
    if quality is not None:
        value.aa = {
            "slug": model,
            "evaluations": {
                "artificial_analysis_coding_index": quality,
                "artificial_analysis_intelligence_index": quality,
                "artificial_analysis_agentic_index": quality,
                **(extra_aa or {}),
            },
            "artificial_analysis_intelligence_index_cost": {"cost_per_task": {"total_cost": aa_cost}},
        }
        value.aa_match = "configured"
        # Also ensure quality_metrics via direct aa evaluations will be translated
        if extra_aa:
            for ek, ev in extra_aa.items():
                value.aa["evaluations"][ek] = ev
    elif extra_aa:
        value.aa = {"slug": model, "evaluations": dict(extra_aa), "artificial_analysis_intelligence_index_cost": {"cost_per_task": {"total_cost": aa_cost}}}
        value.aa_match = "configured"
    return value


class ParityTests(unittest.TestCase):
    def test_cheap_failing_secondary_loses_to_expensive_compliant(self):
        # Cheap passes primary (30 vs min 20) but fails secondary agentic 15; expensive passes both but costs more
        cheap = candidate("openrouter/cheap", billing="paid", quality=30, aa_cost=0.01, extra_aa={"artificial_analysis_agentic_index": 10})
        expensive = candidate("openrouter/expensive", billing="paid", quality=30, aa_cost=0.20, extra_aa={"artificial_analysis_agentic_index": 25})
        profile = {"metric": "artificial_analysis_coding_index", "minimum": 20, "secondary": {"artificial_analysis_agentic_index": 15}}
        config = {"policy": {"allowPaid": True, "allowFree": True, "useAaCostPerTask": True}}
        selected = MODULE.select_candidate([cheap, expensive], profile, config, False)
        self.assertEqual("openrouter/expensive", selected.route)

    def test_provider_authorized_free_wins_over_paid_when_global_allowFree_false(self):
        free = candidate("openrouter/model:free", billing="free", quality=40, aa_cost=0.0, free_allowed=True)
        paid = candidate("openrouter/paid", billing="paid", quality=40, aa_cost=0.5)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 20}
        # Global allowFree false, but provider free_allowed true -> free should win (cost 0)
        config = {"providers": {"openrouter": {"allowFree": True}}, "policy": {"allowPaid": True, "allowFree": False, "useAaCostPerTask": True}}
        # Need to ensure free_allowed is set via provider config; build_candidates would set it, but our helper sets directly
        selected = MODULE.select_candidate([free, paid], profile, config, False)
        self.assertEqual("openrouter/model:free", selected.route)

    def test_unknown_context_eligible_and_insufficient_rejected(self):
        unknown_ctx = candidate("openrouter/m1", billing="paid", quality=40, context_limit=0)  # 0 -> unknown
        insufficient = candidate("openrouter/m2", billing="paid", quality=40, context_limit=1000)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 20, "context": 50000}
        config = {"policy": {"allowPaid": True, "allowFree": True}}
        # Unknown should be eligible, insufficient should be rejected
        selected = MODULE.select_candidate([unknown_ctx, insufficient], profile, config, False)
        self.assertEqual("openrouter/m1", selected.route)
        # Now test insufficient alone should raise
        with self.assertRaises(MODULE.RouterError) as ctx:
            MODULE.select_candidate([insufficient], profile, config, False)
        self.assertIn("context window is too small", str(ctx.exception))

    def test_unknown_status_eligible_and_wins_on_cost(self):
        unknown_status = candidate("openrouter/cheap", billing="paid", quality=40, aa_cost=0.01, status="unknown")
        known = candidate("openrouter/expensive", billing="paid", quality=40, aa_cost=1.0, status="active")
        profile = {"metric": "artificial_analysis_coding_index", "minimum": 20}
        config = {"policy": {"allowPaid": True, "allowFree": True, "useAaCostPerTask": True}}
        selected = MODULE.select_candidate([unknown_status, known], profile, config, False)
        self.assertEqual("openrouter/cheap", selected.route)

    def test_unknown_quota_eligible_but_loses_tie(self):
        # Same effective_cost, same quality -> tie should go to sufficient quota
        unknown_quota = candidate("openrouter/a", billing="paid", quality=40, aa_cost=0.5, quota_state="unknown", quota_percent=50.0)
        sufficient = candidate("openrouter/b", billing="paid", quality=40, aa_cost=0.5, quota_state="sufficient", quota_percent=50.0)
        # Ensure status active, etc.
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 20}
        config = {"policy": {"allowPaid": True, "allowFree": True, "useAaCostPerTask": True}}
        selected = MODULE.select_candidate([unknown_quota, sufficient], profile, config, False)
        self.assertEqual("openrouter/b", selected.route)

    def test_unknown_primary_quality_rejected_even_in_fallback(self):
        unknown = candidate("openrouter/unk", billing="paid", quality=None)
        low = candidate("openrouter/low", billing="paid", quality=10)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30}
        config = {"policy": {"allowPaid": True, "allowFree": True}}
        selected = MODULE.select_candidate([unknown, low], profile, config, False)
        # low is below minimum but should be fallback; unknown should never win even in fallback
        self.assertEqual("openrouter/low", selected.route)

    def test_below_minimum_fallback_preserves_non_quality_gates(self):
        # Cheap low quality fails tool_call gate -> even fallback should not rescue it if tool_call false
        # We test: cheap_low with tool_call False (fails gate) vs low with tool_call True but low quality
        cheap_blocked = candidate("openrouter/cheap", billing="paid", quality=10, tool_call=False)
        low_ok = candidate("openrouter/low", billing="paid", quality=10, tool_call=True)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30, "requiresReasoning": False}
        config = {"policy": {"allowPaid": True, "allowFree": True}}
        selected = MODULE.select_candidate([cheap_blocked, low_ok], profile, config, False)
        # Both below minimum, but cheap_blocked fails tool gate even in fallback, so low_ok should win
        self.assertEqual("openrouter/low", selected.route)

    def test_arr_exception_fails_closed(self):
        cand = candidate("openrouter/m", billing="paid", quality=40)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 20}
        config = {"policy": {"allowPaid": True}}
        with patch.object(arr_bridge, "arr_route", side_effect=RuntimeError("boom")):
            with self.assertRaises(Exception) as ctx:
                MODULE.select_candidate([cand], profile, config, False)
            # Fail-closed: must raise RouterError (any module instance) with chained cause
            self.assertIn("ARR routing failed", str(ctx.exception))
            self.assertIsNotNone(ctx.exception.__cause__)
            self.assertIsInstance(ctx.exception, MODULE.RouterError)

    def test_unknown_billing_is_paid_gated(self):
        unknown = candidate("openrouter/unknown", billing="unknown", quality=40, aa_cost=0.2)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 20}
        denied_config = {"policy": {"allowPaid": False, "allowFree": True}}
        with self.assertRaises(MODULE.NoRouteError) as denied:
            MODULE.select_candidate([unknown], profile, denied_config, False)
        self.assertIn("paid routes disabled by policy", str(denied.exception))

        allowed = MODULE.select_candidate(
            [candidate("openrouter/unknown", billing="unknown", quality=40, aa_cost=0.2)],
            profile,
            {"policy": {"allowPaid": True, "allowFree": True}},
            False,
        )
        self.assertEqual("openrouter/unknown", allowed.route)

    def test_no_route_uses_one_arr_decision(self):
        cand = candidate("openrouter/m", billing="paid", quality=10, aa_cost=0.2, tool_call=False)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 50}
        config = {"policy": {"allowPaid": True, "allowFree": True}}
        with patch.object(arr_bridge, "arr_route", wraps=arr_bridge.arr_route) as route:
            with self.assertRaises(MODULE.NoRouteError):
                MODULE.select_candidate([cand], profile, config, False)
        self.assertEqual(1, route.call_count)

    def test_tps_guard_does_not_mask_arr_integration_failure(self):
        cand = candidate("openrouter/m", billing="free", quality=40, aa_cost=0.0)
        config = {
            "policy": {"allowPaid": True, "allowFree": True},
            "tpsProbe": {"enabled": True, "maxProbesPerRun": 1, "onlyFree": True},
        }
        with patch.object(
            MODULE,
            "select_candidate",
            side_effect=arr_bridge.ARRIntegrationError("ARR routing failed"),
        ):
            with self.assertRaises(arr_bridge.ARRIntegrationError):
                MODULE.select_with_tps_guard([cand], {"minimum": 20}, config, False)

    def test_fallback_evidence_is_preserved_in_report(self):
        cand = candidate("openrouter/m", billing="paid", quality=10, aa_cost=0.2)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 50}
        config = {"policy": {"allowPaid": True, "allowFree": True}}
        selected = MODULE.select_candidate([cand], profile, config, False)
        self.assertTrue(selected.fallback_used)
        self.assertTrue(
            MODULE.report(selected, "coding", profile, "configured", False)["fallback_used"]
        )

    def test_clean_environment_wrappers_use_venv(self):
        # Verify wrappers reference .venv and requirements pinned, and setup installs correctly
        script_dir = Path(__file__).parent
        route_kilo = (script_dir / "route-kilo").read_text()
        route_sub = (script_dir / "route-subagents").read_text()
        self.assertIn(".venv/bin/python", route_kilo)
        self.assertIn(".venv/bin/python", route_sub)
        req = (script_dir / "requirements.txt").read_text()
        self.assertIn("agent-runtime-router.git@", req)
        self.assertIn("d7d0cfefd279142559d11bd2c6d20d17f2d87ded", req)
        # Verify setup script exists and is executable
        setup = script_dir / "setup.sh"
        self.assertTrue(setup.exists())
        self.assertTrue(setup.stat().st_mode & 0o111)
        # The suite is intentionally run only after setup.sh; a missing venv is
        # a failed clean-install gate, not a skippable local condition.
        venv_py = script_dir / ".venv" / "bin" / "python"
        self.assertTrue(venv_py.exists(), "run .kilo/model-router/setup.sh before this suite")

    def test_real_config_profile_semantics(self):
        # Use actual tracked config profile (coding) semantics: secondary agentic threshold
        config_path = Path(__file__).with_name("config")
        # Load actual config if exists, else use default profiles from router
        if config_path.exists():
            cfg = MODULE.load_config(config_path)
        else:
            cfg = MODULE.DEFAULT_CONFIG
        # Use the tracked DEFAULT_PROFILES["coding"] which has secondary agentic 15
        profile_name, profile = MODULE.profile_config(cfg, "coding", "Fix the failing JVM test in PortfolioCalculations")
        self.assertEqual("coding", profile_name)
        self.assertIn("secondary", profile)
        # Cheap with low agentic (10) should be rejected even though primary passes
        cheap = candidate("openrouter/cheap", billing="paid", quality=50, aa_cost=0.01, extra_aa={"artificial_analysis_agentic_index": 10})
        expensive = candidate("openrouter/expensive", billing="paid", quality=50, aa_cost=0.5, extra_aa={"artificial_analysis_agentic_index": 25})
        # Ensure profile minimum is 45 for coding
        self.assertEqual(45, profile.get("minimum"))
        selected = MODULE.select_candidate([cheap, expensive], profile, cfg, False)
        self.assertEqual("openrouter/expensive", selected.route)

    def test_secondary_missing_tolerated(self):
        # Missing secondary metric should be tolerated (legacy)
        cand = candidate("openrouter/m", billing="paid", quality=50, extra_aa={})  # no agentic
        # Remove agentic from evaluations
        if cand.aa and "evaluations" in cand.aa:
            cand.aa["evaluations"].pop("artificial_analysis_agentic_index", None)
        profile = {"metric": "artificial_analysis_coding_index", "minimum": 45, "secondary": {"artificial_analysis_agentic_index": 15}}
        config = {"policy": {"allowPaid": True}}
        selected = MODULE.select_candidate([cand], profile, config, False)
        self.assertEqual("openrouter/m", selected.route)


if __name__ == "__main__":
    unittest.main()
