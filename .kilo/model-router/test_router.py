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
            "evaluations": {"artificial_analysis_coding_index": quality},
            "artificial_analysis_intelligence_index_cost": {"cost_per_task": {"total_cost": aa_cost}},
        }
        value.aa_match = "configured"
    return value


class RouterTests(unittest.TestCase):
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

    def test_task_profile_inference_escalates_trading_work(self):
        self.assertEqual("critical", MODULE.infer_profile("Review the trading order execution path"))

    def test_free_route_guard_detects_secret_material(self):
        self.assertTrue(MODULE.is_sensitive("Read the API key from .env", "routine"))
        self.assertFalse(MODULE.is_sensitive("Review the public trading engine", "critical"))

    def test_provider_config_counts_as_configured_access(self):
        self.assertEqual({"openrouter"}, MODULE.parse_config_provider_ids({"provider": {"openrouter": {}}}))

    def test_jsonc_provider_config_is_supported(self):
        payload = MODULE.parse_json_text('{"provider": {"openrouter": {"baseURL": "https://example.test",},}, // comment\n}')
        self.assertEqual({"openrouter"}, MODULE.parse_config_provider_ids(payload))


if __name__ == "__main__":
    unittest.main()
