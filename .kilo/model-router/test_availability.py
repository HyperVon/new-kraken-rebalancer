import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("availability.py")
SPEC = importlib.util.spec_from_file_location("model_router_availability", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class AvailabilityTests(unittest.TestCase):
    def test_quota_provider_reports_insufficient_remaining_percent(self):
        status = {
            "providers": [{"id": "openrouter", "available": True}],
            "liveProbes": [{"id": "openrouter", "ok": True}],
        }
        show = {
            "cacheAgeSeconds": 2,
            "providers": {
                "openrouter": {
                    "status": "ok",
                    "entries": [{"percentRemaining": 0.5}],
                }
            },
        }
        result = MODULE._provider_quota("openrouter", status, show, {"openrouter": {"ok": True}}, 300, 1)
        self.assertEqual("insufficient", result["state"])

    def test_failure_classification_and_retry_after(self):
        self.assertEqual("rate_limit", MODULE.failure_kind("HTTP 429 Too Many Requests"))
        self.assertEqual("credits", MODULE.failure_kind("402 payment required: insufficient credits"))
        self.assertEqual("model_eol", MODULE.failure_kind("HTTP 410 Gone: model reached its end of life"))
        self.assertEqual("model_eol", MODULE.failure_kind('{"status":410,"detail":"... is no longer available."}'))
        self.assertIsNone(MODULE.failure_kind("worker returned an empty report"))
        self.assertEqual(60, MODULE.retry_after_seconds("Retry-After: 60"))

    def test_record_failure_persists_only_cooldown_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "availability.json"
            config = {"quota": {"cooldownPath": str(path), "cooldown": {"rateLimitSeconds": 60}}}
            seconds = MODULE.record_failure(config, "openrouter/model", "openrouter", "rate_limit", "429")
            self.assertEqual(60, seconds)
            payload = MODULE.json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual("rate_limit", payload["routes"]["openrouter/model"]["reason"])
            self.assertNotIn("429", MODULE.json.dumps(payload))

    def test_report_contract_failure_uses_provider_cooldown(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "availability.json"
            config = {
                "quota": {
                    "cooldownPath": str(path),
                    "cooldown": {"reportContractSeconds": 90},
                }
            }
            seconds = MODULE.record_failure(config, "openrouter/model", "openrouter", "report_contract", "")
            self.assertEqual(90, seconds)
            payload = MODULE.json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual("report_contract", payload["routes"]["openrouter/model"]["reason"])


if __name__ == "__main__":
    unittest.main()
