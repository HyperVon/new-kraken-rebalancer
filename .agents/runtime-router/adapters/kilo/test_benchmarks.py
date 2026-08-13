"""Offline tests for target-owned benchmark evidence."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from agent_runtime_router import Availability, Candidate, CostClass, QuotaStatus

from benchmarks import _NoRedirect, apply_benchmark_quality


def _candidate() -> Candidate:
    return Candidate(
        "openrouter",
        "demo:free",
        frozenset({"code"}),
        Availability.AVAILABLE,
        CostClass.FREE,
        QuotaStatus.UNKNOWN,
        128000,
    )


def _policy() -> dict[str, object]:
    return {
        "artificialAnalysis": {
            "enabled": True,
            "apiKeyEnv": "ARR_TEST_AA_KEY",
            "cacheHours": 1,
        },
        "models": {},
    }


class BenchmarkAdapterTests(unittest.TestCase):
    def test_redirects_are_rejected(self) -> None:
        request = urllib.request.Request("https://example.test/models")
        with self.assertRaises(urllib.error.HTTPError):
            _NoRedirect().redirect_request(request, None, 302, "found", {}, "https://other.test/models")

    def test_primary_evidence_is_bound_and_raw_payload_is_not_cached(self) -> None:
        calls: list[str] = []

        def fetcher(*, url: str, headers: dict[str, str], timeout_seconds: float) -> object:
            del headers, timeout_seconds
            calls.append(url)
            return {
                "data": [
                    {
                        "slug": "demo",
                        "name": "Demo",
                        "evaluations": {
                            "intelligence_index": 51,
                            "coding_index": 44,
                            "agentic_index": 33,
                            "secret": "must not be persisted",
                        },
                    }
                ]
            }

        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            previous = os.environ.get("ARR_TEST_AA_KEY")
            os.environ["ARR_TEST_AA_KEY"] = "fixture-key"
            try:
                result = apply_benchmark_quality(
                    (_candidate(),), _policy(), target, allow_network=True, now=100.0, fetcher=fetcher
                )
            finally:
                if previous is None:
                    os.environ.pop("ARR_TEST_AA_KEY", None)
                else:
                    os.environ["ARR_TEST_AA_KEY"] = previous
            self.assertEqual(calls, ["https://artificialanalysis.ai/api/v2/language/models/free?page=1"])
            self.assertEqual(result[0].quality_metrics["artificial_analysis_coding_index"], 44)
            self.assertEqual(result[0].quality_evidence[0].source, "artificial-analysis")
            cache = json.loads((target / ".agents/runtime-router/harnesses/kilo/quality.json").read_text())
            encoded = json.dumps(cache, sort_keys=True)
            self.assertNotIn("must not be persisted", encoded)
            self.assertNotIn("Demo", encoded)

            def unexpected(**kwargs: object) -> object:
                raise AssertionError("fresh cache should avoid another fetch")

            cached = apply_benchmark_quality(
                (_candidate(),), _policy(), target, now=101.0, fetcher=unexpected
            )
            self.assertEqual(cached[0].quality_metrics["artificial_analysis_intelligence_index"], 51)
            self.assertEqual(cached[0].quality_evidence[0].source, "artificial-analysis")

    def test_openrouter_is_explicit_fallback(self) -> None:
        def fetcher(*, url: str, headers: dict[str, str], timeout_seconds: float) -> object:
            del headers, timeout_seconds
            if "artificialanalysis.ai" in url:
                raise OSError("primary unavailable")
            return {
                "data": [
                    {
                        "id": "demo",
                        "benchmarks": {
                            "artificial_analysis": {"coding_index": 41}
                        },
                    }
                ]
            }

        with tempfile.TemporaryDirectory() as directory:
            result = apply_benchmark_quality(
                (_candidate(),), _policy(), Path(directory), allow_network=True, now=100.0, fetcher=fetcher
            )
            self.assertEqual(result[0].quality_metrics["artificial_analysis_coding_index"], 41)
            evidence = result[0].quality_evidence[0]
            self.assertEqual(evidence.source, "openrouter-benchmark")
            self.assertIsNotNone(evidence.fallback)

            cached = apply_benchmark_quality(
                (_candidate(),), _policy(), Path(directory), now=101.0,
                fetcher=lambda **kwargs: (_ for _ in ()).throw(AssertionError("cache miss")),
            )
            self.assertEqual(cached[0].quality_evidence[0].source, "openrouter-benchmark")
            self.assertIsNotNone(cached[0].quality_evidence[0].fallback)


if __name__ == "__main__":
    unittest.main()
