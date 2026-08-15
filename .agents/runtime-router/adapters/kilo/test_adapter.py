"""Credential-free target adapter and policy parity tests."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch
from pathlib import Path

from agent_runtime_router import (
    Candidate, EffortLevel, EffortProfile, Availability, CostClass, KILO_READINESS_SOURCE,
    QuotaStatus, READINESS_CAPABILITY, ReadinessCache, ReadinessMeasurement,
    ReadinessStatus, TaskRequest,
)
from agent_runtime_router.harnesses.target import TargetPolicyConfig, route_with_target_policy
from agent_runtime_router.throughput import TpsMeasurement, TpsStatus
from agent_runtime_router.quota import apply_quota_evidence

from adapter import build_adapter
from catalog import CatalogError, build_candidates, discover_candidates, parse_catalog_output
from gen_discovery import _is_supported_kilo_version, _profile_mapping
from quota import collect_quota_evidence
from run_arr_task import (
    TARGET as RUNNER_TARGET,
    _apply_profile_quality, _load_or_discover, _readiness_digest, _readiness_settings,
    _tps_evidence_digest,
    _remove_failed_readiness_option, _route_with_readiness, _task, _tps_probe_priority,
    _resolve_kilo_alias,
)
from route_subagents import (
    TARGET as SUBAGENT_RUNNER_TARGET,
    _diversity_key,
    _free_only_policy,
    _persist_worker_reports,
    _workflow_uses_distinct_routes,
    _workflow_tracks,
)
from route_subagents import _sanitize_prompt as sanitize_workflow_prompt
from route_subagents import _no_route_payload
from run_arr_task import _sanitize_prompt as sanitize_manual_prompt


ROOT = Path(__file__).resolve().parents[4]
POLICY_DIR = ROOT / ".agents" / "runtime-router"
ADAPTER_DIR = POLICY_DIR / "adapters" / "kilo"


class KrakenKiloAdapterTests(unittest.TestCase):
    def test_entrypoint_defaults_resolve_to_the_target_root(self) -> None:
        self.assertEqual(ROOT, RUNNER_TARGET)
        self.assertEqual(ROOT, SUBAGENT_RUNNER_TARGET)

    def test_catalog_parser_and_target_allowlist(self) -> None:
        raw = json.dumps([
            {"providerID": "openrouter", "id": "free-model:free", "status": "active", "capabilities": {"toolcall": True, "reasoning": True}, "limit": {"context": 128000, "output": 4096}, "variants": {"low": {}, "high": {}}, "benchmarks": {"artificial_analysis": {"coding_index": 50, "agentic_index": 30}}},
            {"providerID": "ollama", "id": "local", "status": "active"},
            {"providerID": "openrouter", "id": "claude-3", "status": "active"},
        ]).encode()
        records = parse_catalog_output("openrouter", raw)
        policy = json.loads((ADAPTER_DIR / "provider-policy.json").read_text())
        candidates = build_candidates(records, policy)
        self.assertEqual([item.candidate_id for item in candidates], ["openrouter/free-model:free"])
        candidate = candidates[0]
        self.assertEqual(candidate.cost_class.value, "free")
        self.assertIn("tool_call", candidate.capabilities)
        self.assertIn(EffortLevel.HIGH, {profile.effort for profile in candidate.effort_profiles})
        self.assertEqual(candidate.quality_metrics["artificial_analysis_coding_index"], 50)

    def test_free_only_provider_drops_positive_catalog_cost(self) -> None:
        records = (
            {
                "providerID": "nvidia",
                "id": "paid-model",
                "status": "active",
                "cost": {"input": 0.2, "output": 0.4},
            },
        )
        policy = json.loads((ADAPTER_DIR / "provider-policy.json").read_text())
        with self.assertRaises(CatalogError):
            build_candidates(records, policy)

    def test_target_billing_overrides_zero_catalog_price(self) -> None:
        records = (
            {
                "providerID": "kilo",
                "id": "kilo-auto/efficient",
                "status": "active",
                "cost": {"input": 0, "output": 0},
                "capabilities": {"toolcall": True, "reasoning": True},
                "limit": {"context": 128000, "output": 4096},
            },
        )
        policy = json.loads((ADAPTER_DIR / "provider-policy.json").read_text())
        candidate = build_candidates(records, policy)[0]
        self.assertEqual("account-priced", candidate.billing)
        self.assertEqual(CostClass.PAID, candidate.cost_class)

    def test_explicit_model_free_marker_overrides_mixed_provider_default(self) -> None:
        records = (
            {
                "providerID": "kilo",
                "id": "tencent/hy3:free",
                "status": "active",
                "isFree": True,
                "cost": {"input": 0, "output": 0},
                "capabilities": {"toolcall": True, "reasoning": True},
                "limit": {"context": 262144, "output": 128000},
                "variants": {"none": {}, "low": {}, "high": {}},
            },
        )
        policy = json.loads((ADAPTER_DIR / "provider-policy.json").read_text())
        candidate = build_candidates(records, policy)[0]
        self.assertEqual("kilo/tencent/hy3:free", candidate.candidate_id)
        self.assertEqual("free", candidate.billing)
        self.assertEqual(CostClass.FREE, candidate.cost_class)
        self.assertEqual({EffortLevel.LOW, EffortLevel.HIGH}, {profile.effort for profile in candidate.effort_profiles})

    def test_positive_price_cannot_be_marked_free_by_contradictory_model_metadata(self) -> None:
        records = (
            {
                "providerID": "kilo",
                "id": "tencent/hy3:free",
                "status": "active",
                "isFree": True,
                "cost": {"input": 0.14, "output": 0.58},
            },
        )
        policy = json.loads((ADAPTER_DIR / "provider-policy.json").read_text())
        with self.assertRaises(CatalogError):
            build_candidates(records, policy)

    def test_hy3_artificial_analysis_alias_is_target_owned(self) -> None:
        policy = json.loads((ADAPTER_DIR / "provider-policy.json").read_text())
        self.assertEqual(["hy3", "hy3-preview"], policy["models"]["kilo/tencent/hy3:free"]["aaSlugs"])

    def test_all_legacy_profiles_are_present(self) -> None:
        profiles = json.loads((ADAPTER_DIR / "profiles.json").read_text())
        expected = {"trivial", "routine", "coding", "complex-coding", "agentic", "architecture", "quick-review", "detailed-review", "critical"}
        self.assertEqual(set(profiles["profiles"]), expected)
        for profile in profiles["profiles"].values():
            self.assertIn("context", profile)
            self.assertIn("input_tokens", profile)
            self.assertIn("output_tokens", profile)

        self.assertEqual(profiles["profiles"]["coding"]["minimum"], 45)
        self.assertEqual(profiles["profiles"]["coding"]["secondary"], {"artificial_analysis_agentic_index": 15})
        self.assertEqual(profiles["profiles"]["complex-coding"]["minimum"] + profiles["profiles"]["complex-coding"]["margin"], 60)
        self.assertEqual(profiles["profiles"]["agentic"]["secondary"], {"artificial_analysis_coding_index": 15})
        self.assertEqual(profiles["profiles"]["architecture"]["minimum"], 40)
        self.assertEqual(
            profiles["profiles"]["architecture"]["variantPreference"][0],
            "max",
        )
        self.assertEqual(profiles["profiles"]["quick-review"]["context"], 96000)
        self.assertEqual(profiles["profiles"]["detailed-review"]["secondary"]["artificial_analysis_agentic_index"], 25)
        self.assertEqual(profiles["profiles"]["critical"]["variantPreference"][0], "max")

    def test_profile_metric_is_applied_per_track(self) -> None:
        candidate = Candidate(
            "openrouter",
            "demo",
            frozenset({"code"}),
            Availability.AVAILABLE,
            CostClass.PAID,
            QuotaStatus.AVAILABLE,
            128000,
            quality_metrics={
                "artificial_analysis_coding_index": 41,
                "artificial_analysis_intelligence_index": 52,
            },
            effort_profiles=(),
        )
        profiles = json.loads((ADAPTER_DIR / "profiles.json").read_text())["profiles"]
        coding = _apply_profile_quality((candidate,), profiles["coding"])[0]
        review = _apply_profile_quality((candidate,), profiles["detailed-review"])[0]
        self.assertEqual(coding.quality, 41)
        self.assertEqual(review.quality, 52)

    def test_policy_round_trip_and_blacklist_parity(self) -> None:
        raw = json.loads((POLICY_DIR / "policy.json").read_text())
        policy = TargetPolicyConfig.from_mapping(raw)
        self.assertTrue(policy.routing_policy.allow_free)
        self.assertTrue(policy.routing_policy.min_free_tps >= 20)
        self.assertEqual(300.0, policy.routing_policy.tps_probe_timeout_seconds)
        self.assertIn("ollama", policy.routing_policy.denied_providers)
        self.assertTrue(any("claude" in entry for entry in policy.blacklist))

    def test_adapter_loads_pointer_backed_profile(self) -> None:
        executable = shutil.which("kilo")
        if executable is None:
            self.skipTest("Kilo is not installed")
        profile = ROOT / ".agents" / "runtime-router" / "harnesses" / "kilo" / "profile.json"
        if not profile.is_file():
            self.skipTest("ignored Kilo harness profile has not been generated")
        adapter = build_adapter(ROOT, executable)
        self.assertEqual("kilo", adapter.describe().harness_id)

    def test_generator_builds_a_valid_namespaced_kilo_profile(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            (target / "AGENTS.md").write_text("rules\n", encoding="utf-8")
            (target / ".agents" / "skills").mkdir(parents=True)
            (target / ".kilo" / "agent").mkdir(parents=True)
            profile = _profile_mapping(target, "7.4.21")

        self.assertEqual(1, profile["schema_version"])
        self.assertEqual("kilo", profile["harness_id"])
        self.assertEqual("7.4.21", profile["version"])
        self.assertEqual(["AGENTS.md"], profile["instruction_paths"])
        self.assertEqual([".agents/skills", ".kilo/agent"], profile["skill_paths"])
        self.assertEqual(
            {"model_listing", "native_launch"},
            {item["capability"] for item in profile["capabilities"]},
        )

    def test_generator_accepts_compatible_kilo_patch_releases_only(self) -> None:
        self.assertTrue(_is_supported_kilo_version("7.4.21"))
        self.assertTrue(_is_supported_kilo_version("7.4.22"))
        self.assertTrue(_is_supported_kilo_version("7.4.99"))
        self.assertFalse(_is_supported_kilo_version("7.4.20"))
        self.assertFalse(_is_supported_kilo_version("7.5.0"))
        self.assertFalse(_is_supported_kilo_version("8.0.0"))
        self.assertFalse(_is_supported_kilo_version("7.4.22-dev"))

    def test_generator_requires_the_observed_kilo_help_contract(self) -> None:
        from gen_discovery import _verify_help_contract

        outputs = iter(
            (
                subprocess.CompletedProcess((), 0, b"--model --agent --format --variant", None),
                subprocess.CompletedProcess((), 0, b"--verbose", None),
            )
        )
        with patch("gen_discovery.subprocess.run", side_effect=lambda *args, **kwargs: next(outputs)):
            _verify_help_contract(Path("/absolute/kilo"))

        with patch(
            "gen_discovery.subprocess.run",
            return_value=subprocess.CompletedProcess((), 0, b"--model --agent --format", None),
        ):
            with self.assertRaises(SystemExit):
                _verify_help_contract(Path("/absolute/kilo"))

    def test_launch_prompts_are_normalized_before_bounded_argv_binding(self) -> None:
        raw = "First line\nSecond line\twith a separator\x00"
        self.assertEqual("First line Second line with a separator", sanitize_manual_prompt(raw))
        self.assertEqual("First line Second line with a separator", sanitize_workflow_prompt(raw))

    def test_registered_workflow_tracks_have_argv_safe_prompts(self) -> None:
        tracks = _workflow_tracks(
            ADAPTER_DIR / "workflows.json",
            "comprehensive-quality-overhaul",
            "Line one\nLine two\twith a detail",
        )
        self.assertEqual(5, len(tracks))
        self.assertTrue(all("\n" not in track.task and "\t" not in track.task for track in tracks))

    def test_legacy_workflow_presets_are_registered(self) -> None:
        workflows = json.loads((ADAPTER_DIR / "workflows.json").read_text())["workflows"]
        expected = {
            "documentation-review",
            "comprehensive-quality-overhaul",
            "documentation-adversarial-review",
            "adversarial-pr-review",
            "architecture-review",
            "continuous-quality",
            "continuous-improvement",
            "autonomous-code-optimizer",
            "ai-slop-detector",
            "complex-code-comments",
            "dependency-upgrade",
            "rules-and-skills-audit",
            "skill-reviewer",
        }
        self.assertTrue(expected.issubset(workflows))

    def test_comprehensive_quality_workflow_has_five_read_only_tracks(self) -> None:
        workflows = json.loads((ADAPTER_DIR / "workflows.json").read_text())["workflows"]
        tracks = workflows["comprehensive-quality-overhaul"]
        self.assertEqual(
            {track["id"] for track in tracks},
            {"wt-code", "wt-docs", "wt-skills", "wt-tests", "wt-arch"},
        )
        self.assertTrue(all(track.get("read_only") is True for track in tracks))
        self.assertTrue(all(track.get("files") and track.get("task") for track in tracks))
        self.assertEqual("architecture", next(track for track in tracks if track["id"] == "wt-arch")["profile"])

    def test_workflow_diversity_treats_free_alias_as_same_model_family(self) -> None:
        free = Candidate(
            "kilo", "tencent/hy3:free", frozenset({"code"}),
            Availability.AVAILABLE, CostClass.FREE, QuotaStatus.AVAILABLE, 128000,
        )
        paid = Candidate(
            "kilo", "tencent/hy3", frozenset({"code"}),
            Availability.AVAILABLE, CostClass.PAID, QuotaStatus.AVAILABLE, 128000,
        )
        self.assertEqual(_diversity_key(free), _diversity_key(paid))

    def test_named_workflows_default_to_distinct_model_families(self) -> None:
        self.assertTrue(_workflow_uses_distinct_routes("comprehensive-quality-overhaul"))
        self.assertTrue(_workflow_uses_distinct_routes("architecture-review"))
        self.assertFalse(_workflow_uses_distinct_routes(None))

    def test_workflow_reports_are_persisted_as_bounded_paths(self) -> None:
        class FakeResult:
            track = "wt-arch"
            report = "redacted worker summary"

            @staticmethod
            def to_execution_report(*, adapter_status: str) -> Any:
                return SimpleNamespace(
                    to_dict=lambda: {
                        "schema_version": 1,
                        "track": "wt-arch",
                        "status": "SUCCEEDED",
                        "adapter_status": adapter_status,
                    }
                )

        with tempfile.TemporaryDirectory() as directory:
            relative, results = _persist_worker_reports(
                Path(directory), "comprehensive-quality-overhaul", [FakeResult()]
            )
            report_path = Path(directory) / results[0]["report_path"]
            self.assertTrue(report_path.is_file())
            self.assertEqual("redacted worker summary", report_path.read_text(encoding="utf-8"))
            self.assertTrue((Path(directory) / relative / "manifest.json").is_file())
            self.assertEqual(64, len(results[0]["report_sha256"]))

    def test_receipt_runner_exposes_free_only_workflow_launcher(self) -> None:
        runner = ROOT / ".agents" / ".agent-runtime-router" / "run.py"
        if not runner.is_file():
            self.skipTest("ARR harness not installed (run.py missing)")
        launcher = ADAPTER_DIR / "route_subagents.py"
        result = subprocess.run(
            [sys.executable, str(runner), "--python", str(launcher), "--help"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--free-only", result.stdout)

    def test_evidence_preparation_requires_a_separate_explicit_approval(self) -> None:
        runner = ROOT / ".agents" / ".agent-runtime-router" / "run.py"
        if not runner.is_file():
            self.skipTest("ARR harness not installed (run.py missing)")
        single = subprocess.run(
            [
                sys.executable,
                str(runner),
                "--python",
                str(ADAPTER_DIR / "run_arr_task.py"),
                "--prepare-evidence",
                "No worker should start.",
            ],
            capture_output=True,
            text=True,
            timeout=10,
        )
        workflow = subprocess.run(
            [
                sys.executable,
                str(runner),
                "--python",
                str(ADAPTER_DIR / "route_subagents.py"),
                "--workflow",
                "comprehensive-quality-overhaul",
                "--prepare-evidence",
                "No workers should start.",
            ],
            capture_output=True,
            text=True,
            timeout=10,
        )
        expected = {
            "error_code": "evidence_approval_required",
            "status": "INCOMPLETE",
        }
        self.assertEqual(2, single.returncode)
        self.assertEqual(expected, json.loads(single.stdout))
        self.assertEqual(2, workflow.returncode)
        self.assertEqual(expected, json.loads(workflow.stdout))

    def test_free_only_policy_rejects_paid_and_unknown_cost(self) -> None:
        policy = TargetPolicyConfig.from_mapping(
            json.loads((POLICY_DIR / "policy.json").read_text())
        )
        restricted = _free_only_policy(policy)
        self.assertTrue(restricted.routing_policy.allow_free)
        self.assertFalse(restricted.routing_policy.allow_paid)
        self.assertFalse(restricted.routing_policy.allow_unknown_cost)
        task = TaskRequest("free-only", frozenset({"code"}), 1, None, None)
        free = Candidate(
            "kilo", "free", frozenset({"code"}), Availability.AVAILABLE,
            CostClass.FREE, QuotaStatus.AVAILABLE, 128000, billing="free", tps=30,
        )
        paid = Candidate(
            "kilo", "paid", frozenset({"code"}), Availability.AVAILABLE,
            CostClass.PAID, QuotaStatus.AVAILABLE, 128000, billing="payg",
        )
        unknown = Candidate(
            "kilo", "unknown", frozenset({"code"}), Availability.AVAILABLE,
            CostClass.UNKNOWN, QuotaStatus.AVAILABLE, 128000, billing="unknown",
        )
        now = time.time()
        measurement = TpsMeasurement(
            free.candidate_id, "test", "kilo", "a" * 64, TpsStatus.MEASURED,
            now, now + 600, 30,
        )
        decision = route_with_target_policy(
            task, (paid, unknown, free), restricted,
            tps_measurements={free.candidate_id: measurement}, now=now,
        )
        self.assertIsNotNone(decision.selected)
        self.assertEqual(free.candidate_id, decision.selected.candidate_id)

    def test_workflow_launcher_reports_missing_catalog_structurally(self) -> None:
        launcher = ADAPTER_DIR / "route_subagents.py"
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            for relative in (
                ".agents/runtime-router/policy.json",
                ".agents/runtime-router/adapters/kilo/provider-policy.json",
                ".agents/runtime-router/adapters/kilo/profiles.json",
                ".agents/runtime-router/adapters/kilo/workflows.json",
            ):
                destination = target / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / relative, destination)
            result = subprocess.run(
                [
                    sys.executable,
                    str(launcher),
                    "--target",
                    str(target),
                    "--workflow",
                    "comprehensive-quality-overhaul",
                    "--free-only",
                    "--task",
                    "offline launcher preflight",
                ],
                capture_output=True,
                text=True,
                timeout=10,
            )
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("Traceback", result.stdout + result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual(payload, {"error_code": "catalog_missing_or_unusable", "status": "INCOMPLETE"})

    def test_no_route_report_preserves_track_and_rejection_reasons(self) -> None:
        policy = TargetPolicyConfig.from_mapping(
            json.loads((POLICY_DIR / "policy.json").read_text())
        )
        restricted = _free_only_policy(policy)
        paid = Candidate(
            "openrouter", "paid", frozenset({"code"}), Availability.AVAILABLE,
            CostClass.PAID, QuotaStatus.AVAILABLE, 128000, billing="payg",
        )
        task = TaskRequest("no-route", frozenset({"code"}), 1, None, None)
        decision = route_with_target_policy(task, (paid,), restricted)
        from agent_runtime_router.workflow import Track

        payload = _no_route_payload(
            Track("tests-docs", "private prompt must not be emitted", "routine", "code", (), True),
            decision,
        )
        self.assertEqual("no_route", payload["error_code"])
        self.assertEqual("tests-docs", payload["track"])
        self.assertEqual(1, payload["candidate_count"])
        self.assertTrue(payload["rejection_counts"])
        self.assertNotIn("private prompt", json.dumps(payload))

    def test_missing_quota_plugin_does_not_break_free_candidates(self) -> None:
        candidates = (Candidate("openrouter", "demo:free", frozenset({"code"}), __import__("agent_runtime_router").Availability.AVAILABLE, __import__("agent_runtime_router").CostClass.FREE, __import__("agent_runtime_router").QuotaStatus.UNKNOWN, 128000, billing="free"),)
        provider_policy = json.loads((ADAPTER_DIR / "provider-policy.json").read_text())
        with tempfile.TemporaryDirectory() as directory:
            old = __import__("os").environ.pop("OPENCODE_QUOTA_COMMAND", None)
            try:
                self.assertEqual(collect_quota_evidence(candidates, provider_policy), {})
            finally:
                if old is not None:
                    __import__("os").environ["OPENCODE_QUOTA_COMMAND"] = old

    def test_unapproved_quota_plan_never_invokes_plugin(self) -> None:
        candidates = (
            Candidate(
                "openrouter", "demo:free", frozenset({"code"}),
                Availability.AVAILABLE, CostClass.FREE, QuotaStatus.UNKNOWN,
                128000, billing="free",
            ),
        )
        provider_policy = json.loads((ADAPTER_DIR / "provider-policy.json").read_text())
        with patch.dict(os.environ, {"OPENCODE_QUOTA_COMMAND": "/bin/false"}), patch(
            "quota._run_plugin"
        ) as run_plugin:
            self.assertEqual(collect_quota_evidence(candidates, provider_policy), {})
            run_plugin.assert_not_called()

    def test_refresh_requires_approval_before_discovery(self) -> None:
        policy = TargetPolicyConfig.from_mapping(
            json.loads((POLICY_DIR / "policy.json").read_text())
        )
        with tempfile.TemporaryDirectory() as directory, patch(
            "run_arr_task.discover_candidates"
        ) as discover:
            target = Path(directory)
            result = _load_or_discover(
                target,
                target / "catalog-cache.json",
                "/bin/true",
                {},
                policy,
                refresh=True,
                approve=False,
            )
            self.assertIsNone(result)
            discover.assert_not_called()

    def test_low_subscription_quota_is_exhausted_and_payg_is_last_resort(self) -> None:
        subscription = Candidate(
            "opencode-go", "subscription", frozenset({"code"}),
            Availability.AVAILABLE, CostClass.PAID, QuotaStatus.UNKNOWN, 128000,
            billing="subscription/account-priced",
        )
        payg = Candidate(
            "openrouter", "cheap", frozenset({"code"}), Availability.AVAILABLE,
            CostClass.PAID, QuotaStatus.UNKNOWN, 128000, billing="payg",
        )
        provider_policy = json.loads((ADAPTER_DIR / "provider-policy.json").read_text())
        payload = {
            "providers": {
                "opencode-go": {"remainingPercent": 0.5, "timestamp": 100},
                "openrouter": {"remainingBalance": 3.70, "currency": "USD", "timestamp": 100},
            }
        }
        with patch.dict(os.environ, {"OPENCODE_QUOTA_COMMAND": "/bin/true"}), patch(
            "quota._run_plugin", return_value=payload
        ):
            evidence = collect_quota_evidence(
                (subscription, payg), provider_policy, now=100, approve=True
            )
        prepared = apply_quota_evidence(
            (subscription, payg), evidence, now=101, harness_id="kilo"
        )
        self.assertEqual(QuotaStatus.EXHAUSTED, prepared[0].quota_status)
        self.assertEqual(QuotaStatus.AVAILABLE, prepared[1].quota_status)
        self.assertEqual(3.70, prepared[1].quota_balance)

    def test_task_requires_cached_tool_readiness_separately_from_tps(self) -> None:
        profile = {"context": 1}
        request = _task("routine", profile, "smoke", candidate=None, sensitive=False)
        self.assertIn(READINESS_CAPABILITY, request.required_capabilities)
        self.assertNotIn("tps", request.required_capabilities)

    def test_kilo_native_alias_is_resolved_only_when_the_catalog_match_is_unique(self) -> None:
        task = _task(
            "routine", {"context": 1}, "smoke",
            candidate="kilo/cohere/north-mini-code:free", sensitive=False,
        )
        canonical = Candidate(
            "openrouter", "cohere/north-mini-code:free", frozenset({"code"}),
            Availability.AVAILABLE, CostClass.FREE, QuotaStatus.UNKNOWN, 128000,
            billing="free",
        )
        resolved = _resolve_kilo_alias(task, (canonical,))
        self.assertEqual("openrouter", resolved.pinned_provider)
        self.assertEqual(canonical.model, resolved.pinned_model)
        ambiguous = Candidate(
            "other", canonical.model, frozenset({"code"}), Availability.AVAILABLE,
            CostClass.FREE, QuotaStatus.UNKNOWN, 128000, billing="free",
        )
        self.assertEqual(task, _resolve_kilo_alias(task, (canonical, ambiguous)))

    def test_readiness_probe_settings_are_target_owned_and_bounded(self) -> None:
        provider_policy = json.loads((ADAPTER_DIR / "provider-policy.json").read_text())
        self.assertEqual((5, 86400.0, 300.0, 300.0), _readiness_settings(provider_policy))
        self.assertEqual((2, 86400.0, 300.0, 300.0), _readiness_settings(provider_policy, requested_max_probes=2))
        with self.assertRaises(CatalogError):
            _readiness_settings(provider_policy, requested_max_probes=6)

    def test_tps_probe_priority_uses_arr_route_order_without_bypassing_gate(self) -> None:
        free = Candidate(
            "openrouter", "free:free", frozenset({"code"}),
            Availability.AVAILABLE, CostClass.FREE, QuotaStatus.UNKNOWN, 128000,
            billing="free", quality=100,
        )
        paid = Candidate(
            "openrouter", "paid", frozenset({"code"}),
            Availability.AVAILABLE, CostClass.PAID, QuotaStatus.AVAILABLE, 128000,
            billing="payg", quality=200,
        )
        policy = TargetPolicyConfig.from_mapping(
            json.loads((POLICY_DIR / "policy.json").read_text())
        )
        task = TaskRequest(
            "priority", frozenset({"code", READINESS_CAPABILITY}), 1, None, None
        )
        priority = _tps_probe_priority(
            task, (paid, free), policy
        )
        self.assertEqual((free.candidate_id,), tuple(item.candidate_id for item in priority))
        # The real policy still rejects the same unmeasured free candidate.
        self.assertIsNone(
            __import__("agent_runtime_router.harnesses.target", fromlist=["route_with_target_policy"])
            .route_with_target_policy(task, (free,), policy).selected
        )

    def test_failed_readiness_variant_keeps_other_effort_options(self) -> None:
        candidate = Candidate(
            "openrouter", "free", frozenset({"code", "tool_call"}),
            Availability.AVAILABLE, CostClass.FREE, QuotaStatus.AVAILABLE,
            128000, billing="free", effort_profiles=(
                __import__("agent_runtime_router", fromlist=["EffortProfile"]).EffortProfile(
                    EffortLevel.LOW, quality=40, variant="low",
                ),
                __import__("agent_runtime_router", fromlist=["EffortProfile"]).EffortProfile(
                    EffortLevel.MEDIUM, quality=40, variant="medium",
                ),
            ),
        )
        remaining = _remove_failed_readiness_option(
            (candidate,), candidate.candidate_id, EffortLevel.MEDIUM, "medium"
        )
        self.assertEqual((EffortLevel.LOW,), tuple(item.effort for item in remaining[0].effort_profiles))
        self.assertEqual(
            (), _remove_failed_readiness_option(
                remaining, candidate.candidate_id, EffortLevel.LOW, "low"
            )
        )

    def test_readiness_failure_falls_back_to_a_measured_variant(self) -> None:
        policy = TargetPolicyConfig.from_mapping(
            json.loads((POLICY_DIR / "policy.json").read_text())
        )
        candidate = Candidate(
            "openrouter", "free", frozenset({"code", "tool_call"}),
            Availability.AVAILABLE, CostClass.FREE, QuotaStatus.AVAILABLE,
            128000, billing="free", effort_profiles=(
                EffortProfile(EffortLevel.LOW, quality=40, variant="low"),
                EffortProfile(EffortLevel.MEDIUM, quality=40, variant="medium"),
            ),
        )
        task = TaskRequest(
            "readiness-fallback", frozenset({"code", READINESS_CAPABILITY}),
            1, None, None,
        )
        now = time.time()
        medium_digest = _readiness_digest(
            catalog_digest="a" * 64, candidate=candidate,
            effort=EffortLevel.MEDIUM, variant="medium", executable="/opt/kilo",
        )
        low_digest = _readiness_digest(
            catalog_digest="b" * 64, candidate=candidate,
            effort=EffortLevel.LOW, variant="low", executable="/opt/kilo",
        )
        failed = ReadinessMeasurement(
            candidate.candidate_id, KILO_READINESS_SOURCE, "kilo", medium_digest,
            ReadinessStatus.FAILED, now, now + 600, 1.0, False, False,
            "canary_result_missing",
        )
        ready = ReadinessMeasurement(
            candidate.candidate_id, KILO_READINESS_SOURCE, "kilo", low_digest,
            ReadinessStatus.READY, now, now + 600, 1.0, True, True,
        )
        tps = TpsMeasurement(
            candidate.candidate_id, "test", "kilo", "t" * 64,
            TpsStatus.MEASURED, now, now + 600, 30,
        )
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory).resolve()
            path = target / ".agents/runtime-router/harnesses/kilo/readiness.json"
            ReadinessCache((failed, ready)).write(path, now=now)
            decision = _route_with_readiness(
                task, (candidate,), policy, quota={}, tps={candidate.candidate_id: tps},
                target=target, adapter=None, executable="/opt/kilo", catalog_digest="c" * 64,
                approve=False, max_probes=3, cache_ttl_seconds=600,
                failure_cache_ttl_seconds=30, timeout_seconds=300,
            )
        self.assertEqual(candidate.candidate_id, decision.selected.candidate_id)
        self.assertEqual(EffortLevel.LOW, decision.selected_effort)

    def test_cached_readiness_routes_around_exhausted_subscription_to_payg(self) -> None:
        subscription = Candidate(
            "opencode-go", "subscription", frozenset({"code"}),
            Availability.AVAILABLE, CostClass.PAID, QuotaStatus.EXHAUSTED, 128000,
            billing="subscription/account-priced",
        )
        payg = Candidate(
            "openrouter", "cheap", frozenset({"code"}), Availability.AVAILABLE,
            CostClass.PAID, QuotaStatus.AVAILABLE, 128000, billing="payg",
            quota_balance=3.70,
        )
        raw_policy = json.loads((POLICY_DIR / "policy.json").read_text())
        policy = TargetPolicyConfig.from_mapping(raw_policy)
        task = TaskRequest(
            "route", frozenset({"code", READINESS_CAPABILITY}), 1, None, None
        )
        catalog_digest = "a" * 64
        digest = _readiness_digest(
            catalog_digest=catalog_digest, candidate=payg, effort=None,
            variant=None, executable="/opt/kilo",
        )
        now = time.time()
        ready = ReadinessMeasurement(
            payg.candidate_id, KILO_READINESS_SOURCE, "kilo", digest,
            ReadinessStatus.READY, now, now + 600, 1.0, True, True,
        )
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory).resolve()
            path = target / ".agents/runtime-router/harnesses/kilo/readiness.json"
            ReadinessCache((ready,)).write(path, now=now)
            decision = _route_with_readiness(
                task, (subscription, payg), policy, quota={}, tps={}, target=target,
                adapter=None, executable="/opt/kilo", catalog_digest=catalog_digest,
                approve=False, max_probes=3, cache_ttl_seconds=600,
                failure_cache_ttl_seconds=30, timeout_seconds=10,
            )
        self.assertEqual(payg.candidate_id, decision.selected.candidate_id)

    def test_evidence_digests_survive_catalog_refresh_but_bind_launch_contract(self) -> None:
        candidate = Candidate(
            "openrouter", "free", frozenset({"code"}), Availability.AVAILABLE,
            CostClass.FREE, QuotaStatus.AVAILABLE, 128000, billing="free",
        )
        first = _readiness_digest(
            catalog_digest="a" * 64, candidate=candidate, effort=None,
            variant=None, executable="/opt/kilo",
        )
        refreshed = _readiness_digest(
            catalog_digest="b" * 64, candidate=candidate, effort=None,
            variant=None, executable="/opt/kilo",
        )
        self.assertEqual(first, refreshed)
        policy = TargetPolicyConfig.from_mapping(
            json.loads((POLICY_DIR / "policy.json").read_text())
        )
        self.assertEqual(
            _tps_evidence_digest(policy, "/opt/kilo"),
            _tps_evidence_digest(policy, "/opt/kilo"),
        )
        self.assertNotEqual(
            _tps_evidence_digest(policy, "/opt/kilo"),
            _tps_evidence_digest(policy, "/opt/other-kilo"),
        )

    def test_catalog_discovery_uses_configured_multi_minute_provider_budget(self) -> None:
        provider_policy = {
            "discovery": {"providerTimeoutSeconds": 4.0},
            "providers": {"one": {"enabled": True}, "two": {"enabled": True}},
        }
        calls = []

        def fake_run(command, *, timeout_seconds):
            calls.append((tuple(command), timeout_seconds))
            return 0, json.dumps(
                [{"providerID": command[2], "id": "demo", "status": "active"}]
            ).encode()

        with patch("catalog._run_bounded", side_effect=fake_run):
            candidates = discover_candidates("/absolute/kilo", provider_policy, timeout_seconds=10.0)
        self.assertEqual({item.candidate_id for item in candidates}, {"one/demo", "two/demo"})
        self.assertEqual([call[1] for call in calls], [4.0, 4.0])

    def test_catalog_discovery_rejects_unbounded_provider_timeout(self) -> None:
        provider_policy = {
            "discovery": {"providerTimeoutSeconds": 901},
            "providers": {"one": {"enabled": True}},
        }
        with self.assertRaises(CatalogError):
            discover_candidates("/absolute/kilo", provider_policy, timeout_seconds=900)

    def test_runner_reports_missing_or_unrouteable_catalog_without_traceback(self) -> None:
        runner = ADAPTER_DIR / "run_arr_task.py"
        env = dict(os.environ)
        env["PYTHONPATH"] = os.pathsep.join(
            item for item in (env.get("PYTHONPATH", ""), str(ADAPTER_DIR)) if item
        )
        result = subprocess.run(
            [sys.executable, str(runner), "--target", str(ROOT), "--profile", "routine", "smoke"],
            capture_output=True,
            text=True,
            env=env,
            timeout=10,
        )
        self.assertIn(result.returncode, {0, 2})
        self.assertNotIn("Traceback", result.stdout + result.stderr)
        payload = json.loads(result.stdout)
        # A clean target has no cache and reports INCOMPLETE.  Once the
        # harness-scoped cache has been deliberately installed by acceptance,
        # the same no-worker invocation may proceed to a structured NO_ROUTE
        # because quota/TPS evidence is still fail-closed.  Neither outcome
        # may become a traceback.
        self.assertIn(payload["status"], {"INCOMPLETE", "NO_ROUTE", "PLAN"})

    def test_legacy_router_directory_is_removed(self) -> None:
        legacy = ROOT / ".kilo" / "model-router"
        self.assertFalse(legacy.exists(), "the deleted router must not be resurrected")
        self.assertTrue((ADAPTER_DIR / "run_arr_task.py").is_file())
        self.assertTrue((ADAPTER_DIR / "route_subagents.py").is_file())


if __name__ == "__main__":
    unittest.main()
