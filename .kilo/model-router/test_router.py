import importlib.util
import sys
import unittest
from pathlib import Path

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
        command = MODULE.build_kilo_command(args, {"route": "openai/example"})
        self.assertEqual(
            ["kilo", "--model", "openai/example", "--prompt", "Review the docs"],
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
        result = {"route": "opencode-go/gpt-5.6-luna", "variant": "xhigh"}
        command = MODULE.build_kilo_command(args, result)
        self.assertEqual(
            ["kilo", "--model", "opencode-go/gpt-5.6-luna", "--agent", "build", "--prompt", "Review the docs"],
            command,
        )
        content = MODULE.tui_variant_config(args, result)
        self.assertIsNotNone(content)
        config = MODULE.json.loads(content)
        self.assertEqual("opencode-go/gpt-5.6-luna", config["agent"]["build"]["model"])
        self.assertEqual("xhigh", config["agent"]["build"]["variant"])

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

    def test_task_profile_inference_escalates_trading_work(self):
        self.assertEqual("critical", MODULE.infer_profile("Review the trading order execution path"))

    def test_task_profile_inference_uses_stronger_review_profile(self):
        self.assertEqual("review", MODULE.infer_profile("Audit the documentation and agent instructions"))

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


if __name__ == "__main__":
    unittest.main()
