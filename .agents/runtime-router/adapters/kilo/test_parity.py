"""Deterministic ARR/Kraken parity matrix; no Kilo, network, or credentials."""

from __future__ import annotations

import json
import time
import unittest
from pathlib import Path

from agent_runtime_router import Availability, Candidate, CostClass, QuotaStatus, RoutingPolicy, TaskRequest
from agent_runtime_router.harnesses.target import TargetPolicyConfig, route_with_target_policy
from agent_runtime_router.quota import QuotaEvidence, QuotaEvidenceStatus
from agent_runtime_router.throughput import TpsMeasurement, TpsStatus


ROOT = Path(__file__).resolve().parents[4]


def candidate(model: str, *, billing: str, quality: float | None = 50, metrics: dict[str, float] | None = None, quota: QuotaStatus = QuotaStatus.AVAILABLE, context: int | None = 128000) -> Candidate:
    provider = model.split("/", 1)[0]
    cost = CostClass.FREE if billing == "free" else CostClass.PAID if billing != "unknown" else CostClass.UNKNOWN
    return Candidate(provider, model.split("/", 1)[1], frozenset({"code", "reasoning", "tool_call"}), Availability.AVAILABLE, cost, quota, context, quality=quality, billing=billing, reasoning=True, tool_call=True, quality_metrics=metrics)


class KrakenParityMatrixTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.policy = TargetPolicyConfig.from_mapping(json.loads((ROOT / ".agents/runtime-router/policy.json").read_text()))

    def test_secondary_threshold_rejects_cheap_candidate(self) -> None:
        cheap = candidate("openrouter/cheap", billing="paid", metrics={"artificial_analysis_agentic_index": 10})
        expensive = candidate("openrouter/expensive", billing="paid", metrics={"artificial_analysis_agentic_index": 25})
        task = TaskRequest("coding", frozenset({"code"}), 32000, None, None, quality_minimum=20, secondary_thresholds={"artificial_analysis_agentic_index": 15})
        decision = route_with_target_policy(task, (cheap, expensive), self.policy)
        self.assertEqual("openrouter/expensive", decision.selected.candidate_id)

    def test_free_tps_is_mandatory_but_paid_is_fallback(self) -> None:
        free = candidate("openrouter/free:free", billing="free")
        paid = candidate("openrouter/paid", billing="paid")
        task = TaskRequest("routine", frozenset({"code"}), 32000, None, None)
        decision = route_with_target_policy(task, (free, paid), self.policy)
        self.assertEqual("openrouter/paid", decision.selected.candidate_id)

    def test_free_tps_cache_allows_free_route(self) -> None:
        free = candidate("openrouter/free:free", billing="free")
        task = TaskRequest("routine", frozenset({"code"}), 32000, None, None)
        now = time.time()
        measurement = TpsMeasurement(free.candidate_id, "test-tps", "kilo", "catalog", TpsStatus.MEASURED, now, now + 3600, tps=25)
        policy = TargetPolicyConfig.from_mapping({**json.loads((ROOT / ".agents/runtime-router/policy.json").read_text()), "routing_policy": {**json.loads((ROOT / ".agents/runtime-router/policy.json").read_text())["routing_policy"], "min_free_tps": 20}})
        decision = route_with_target_policy(task, (free,), policy, tps_measurements={free.candidate_id: measurement}, now=now)
        self.assertEqual(free.candidate_id, decision.selected.candidate_id)

    def test_positive_payg_balance_is_eligible_after_free(self) -> None:
        payg = candidate("openrouter/payg", billing="payg")
        task = TaskRequest("routine", frozenset({"code"}), 32000, None, None)
        now = time.time()
        quota = QuotaEvidence(payg.candidate_id, payg.provider, "openrouter-account", "fixture", now, now + 300, QuotaEvidenceStatus.VERIFIED, QuotaStatus.AVAILABLE, remaining_balance=3.70, harness_id="kilo")
        decision = route_with_target_policy(task, (payg,), self.policy, quota_evidence={payg.candidate_id: quota}, now=now)
        self.assertEqual(payg.candidate_id, decision.selected.candidate_id)

    def test_blacklist_is_target_owned_and_pattern_aware(self) -> None:
        blocked = candidate("openrouter/anthropic/claude-3", billing="paid")
        allowed = candidate("openrouter/other", billing="paid")
        task = TaskRequest("routine", frozenset({"code"}), 32000, None, None)
        decision = route_with_target_policy(task, (blocked, allowed), self.policy)
        self.assertEqual(allowed.candidate_id, decision.selected.candidate_id)


if __name__ == "__main__":
    unittest.main()
