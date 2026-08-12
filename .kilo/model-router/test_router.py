import copy
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name("router.py")
SPEC = importlib.util.spec_from_file_location("model_router", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.path.insert(0, str(MODULE_PATH.parent))
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

ROOT = Path(__file__).resolve().parents[2]


def candidate(route, billing="paid", quality=None, aa_cost=None):
    provider, model = route.split("/", 1)
    value = MODULE.Candidate(
        route=route,
        provider=provider,
        model=model,
        name=model,
        status="active",
        input_cost=1.0,
        output_cost=2.0,
        cache_read_cost=None,
        context_limit=128000,
        output_limit=16000,
        tool_call=True,
        reasoning=True,
        attachment=False,
        pdf=False,
        billing=billing,
    )
    if quality is not None:
        value.aa = {
            "slug": model,
            "evaluations": {
                "artificial_analysis_coding_index": quality,
                "artificial_analysis_intelligence_index": quality,
                "artificial_analysis_agentic_index": quality,
            },
            "artificial_analysis_intelligence_index_cost": {"cost_per_task": {"total_cost": aa_cost}},
        }
        value.aa_match = "configured"
    return value


class RouterTests(unittest.TestCase):
    def test_full_tui_command_uses_kilo_prompt_and_selected_route(self):
        args = MODULE.argparse.Namespace(
            tui=True,
            agent=None,
            variant=None,
            interactive=False,
            continue_session=False,
            session=None,
            auto=False,
            message=["Review", "the", "docs"],
        )
        command = MODULE.build_kilo_command(args, {"route": "openai/example", "profile": "agentic"})
        self.assertEqual(
            ["kilo", "--model", "openai/example", "--agent", "code", "--prompt", "Review the docs"],
            command,
        )

    def test_full_tui_variant_uses_agent_config_overlay(self):
        args = MODULE.argparse.Namespace(
            tui=True,
            agent=None,
            variant=None,
            interactive=False,
            continue_session=False,
            session=None,
            auto=False,
            message=["Review", "the", "docs"],
        )
        result = {"route": "opencode-go/gpt-5.6-luna", "variant": "xhigh", "profile": "detailed-review"}
        command = MODULE.build_kilo_command(args, result)
        self.assertEqual(
            ["kilo", "--model", "opencode-go/gpt-5.6-luna", "--agent", "ask", "--prompt", "Review the docs"],
            command,
        )
        content = MODULE.tui_variant_config(args, result)
        self.assertIsNotNone(content)
        config = MODULE.json.loads(content)
        self.assertEqual("opencode-go/gpt-5.6-luna", config["agent"]["ask"]["model"])
        self.assertEqual("xhigh", config["agent"]["ask"]["variant"])
        self.assertEqual("ask", config["default_agent"])

    def test_infer_agent_returns_code_for_implementation_profiles(self):
        self.assertEqual("code", MODULE.infer_agent("agentic"))
        self.assertEqual("code", MODULE.infer_agent("coding"))
        self.assertEqual("code", MODULE.infer_agent("critical"))
        self.assertEqual("code", MODULE.infer_agent("trivial"))

    def test_infer_agent_returns_ask_for_review_profiles(self):
        self.assertEqual("ask", MODULE.infer_agent("quick-review"))
        self.assertEqual("ask", MODULE.infer_agent("detailed-review"))

    def test_open_pr_task_infers_code_mode_not_ask(self):
        # /open-pr infers the agentic profile -> implementation -> code (Code mode)
        self.assertEqual("agentic", MODULE.infer_profile("/open-pr"))
        self.assertEqual("code", MODULE.infer_agent(MODULE.infer_profile("/open-pr")))

    def test_code_review_task_infers_ask_mode(self):
        # /code-review infers detailed-review -> read-only -> ask (Ask mode)
        self.assertEqual("detailed-review", MODULE.infer_profile("/code-review of this branch's changes vs main"))
        self.assertEqual("ask", MODULE.infer_agent(MODULE.infer_profile("/code-review of this branch's changes vs main")))

    def test_explicit_agent_flag_overrides_inference(self):
        args = MODULE.argparse.Namespace(
            tui=True,
            agent="ask",
            variant=None,
            interactive=False,
            continue_session=False,
            session=None,
            auto=False,
            message=["Fix", "the", "bug"],
        )
        command = MODULE.build_kilo_command(args, {"route": "openai/example", "profile": "coding"})
        self.assertEqual(
            ["kilo", "--model", "openai/example", "--agent", "ask", "--prompt", "Fix the bug"],
            command,
        )

    def test_select_candidate_prefers_profile_variant(self):
        model = candidate("opencode-go/example", quality=50)
        model.variants = {"low": {}, "high": {}, "max": {}}
        selected = MODULE.select_candidate(
            [model],
            {"minimum": 10, "variantPreference": ["max", "high"]},
            {"policy": {"allowPaid": True, "allowFree": False, "useAaCostPerTask": True}},
            False,
        )
        self.assertEqual("max", selected.variant)

    def test_review_profile_keeps_free_route_eligible(self):
        free = candidate("openrouter/free-model", billing="free", quality=50)
        selected = MODULE.select_candidate(
            [free],
            {"metric": "artificial_analysis_intelligence_index", "minimum": 30},
            {"policy": {"allowPaid": True, "allowFree": True}},
            False,
        )
        self.assertEqual("openrouter/free-model", selected.route)

    def test_cheaper_free_beats_higher_quality_paid_when_both_qualified(self):
        free = candidate("openrouter/free-model", billing="free", quality=30, aa_cost=0.0)
        paid = candidate("openai/paid-model", billing="paid", quality=50, aa_cost=0.20)
        selected = MODULE.select_candidate(
            [free, paid],
            {"metric": "artificial_analysis_intelligence_index", "minimum": 20},
            {"policy": {"allowPaid": True, "allowFree": True}},
            False,
        )
        self.assertEqual("openrouter/free-model", selected.route)

    def test_cost_breaks_tie_when_capability_equal(self):
        free = candidate("openrouter/free-model", billing="free", quality=50, aa_cost=0.0)
        paid = candidate("openai/paid-model", billing="paid", quality=50, aa_cost=0.20)
        selected = MODULE.select_candidate(
            [paid, free],
            {"metric": "artificial_analysis_intelligence_index", "minimum": 20},
            {"policy": {"allowPaid": True, "allowFree": True}},
            False,
        )
        self.assertEqual("openrouter/free-model", selected.route)

    def test_prepare_initial_prompt_resolves_known_slash_skill(self):
        skill_path = ROOT / ".agents" / "skills" / "open-pr" / "SKILL.md"
        skill_content = skill_path.read_text(encoding="utf-8").strip()
        prompt = MODULE.prepare_initial_prompt("/open-pr Open a pull request")
        self.assertIn(skill_content, prompt)
        self.assertIn("/open-pr Open a pull request", prompt)

    def test_prepare_initial_prompt_leaves_unknown_slash_command_unchanged(self):
        task = "/unknown-command do the work"
        self.assertEqual(task, MODULE.prepare_initial_prompt(task))

    def test_parse_catalog_reads_top_level_model_objects(self):
        output = """openai/example\n{
  \"id\": \"example\",
  \"providerID\": \"openai\",
  \"name\": \"Example\",
  \"cost\": {\"input\": 1, \"output\": 2},
  \"capabilities\": {\"toolcall\": true}
}
"""
        models = MODULE.parse_catalog_output("openai", output)
        self.assertEqual(["example"], [model["id"] for model in models])

    def test_catalog_cache_writes_and_serves_cached_models(self):
        cache = MODULE.catalog_cache_path("openai")
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(
            MODULE.json.dumps({"fetchedAt": 9999999999.0, "models": [{"id": "cached-model"}]}),
            encoding="utf-8",
        )
        try:
            result = MODULE.catalog_for_provider("openai", refresh=False, cache_hours=2)
            self.assertEqual(["cached-model"], [model["id"] for model in result])
        finally:
            cache.unlink(missing_ok=True)

    def test_auto_match_is_cached_by_aa_fingerprint(self):
        aa = {
            "alpha": {"slug": "alpha", "name": "Alpha Model"},
            "beta": {"slug": "beta", "name": "Beta Model"},
        }
        MODULE._AUTO_MATCH_CACHE.clear()
        MODULE._AUTO_MATCH_WRITTEN.clear()
        c = candidate("openrouter/alpha-7b", quality=30)
        c.model, c.name = "alpha-7b", "Alpha 7B"
        matched, kind = MODULE.match_artificial_analysis(c, {}, aa)
        self.assertEqual("automatic", kind)
        self.assertEqual("alpha", matched["slug"])
        fingerprint = f"{MODULE.AA_MATCHER_VERSION}|" + "|".join(sorted(aa))
        key = "alpha-7b|Alpha 7B"
        self.assertEqual("alpha", MODULE._AUTO_MATCH_CACHE[fingerprint][key])
        path = MODULE.aa_matches_cache_path()
        try:
            MODULE._save_auto_match_cache(fingerprint, MODULE._AUTO_MATCH_CACHE[fingerprint])
            self.assertTrue(path.exists())
            saved = MODULE.json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual("alpha", saved["matches"][key])
        finally:
            path.unlink(missing_ok=True)
            MODULE._AUTO_MATCH_CACHE.clear()
            MODULE._AUTO_MATCH_WRITTEN.clear()


    def test_creator_prefixed_catalog_name_matches_aa_row(self):
        aa = {
            "hy3": {"slug": "hy3", "name": "Hy3", "model_creator": {"name": "Tencent"}},
            "gpt-6": {"slug": "gpt-6", "name": "GPT-6", "model_creator": {"name": "OpenAI"}},
        }
        MODULE._AUTO_MATCH_CACHE.clear()
        MODULE._AUTO_MATCH_WRITTEN.clear()
        c = candidate("kilo/tencent/hy3")
        c.model, c.name = "tencent/hy3", "Tencent: Hy3"
        matched, kind = MODULE.match_artificial_analysis(c, {}, aa)
        MODULE._AUTO_MATCH_CACHE.clear()
        self.assertEqual("automatic", kind)
        self.assertEqual("hy3", matched["slug"])

    def test_free_suffix_does_not_match_neighbouring_preview_row(self):
        aa = {
            "hy3": {"slug": "hy3", "name": "Hy3", "model_creator": {"name": "Tencent"}},
            "hy3-preview": {
                "slug": "hy3-preview",
                "name": "Hy3-preview (Reasoning)",
                "model_creator": {"name": "Tencent"},
            },
        }
        MODULE._AUTO_MATCH_CACHE.clear()
        MODULE._AUTO_MATCH_WRITTEN.clear()
        c = candidate("kilo/tencent/hy3:free", billing="free")
        c.model, c.name = "tencent/hy3:free", "Tencent: Hy3 (free)"
        matched, kind = MODULE.match_artificial_analysis(c, {}, aa)
        MODULE._AUTO_MATCH_CACHE.clear()
        self.assertEqual("automatic", kind)
        self.assertEqual("hy3", matched["slug"])

    def test_paid_routes_use_lower_benchmark_task_cost(self):
        cheap = candidate("openrouter/cheap", quality=30, aa_cost=0.02)
        expensive = candidate("openrouter/expensive", quality=30, aa_cost=0.20)
        profile = {"metric": "artificial_analysis_coding_index", "minimum": 20}
        config = {"policy": {"allowPaid": True, "allowFree": False, "useAaCostPerTask": True}}
        selected = MODULE.select_candidate([expensive, cheap], profile, config, False)
        self.assertEqual("openrouter/cheap", selected.route)

    def test_free_route_can_be_disabled(self):
        free = candidate("openrouter/model:free", billing="free", quality=30, aa_cost=0.0)
        profile = {"metric": "artificial_analysis_coding_index", "minimum": 20}
        config = {"policy": {"allowPaid": True, "allowFree": False, "useAaCostPerTask": True}}
        with self.assertRaises(MODULE.RouterError):
            MODULE.select_candidate([free], profile, config, False)

    def test_blacklist_excludes_model_route_and_provider_patterns(self):
        settings = {"include": ["*"]}
        blacklist = {"models": ["opencode-go/minimax-*"], "providers": ["nvidia"]}
        self.assertFalse(
            MODULE.model_is_allowed("opencode-go/minimax-m2.7", "minimax-m2.7", settings, blacklist)
        )
        self.assertFalse(MODULE.model_is_allowed("nvidia/free-model", "free-model", settings, blacklist))
        self.assertTrue(MODULE.model_is_allowed("openai/gpt-5.4", "gpt-5.4", settings, blacklist))

    def test_existing_config_filters_candidates_before_arr(self):
        config = copy.deepcopy(MODULE.DEFAULT_CONFIG)
        config["providers"] = {
            "openai": {
                "enabled": True,
                "requiresAuth": False,
                "billing": "paid",
                "include": ["openai/allowed"],
            },
            "nvidia": {
                "enabled": True,
                "requiresAuth": False,
                "billing": "free",
                "freeOnly": True,
                "allowFree": True,
                "include": ["*"],
            },
        }
        config["blacklist"] = {"models": ["openai/blocked"], "providers": ["nvidia"]}
        raw_models = [
            {"providerID": "openai", "id": "allowed", "name": "Allowed", "cost": {"input": 1.0, "output": 2.0}, "capabilities": {"toolcall": True}},
            {"providerID": "openai", "id": "other", "name": "Other", "cost": {"input": 1.0, "output": 2.0}, "capabilities": {"toolcall": True}},
            {"providerID": "openai", "id": "blocked", "name": "Blocked", "cost": {"input": 1.0, "output": 2.0}, "capabilities": {"toolcall": True}},
            {"providerID": "nvidia", "id": "free", "name": "Free", "cost": {"input": 0.0, "output": 0.0}, "capabilities": {"toolcall": True}},
        ]

        candidates = MODULE.build_candidates(raw_models, config, {})

        self.assertEqual(["openai/allowed"], [candidate.route for candidate in candidates])

    def test_task_profile_inference_escalates_trading_work(self):
        self.assertEqual("critical", MODULE.infer_profile("Review the trading order execution path"))

    def test_task_profile_inference_uses_stronger_review_profile(self):
        self.assertEqual("detailed-review", MODULE.infer_profile("Audit the documentation and agent instructions"))

    def test_code_review_task_uses_review_profile_not_coding(self):
        self.assertEqual("detailed-review", MODULE.infer_profile("/code-review of this branch's changes vs main"))

    def test_free_route_guard_detects_secret_material(self):
        self.assertTrue(MODULE.is_sensitive("Read the API key from .env", "routine"))
        self.assertFalse(MODULE.is_sensitive("Review the public trading engine", "critical"))

    def test_provider_config_counts_as_configured_access(self):
        self.assertEqual({"openrouter"}, MODULE.parse_config_provider_ids({"provider": {"openrouter": {}}}))

    def test_jsonc_provider_config_is_supported(self):
        payload = MODULE.parse_json_text('{"provider": {"openrouter": {"baseURL": "https://example.test",},}, // comment\n}')
        self.assertEqual({"openrouter"}, MODULE.parse_config_provider_ids(payload))

    def test_unknown_quality_is_excluded_even_when_policy_allows_unknown(self):
        unknown = candidate("openrouter/unk", quality=None)
        qualified = candidate("openrouter/known", quality=40)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 20}
        config = {"policy": {"allowPaid": True, "allowFree": True, "allowUnknownCapability": True}}
        selected = MODULE.select_candidate([unknown, qualified], profile, config, False)
        self.assertEqual("openrouter/known", selected.route)

    def test_unknown_quality_alone_raises_when_no_qualified_candidate(self):
        unknown = candidate("openrouter/unk", quality=None)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 20}
        config = {"policy": {"allowPaid": True, "allowFree": True, "allowUnknownCapability": True}}
        with self.assertRaises(MODULE.RouterError):
            MODULE.select_candidate([unknown], profile, config, False)

    def test_no_qualified_candidate_error_includes_top_rejection_reasons(self):
        paid_disabled = candidate("openrouter/paid", quality=50)
        paid_disabled.billing = "paid"
        paid_disabled.status = "active"
        paid_disabled.rejection = None
        unknown = candidate("openrouter/unk", quality=None, billing="free")
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 20}
        config = {"policy": {"allowPaid": False, "allowFree": True}}
        with self.assertRaises(MODULE.RouterError) as ctx:
            MODULE.select_candidate([paid_disabled, unknown], profile, config, False)
        message = str(ctx.exception)
        self.assertIn("no candidate satisfies", message)
        self.assertIn("paid routes disabled by policy", message)
        self.assertIn("capability quality is unknown", message)

    def test_margin_excludes_barely_adequate_model_for_high_risk_profile(self):
        below_margin = candidate("openrouter/weak", quality=32)
        strong = candidate("openrouter/strong", quality=45)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30, "margin": 5}
        config = {"policy": {"allowPaid": True, "allowFree": True}}
        selected = MODULE.select_candidate([below_margin, strong], profile, config, False)
        self.assertEqual("openrouter/strong", selected.route)

    def test_margin_unchanged_for_regular_profile(self):
        model = candidate("openrouter/model", quality=32)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30}
        config = {"policy": {"allowPaid": True, "allowFree": True}}
        selected = MODULE.select_candidate([model], profile, config, False)
        self.assertEqual("openrouter/model", selected.route)

    def test_below_minimum_fallback_selects_highest_quality(self):
        weak = candidate("openrouter/weak", billing="free", quality=40)
        weaker = candidate("openrouter/weaker", billing="free", quality=30)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 60}
        config = {"policy": {"allowPaid": False, "allowFree": True}}
        selected = MODULE.select_candidate([weaker, weak], profile, config, False)
        self.assertEqual("openrouter/weak", selected.route)

    def test_account_priced_blocked_when_paid_disabled(self):
        account = candidate("kilo/account-model", billing="account-priced", quality=90)
        free_model = candidate("nvidia/free", billing="free", quality=20)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 10}
        config = {"policy": {"allowPaid": False, "allowFree": True}}
        selected = MODULE.select_candidate([account, free_model], profile, config, False)
        self.assertEqual("nvidia/free", selected.route)

    def test_high_quota_preferred_when_cost_equal(self):
        low_quota = candidate("openai/a", quality=40, aa_cost=0.10)
        low_quota.quota_state = "sufficient"
        low_quota.quota_percent = 20.0
        high_quota = candidate("openrouter/b", quality=40, aa_cost=0.10)
        high_quota.quota_state = "sufficient"
        high_quota.quota_percent = 90.0
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30}
        config = {"policy": {"allowPaid": True, "allowFree": True}}
        selected = MODULE.select_candidate([low_quota, high_quota], profile, config, False)
        self.assertEqual("openrouter/b", selected.route)

    def test_free_model_with_unknown_quota_is_usable_against_paid(self):
        free = candidate("nvidia/free-model", billing="free", quality=40)
        free.quota_state = "unknown"
        free.quota_percent = None
        free.effective_cost = 0.0
        paid = candidate("openai/paid-model", quality=40, aa_cost=0.20)
        paid.quota_state = "sufficient"
        paid.quota_percent = 90.0
        paid.effective_cost = 0.20
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30}
        config = {"policy": {"allowPaid": True, "allowFree": True}}
        selected = MODULE.select_candidate([free, paid], profile, config, False)
        self.assertEqual("nvidia/free-model", selected.route)

    def test_paid_model_with_unknown_quota_deprioritized_behind_sufficient(self):
        unknown_paid = candidate("openrouter/unknown-paid", quality=40, aa_cost=0.15)
        unknown_paid.quota_state = "unknown"
        unknown_paid.quota_percent = None
        known_paid = candidate("openai/known-paid", quality=40, aa_cost=0.15)
        known_paid.quota_state = "sufficient"
        known_paid.quota_percent = 50.0
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30}
        config = {"policy": {"allowPaid": True, "allowFree": True}}
        selected = MODULE.select_candidate([unknown_paid, known_paid], profile, config, False)
        self.assertEqual("openai/known-paid", selected.route)

    def test_free_tier_selects_highest_quality_not_just_sufficient(self):
        weaker = candidate("nvidia/weaker-free", billing="free", quality=38.3)
        stronger = candidate("kilo/stronger-free", billing="free", quality=42.2)
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30}
        config = {"policy": {"allowPaid": False, "allowFree": True}}
        selected = MODULE.select_candidate([weaker, stronger], profile, config, False)
        self.assertEqual("kilo/stronger-free", selected.route)

    def test_paid_tier_still_prefers_just_sufficient_over_strongest(self):
        just_enough = candidate("openrouter/just-enough", quality=35, aa_cost=0.10)
        just_enough.quota_state = "sufficient"
        overkill = candidate("openrouter/overkill", quality=80, aa_cost=0.10)
        overkill.quota_state = "sufficient"
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30}
        config = {"policy": {"allowPaid": True, "allowFree": True, "useAaCostPerTask": True}}
        selected = MODULE.select_candidate([overkill, just_enough], profile, config, False)
        self.assertEqual("openrouter/just-enough", selected.route)

    def test_subscription_uses_real_cost_smaller_model_wins_over_large(self):
        small = candidate("opencode-go/small", billing="subscription/account-priced", quality=40, aa_cost=0.02)
        small.quota_state = "sufficient"
        large = candidate("opencode-go/kimi-k3", billing="subscription/account-priced", quality=55, aa_cost=0.40)
        large.quota_state = "sufficient"
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30}
        config = {"policy": {"allowPaid": True, "allowFree": True, "useAaCostPerTask": True}}
        selected = MODULE.select_candidate([large, small], profile, config, False)
        self.assertEqual("opencode-go/small", selected.route)

    def test_subscription_preferred_over_payg_on_cost_tie(self):
        subscription = candidate("opencode-go/sub", billing="subscription/account-priced", quality=45, aa_cost=0.10)
        subscription.quota_state = "sufficient"
        payg = candidate("openrouter/payg", billing="paid", quality=45, aa_cost=0.10)
        payg.quota_state = "sufficient"
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 30}
        config = {"policy": {"allowPaid": True, "allowFree": True, "useAaCostPerTask": True}}
        selected = MODULE.select_candidate([payg, subscription], profile, config, False)
        self.assertEqual("opencode-go/sub", selected.route)

    def test_free_unknown_quota_still_beats_subscription_with_real_cost(self):
        free = candidate("nvidia/free-model", billing="free", quality=30)
        free.quota_state = "unknown"
        free.quota_percent = None
        free.effective_cost = 0.0
        subscription = candidate("opencode-go/sub", billing="subscription/account-priced", quality=45, aa_cost=0.20)
        subscription.quota_state = "sufficient"
        profile = {"metric": "artificial_analysis_intelligence_index", "minimum": 20}
        config = {"policy": {"allowPaid": True, "allowFree": True, "useAaCostPerTask": True}}
        selected = MODULE.select_candidate([subscription, free], profile, config, False)
        self.assertEqual("nvidia/free-model", selected.route)

    def test_synthesize_or_record_maps_indices_to_aa_metric_keys(self):
        raw = {
            "id": "openai/gpt-5.6-luna",
            "name": "GPT-5.6 Luna",
            "benchmarks": {
                "artificial_analysis": {
                    "intelligence_index": 51.2,
                    "coding_index": 71.4,
                    "agentic_index": 45.6,
                }
            },
            "pricing": {"prompt": "0.10"},
        }
        record = MODULE._synthesize_or_record("openai/gpt-5.6-luna", raw)
        evaluations = record["evaluations"]
        self.assertEqual(51.2, evaluations["artificial_analysis_intelligence_index"])
        self.assertEqual(71.4, evaluations["artificial_analysis_coding_index"])
        self.assertEqual(45.6, evaluations["artificial_analysis_agentic_index"])
        self.assertEqual("openai/gpt-5.6-luna", record["slug"])
        self.assertEqual("openrouter", record["source"])

    def test_synthesize_or_record_skips_missing_indices(self):
        raw = {
            "id": "cohere/north-mini-code:free",
            "benchmarks": {
                "artificial_analysis": {"intelligence_index": 19.8, "coding_index": 36.5},
            },
        }
        record = MODULE._synthesize_or_record("cohere/north-mini-code:free", raw)
        self.assertIn("artificial_analysis_intelligence_index", record["evaluations"])
        self.assertIn("artificial_analysis_coding_index", record["evaluations"])
        self.assertNotIn("artificial_analysis_agentic_index", record["evaluations"])

    def test_load_openrouter_benchmarks_parses_model_payload(self):
        import json as json_lib
        import io

        payload = {
            "data": [
                {
                    "id": "z-ai/glm-5.2",
                    "name": "GLM 5.2",
                    "benchmarks": {
                        "artificial_analysis": {"intelligence_index": 51.1, "coding_index": 68.8, "agentic_index": 43.1}
                    },
                    "pricing": {"prompt": "0.0"},
                },
                {
                    "id": "nvidia/no-benchmarks",
                    "name": "No Benchmarks",
                    "benchmarks": {},
                    "pricing": {},
                },
                {
                    "id": "nvidia/missing-artificial-analysis",
                    "name": "Missing AA",
                    "benchmarks": {"design_arena": []},
                    "pricing": {},
                },
            ]
        }

        class FakeResponse:
            def __init__(self, data):
                self._buf = io.StringIO(json_lib.dumps(data))

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return self._buf.read()

        def fake_urlopen(request, timeout=None):
            return FakeResponse(payload)

        config = {"artificialAnalysis": {"enabled": True, "cacheHours": 24}}
        with patch.object(MODULE.urllib.request, "urlopen", side_effect=fake_urlopen), patch.object(
            MODULE,
            "openrouter_benchmarks_cache_path",
            return_value=Path("/tmp/kilo-test-or-cache-nonexistent-xyz.json"),
        ):
            models, status = MODULE.load_openrouter_benchmarks(config, refresh=True)
        self.assertEqual("fresh", status)
        self.assertIn("z-ai/glm-5.2", models)
        self.assertNotIn("nvidia/no-benchmarks", models)
        self.assertNotIn("nvidia/missing-artificial-analysis", models)
        record = models["z-ai/glm-5.2"]
        self.assertEqual(51.1, record["evaluations"]["artificial_analysis_intelligence_index"])
        self.assertEqual(68.8, record["evaluations"]["artificial_analysis_coding_index"])
        self.assertEqual(43.1, record["evaluations"]["artificial_analysis_agentic_index"])
        # the temp path write is a no-op since the path is non-existent dir? it writes to /tmp which exists
        import os

        if os.path.exists("/tmp/kilo-test-or-cache-nonexistent-xyz.json"):
            os.remove("/tmp/kilo-test-or-cache-nonexistent-xyz.json")

    def test_or_fallback_aa_records_make_unknown_candidate_qualify(self):
        # A candidate the AA cache would score as unknown-quality gets an AA record
        # synthesized from OpenRouter benchmarks, lifting it above the profile floor.
        or_models = {
            "z-ai/glm-5.2": MODULE._synthesize_or_record(
                "z-ai/glm-5.2",
                {
                    "id": "z-ai/glm-5.2",
                    "name": "GLM 5.2",
                    "benchmarks": {
                        "artificial_analysis": {"intelligence_index": 51.1, "coding_index": 68.8, "agentic_index": 43.1}
                    },
                    "pricing": {"prompt": "0.0"},
                },
            )
        }
        # Build a candidate whose model matches the OR id exactly; free billing.
        glm = MODULE.Candidate(
            route="openrouter/z-ai/glm-5.2",
            provider="openrouter",
            model="z-ai/glm-5.2",
            name="GLM 5.2",
            status="active",
            input_cost=0.0,
            output_cost=0.0,
            cache_read_cost=None,
            context_limit=128000,
            output_limit=16000,
            tool_call=True,
            reasoning=True,
            attachment=False,
            pdf=False,
            billing="free",
            free_allowed=True,
            variants={},
            preferred_variant=None,
        )
        aa, match = MODULE.match_artificial_analysis(glm, {}, or_models)
        self.assertIsNotNone(aa)
        self.assertIn(match, ("automatic", "configured"))
        self.assertEqual(43.1, aa["evaluations"]["artificial_analysis_agentic_index"])

    def _probe_config(self, **overrides):
        config = copy.deepcopy(MODULE.DEFAULT_CONFIG)
        config["tpsProbe"].update(overrides)
        return config

    def _free(self, name, quality=60):
        return candidate(f"nvidia/{name}", billing="free", quality=quality)

    def _free_route(self, route, quality=60):
        return candidate(route, billing="free", quality=quality)

    def _paid(self, name, quality=60, aa_cost=0.001):
        return candidate(f"openai/{name}", billing="paid", quality=quality, aa_cost=aa_cost)

    def test_slow_free_route_is_excluded_and_next_best_chosen(self):
        with patch("model_router.probe_tps", return_value=(6.0, "probe")):
            selected, warnings = MODULE.select_with_tps_guard(
                [self._free("slow-a"), self._paid("fallback")],
                MODULE.DEFAULT_PROFILES["coding"],
                self._probe_config(),
                False,
            )
        self.assertEqual("openai/fallback", selected.route)
        self.assertTrue(any("below" in warning for warning in warnings))
        self.assertTrue(any("next cheapest qualifying" in warning for warning in warnings))

    def test_fast_free_route_is_kept(self):
        with patch("model_router.probe_tps", return_value=(120.0, "probe")):
            selected, warnings = MODULE.select_with_tps_guard(
                [self._free("fast")],
                MODULE.DEFAULT_PROFILES["coding"],
                self._probe_config(),
                False,
            )
        self.assertEqual("nvidia/fast", selected.route)
        self.assertEqual([], warnings)

    def test_unknown_tps_never_blocks_selection(self):
        with patch("model_router.probe_tps", return_value=(None, "probe failed (URLError)")):
            selected, warnings = MODULE.select_with_tps_guard(
                [self._free("unmeasurable")],
                MODULE.DEFAULT_PROFILES["coding"],
                self._probe_config(),
                False,
            )
        self.assertEqual("nvidia/unmeasurable", selected.route)
        self.assertEqual([], warnings)

    def test_paid_route_is_never_probed_when_only_free(self):
        with patch("model_router.probe_tps", return_value=(6.0, "probe")) as probe:
            selected, warnings = MODULE.select_with_tps_guard(
                [self._paid("paid-only")],
                MODULE.DEFAULT_PROFILES["coding"],
                self._probe_config(),
                False,
            )
        probe.assert_not_called()
        self.assertEqual("openai/paid-only", selected.route)
        self.assertEqual([], warnings)

    def test_all_free_slow_falls_back_to_cheapest_qualifying_paid(self):
        with patch("model_router.probe_tps", return_value=(5.0, "probe")) as probe:
            selected, warnings = MODULE.select_with_tps_guard(
                [self._free("slow-a"), self._free("slow-b"), self._paid("fallback")],
                MODULE.DEFAULT_PROFILES["coding"],
                self._probe_config(),
                False,
            )
        self.assertEqual(2, probe.call_count)
        self.assertEqual("openai/fallback", selected.route)
        self.assertTrue(any("next cheapest qualifying" in warning for warning in warnings))

    def test_no_qualifying_route_outside_slow_free_keeps_best_available(self):
        config = self._probe_config()
        config["policy"]["allowPaid"] = False
        with patch("model_router.probe_tps", return_value=(5.0, "probe")) as probe:
            selected, warnings = MODULE.select_with_tps_guard(
                [self._free("slow-a"), self._free("slow-b")],
                MODULE.DEFAULT_PROFILES["coding"],
                config,
                False,
            )
        self.assertGreater(probe.call_count, 0)
        self.assertTrue(selected.billing == "free")
        self.assertTrue(any("kept the best available" in warning for warning in warnings))

    def test_probe_can_be_disabled(self):
        with patch("model_router.probe_tps", return_value=(6.0, "probe")) as probe:
            selected, warnings = MODULE.select_with_tps_guard(
                [self._free("slow-a"), self._paid("fallback")],
                MODULE.DEFAULT_PROFILES["coding"],
                self._probe_config(enabled=False),
                False,
            )
        probe.assert_not_called()
        self.assertEqual("nvidia/slow-a", selected.route)
        self.assertEqual([], warnings)

    def test_probe_results_are_cached_and_reused(self):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return b'{"usage": {"completion_tokens": 300}}'

        cache_path = Path(tempfile.mkdtemp()) / "tps.json"
        with patch("model_router.urllib.request.urlopen", return_value=FakeResponse()), patch.object(
            MODULE, "TPS_CACHE_PATH", cache_path
        ):
            first_tps, first_source = MODULE.probe_tps(self._free("fast"), MODULE.DEFAULT_CONFIG)
            second_tps, second_source = MODULE.probe_tps(self._free("fast"), MODULE.DEFAULT_CONFIG)
        self.assertIsNotNone(first_tps)
        self.assertEqual("probe", first_source)
        self.assertEqual(first_tps, second_tps)
        self.assertEqual("cache", second_source)

    def test_stale_probe_cache_is_refreshed(self):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return b'{"usage": {"completion_tokens": 300}}'

        cache_path = Path(tempfile.mkdtemp()) / "tps.json"
        cache_path.write_text(
            '{"nvidia/stale": {"tps": 5.0, "measured_at": %f, "model": "stale"}}'
            % (MODULE.time.time() - 7200),
            encoding="utf-8",
        )
        with patch("model_router.urllib.request.urlopen", return_value=FakeResponse()), patch.object(
            MODULE, "TPS_CACHE_PATH", cache_path
        ):
            tps, source = MODULE.probe_tps(self._free("stale"), MODULE.DEFAULT_CONFIG)
        self.assertEqual("probe", source)
        self.assertGreater(tps, 5.0)

    def test_probe_request_requests_substantial_generation(self):
        captured = {}

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return b'{"usage": {"completion_tokens": 300}}'

        def fake_urlopen(request, timeout=None):
            captured["url"] = request.full_url
            captured["body"] = MODULE.json.loads(request.data.decode())
            captured["headers"] = dict(request.headers)
            return FakeResponse()

        cache_path = Path(tempfile.mkdtemp()) / "tps.json"
        with patch("model_router.urllib.request.urlopen", side_effect=fake_urlopen), patch.object(
            MODULE, "TPS_CACHE_PATH", cache_path
        ):
            tps, source = MODULE.probe_tps(self._free("big-output"), MODULE.DEFAULT_CONFIG)
        self.assertEqual("https://integrate.api.nvidia.com/v1/chat/completions", captured["url"])
        prompt = captured["body"]["messages"][0]["content"]
        self.assertIn("one thousand characters", prompt)
        self.assertGreaterEqual(captured["body"]["max_tokens"], 300)
        self.assertFalse(captured["body"]["stream"])
        self.assertEqual(
            "application/json", next(value for key, value in captured["headers"].items() if key.lower() == "content-type")
        )
        self.assertIsNotNone(tps)
        self.assertEqual("probe", source)

    def test_probe_endpoint_falls_back_to_candidate_api_url(self):
        captured = {}

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return b'{"usage": {"completion_tokens": 300}}'

        def fake_urlopen(request, timeout=None):
            captured["url"] = request.full_url
            return FakeResponse()

        config = copy.deepcopy(MODULE.DEFAULT_CONFIG)
        del config["providers"]["nvidia"]["probeEndpoint"]
        candidate = self._free("via-api")
        candidate.api_url = "https://example.com/v1"
        cache_path = Path(tempfile.mkdtemp()) / "tps.json"
        with patch("model_router.urllib.request.urlopen", side_effect=fake_urlopen), patch.object(
            MODULE, "TPS_CACHE_PATH", cache_path
        ):
            tps, source = MODULE.probe_tps(candidate, config)
        self.assertEqual("https://example.com/v1/chat/completions", captured["url"])
        self.assertIsNotNone(tps)
        self.assertEqual("probe", source)

    def test_probe_uses_auth_store_key(self):
        captured = {}

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return b'{"usage": {"completion_tokens": 300}}'

        def fake_urlopen(request, timeout=None):
            captured["headers"] = dict(request.headers)
            return FakeResponse()

        auth_path = Path(tempfile.mkdtemp()) / "auth.json"
        auth_path.write_text('{"nvidia": {"type": "api", "key": "nv-test"}}', encoding="utf-8")
        cache_path = Path(tempfile.mkdtemp()) / "tps.json"
        with patch("model_router.urllib.request.urlopen", side_effect=fake_urlopen), patch.object(
            MODULE, "TPS_CACHE_PATH", cache_path
        ), patch.object(MODULE, "AUTH_STORE_PATH", auth_path), patch.dict(
            MODULE.os.environ, {"NVIDIA_API_KEY": ""}
        ):
            tps, source = MODULE.probe_tps(self._free("auth-key"), MODULE.DEFAULT_CONFIG)
        self.assertIsNotNone(tps)
        self.assertEqual("probe", source)
        self.assertEqual("Bearer nv-test", captured["headers"].get("Authorization"))

    def test_probe_uses_kilo_config_api_key(self):
        captured = {}

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return b'{"usage": {"completion_tokens": 300}}'

        def fake_urlopen(request, timeout=None):
            captured["headers"] = dict(request.headers)
            return FakeResponse()

        config_path = Path(tempfile.mkdtemp()) / "kilo.jsonc"
        config_path.write_text(
            '// kilo config\n{\n  "provider": {\n    "openrouter": {\n      "options": { "apiKey": "sk-or-test" }\n    }\n  }\n}\n',
            encoding="utf-8",
        )
        cache_path = Path(tempfile.mkdtemp()) / "tps.json"
        with patch("model_router.urllib.request.urlopen", side_effect=fake_urlopen), patch.object(
            MODULE, "TPS_CACHE_PATH", cache_path
        ), patch.object(MODULE, "KILO_CONFIG_PATHS", (config_path,)), patch.dict(
            MODULE.os.environ, {"OPENROUTER_API_KEY": ""}
        ):
            tps, source = MODULE.probe_tps(self._free_route("openrouter/foo:free"), MODULE.DEFAULT_CONFIG)
        self.assertIsNotNone(tps)
        self.assertEqual("probe", source)
        self.assertEqual("Bearer sk-or-test", captured["headers"].get("Authorization"))

    def test_free_route_on_paid_provider_is_probed(self):
        free_route = self._free_route("openrouter/foo:free")
        with patch("model_router.probe_tps", return_value=(80.0, "probe")) as probe:
            selected, warnings = MODULE.select_with_tps_guard(
                [free_route], MODULE.DEFAULT_PROFILES["coding"], self._probe_config(), False
            )
        probe.assert_called_once()
        self.assertEqual("openrouter/foo:free", selected.route)
        self.assertEqual([], warnings)

    def test_probe_timeout_scales_with_expected_output_and_min_tps(self):
        captured = []

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                return b'{"usage": {"completion_tokens": 300}}'

        def fake_urlopen(request, timeout=None):
            captured.append(timeout)
            return FakeResponse()

        cache_path = Path(tempfile.mkdtemp()) / "tps.json"
        with patch("model_router.urllib.request.urlopen", side_effect=fake_urlopen), patch.object(
            MODULE, "TPS_CACHE_PATH", cache_path
        ):
            MODULE.probe_tps(self._free("timed"), MODULE.DEFAULT_CONFIG)
            MODULE.probe_tps(self._free("timed2"), self._probe_config(minTps=5))
        self.assertEqual([50.0, 60.0], captured)

    def test_probe_timeout_caches_zero_and_is_reused(self):
        def fake_urlopen(request, timeout=None):
            raise TimeoutError("timed out")

        cache_path = Path(tempfile.mkdtemp()) / "tps.json"
        with patch("model_router.urllib.request.urlopen", side_effect=fake_urlopen), patch.object(
            MODULE, "TPS_CACHE_PATH", cache_path
        ):
            tps, source = MODULE.probe_tps(self._free("slowpoke"), MODULE.DEFAULT_CONFIG)
            second_tps, second_source = MODULE.probe_tps(self._free("slowpoke"), MODULE.DEFAULT_CONFIG)
        self.assertEqual(0.0, tps)
        self.assertEqual("timeout", source)
        entry = MODULE.json.loads(cache_path.read_text())["nvidia/slowpoke"]
        self.assertEqual(0.0, entry["tps"])
        self.assertEqual(0.0, second_tps)
        self.assertEqual("cache", second_source)

    def test_timed_out_route_is_excluded_from_selection(self):
        free_route = self._free("slowpoke")
        paid_fallback = self._paid("fallback")
        with patch("model_router.probe_tps", return_value=(0.0, "timeout")):
            selected, warnings = MODULE.select_with_tps_guard(
                [free_route, paid_fallback], MODULE.DEFAULT_PROFILES["coding"], self._probe_config(), False
            )
        self.assertEqual("openai/fallback", selected.route)
        self.assertTrue(any("below" in warning for warning in warnings))

    def test_transient_probe_error_is_not_cached(self):
        def fake_urlopen(request, timeout=None):
            raise OSError("connection refused")

        cache_path = Path(tempfile.mkdtemp()) / "tps.json"
        with patch("model_router.urllib.request.urlopen", side_effect=fake_urlopen), patch.object(
            MODULE, "TPS_CACHE_PATH", cache_path
        ):
            tps, source = MODULE.probe_tps(self._free("flaky"), MODULE.DEFAULT_CONFIG)
        self.assertIsNone(tps)
        self.assertIn("probe failed", source)
        self.assertFalse(cache_path.exists())


if __name__ == "__main__":
    unittest.main()
