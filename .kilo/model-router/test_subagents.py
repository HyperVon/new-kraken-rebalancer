import importlib.util
import json
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("subagents.py")
SPEC = importlib.util.spec_from_file_location("model_router_subagents", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.path.insert(0, str(MODULE_PATH.parent))
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SubagentRouterTests(unittest.TestCase):
    def test_manifest_rejects_duplicate_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tracks.json"
            path.write_text(
                '{"tracks": [{"id": "same", "task": "one"}, {"id": "same", "task": "two"}]}',
                encoding="utf-8",
            )
            with self.assertRaises(MODULE.router.RouterError):
                MODULE.load_manifest(path)

    def test_worker_prompt_contains_route_and_read_only_contract(self):
        prompt = MODULE.worker_prompt(
            {"id": "docs", "files": ["docs/"], "task": "Check the docs", "read_only": True},
            "openai/gpt-5.4",
            False,
        )
        self.assertIn("openai/gpt-5.4", prompt)
        self.assertIn("Do not edit files", prompt)
        self.assertIn("tool-call markup", prompt)
        self.assertIn("docs/", prompt)

    def test_worker_prompt_injects_launch_time_and_date_rule(self):
        prompt = MODULE.worker_prompt(
            {"id": "docs", "files": ["docs/"], "task": "Check the docs", "read_only": True},
            "openai/gpt-5.4",
            False,
        )
        self.assertRegex(prompt, r"Launched at \(UTC\): \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
        self.assertIn("run `date`", prompt)
        self.assertIn("Never estimate the time", prompt)

    def test_write_status_publishes_live_progress_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            status_path = Path(directory) / "status.json"
            with patch.object(MODULE, "STATUS_PATH", status_path):
                MODULE.write_status(
                    {
                        "pid": 1234,
                        "phase": "launching",
                        "tracks": [{"track": "docs", "route": "openai/gpt-5.4", "status": "queued"}],
                    }
                )
            stored = json.loads(status_path.read_text(encoding="utf-8"))
            self.assertEqual("launching", stored["phase"])
            self.assertEqual("docs", stored["tracks"][0]["track"])
            self.assertEqual("queued", stored["tracks"][0]["status"])
            self.assertIn("updated_at_utc", stored)
            self.assertRegex(stored["updated_at_utc"], r"\d{4}-\d{2}-\d{2}T")

    def test_workflow_preset_generates_specialized_tracks(self):
        tracks = MODULE.workflows.build_tracks("documentation-review", "Review the docs")
        self.assertEqual(4, len(tracks))
        self.assertEqual({"product-docs", "runtime-contracts", "agent-guidance", "build-config"}, {track["id"] for track in tracks})
        self.assertTrue(all("Review the docs" in track["task"] for track in tracks))

    def test_compact_output_keeps_bounded_tail(self):
        output = "\n".join(f"line {index}" for index in range(30))
        lines = MODULE.compact_output(output).splitlines()
        self.assertEqual(MODULE.MAX_REPORT_LINES, len(lines))
        self.assertEqual("line 29", lines[-1])

    def test_compact_output_decodes_timeout_bytes(self):
        self.assertEqual("worker failed", MODULE.compact_output(b"worker failed"))

    def test_extract_report_uses_json_text_events_and_ignores_tool_traces(self):
        stdout = "\n".join(
            [
                '{"type":"tool_use","part":{"tool":"read"}}',
                '{"type":"text","part":{"text":"Checked: docs/README.md\\nResult: clean"}}',
                '{"type":"step_finish","part":{"reason":"stop"}}',
            ]
        )
        report, usable = MODULE.extract_report(stdout, "agent warning")
        self.assertTrue(usable)
        self.assertEqual("Checked: docs/README.md\nResult: clean", report)

    def test_extract_report_rejects_protocol_only_output(self):
        report, usable = MODULE.extract_report("⚙ compress {\"topic\":\"audit\"}", "")
        self.assertFalse(usable)
        self.assertIn("compress", report)

    def test_worker_launch_passes_selected_route_and_json_output(self):
        item = {
            "track": {"id": "source", "task": "Inspect source", "files": [], "read_only": True},
            "selection": {"route": "openai/gpt-5.4", "agent": "explore"},
            "prompt": "bounded prompt",
        }
        completed = MODULE.subprocess.CompletedProcess(
            [],
            0,
            '{"type":"text","part":{"text":"Checked: source\\nResult: clean"}}',
            "",
        )
        with patch.object(MODULE.subprocess, "run", return_value=completed) as run:
            result = MODULE.launch_worker(item, timeout=10, allow_auto=False)
        command = run.call_args.args[0]
        self.assertEqual(0, result["exit_code"])
        self.assertIn("--model", command)
        self.assertEqual("openai/gpt-5.4", command[command.index("--model") + 1])
        self.assertEqual("json", command[command.index("--format") + 1])
        self.assertNotIn("--agent", command)

    def test_worker_launch_rejects_success_without_text_report(self):
        completed = MODULE.subprocess.CompletedProcess([], 0, '{"type":"step_finish"}', "")
        item = {
            "track": {"id": "source", "task": "Inspect source", "files": [], "read_only": True},
            "selection": {"route": "openai/gpt-5.4", "agent": "explore"},
            "prompt": "bounded prompt",
        }
        with patch.object(MODULE.subprocess, "run", return_value=completed):
            result = MODULE.launch_worker(item, timeout=10, allow_auto=False)
        self.assertEqual(1, result["exit_code"])
        self.assertEqual("report_contract", result["failure_kind"])

    def test_run_report_records_routes_without_prompts_or_worker_text(self):
        plan = {
            "workflow": "documentation-review",
            "aa": "fresh",
            "tracks": [
                {
                    "track": "docs",
                    "route": "openai/gpt-5.4",
                    "provider": "openai",
                    "model": "gpt-5.4",
                    "agent": "documentation-contract-auditor",
                    "profile": "routine",
                    "billing": "subscription/account-priced",
                    "cost": {"effective": 0.0, "source": "subscription/account-priced"},
                    "capability": {"score": 42.0, "source": "Artificial Analysis", "minimum": 10},
                    "quota": {"state": "sufficient", "remaining_percent": 80.0},
                    "aa": "fresh",
                    "read_only": True,
                }
            ],
        }
        prepared = [
            {
                "track": {"id": "docs", "files": ["docs/"], "task": "secret parent prompt"},
                "selection": plan["tracks"][0],
            }
        ]
        results = [
            {
                "track": "docs",
                "route": "openai/gpt-5.4",
                "attempted_routes": ["openai/gpt-5.4"],
                "exit_code": 0,
                "duration_seconds": 1.2,
                "failure_kind": None,
                "failovers": [],
                "report": "SECRET worker findings",
            }
        ]
        with tempfile.TemporaryDirectory() as directory:
            paths = MODULE.write_run_report(plan, prepared, results, directory)
            markdown = Path(paths["markdown"]).read_text(encoding="utf-8")
            payload = MODULE.json.loads(Path(paths["json"]).read_text(encoding="utf-8"))
        self.assertIn("openai/gpt-5.4", markdown)
        self.assertNotIn("secret parent prompt", markdown)
        self.assertNotIn("SECRET worker findings", markdown)
        self.assertEqual("gpt-5.4", payload["tracks"][0]["used"]["model"])

    def test_adversarial_workflows_require_distinct_routes(self):
        self.assertTrue(MODULE.workflows.requires_distinct_routes("documentation-adversarial-review"))
        self.assertTrue(MODULE.workflows.requires_distinct_routes("adversarial-pr-review"))
        self.assertFalse(MODULE.workflows.requires_distinct_routes("documentation-review"))

    def test_read_only_worker_workspace_isolated_from_parent_runtime_files(self):
        with MODULE.worker_workspace(True) as workspace:
            self.assertNotEqual(MODULE.router.ROOT, workspace)
            self.assertTrue((workspace / "README.md").exists())
            self.assertFalse((workspace / "rebalancer-config.json").exists())

    def test_read_only_workspace_ignore_excludes_credentials_and_local_state(self):
        names = [
            "README.md",
            "env.local",
            "manifest.local",
            "agent-manager.json",
            ".env.production",
            "rebalancer-config.json",
            "history.db",
            "src",
        ]
        ignored = MODULE._ignore_read_only_files("", names)
        self.assertIn("env.local", ignored)
        self.assertIn("manifest.local", ignored)
        self.assertIn("agent-manager.json", ignored)
        self.assertIn(".env.production", ignored)
        self.assertIn("rebalancer-config.json", ignored)
        self.assertIn("history.db", ignored)
        self.assertNotIn("README.md", ignored)
        self.assertNotIn("src", ignored)

    def test_read_only_worker_fails_over_after_rate_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            fallback = MODULE.router.Candidate(
                route="nvidia/free-model",
                provider="nvidia",
                model="free-model",
                name="Free model",
                status="active",
                input_cost=0,
                output_cost=0,
                cache_read_cost=0,
                context_limit=128000,
                output_limit=16000,
                tool_call=True,
                reasoning=True,
                attachment=False,
                pdf=False,
                billing="free",
                free_allowed=True,
            )
            item = {
                "track": {"id": "source", "task": "Inspect source", "files": [], "read_only": True},
                "selection": {
                    "route": "openrouter/limited",
                    "provider": "openrouter",
                    "profile": "coding",
                    "aa": "fresh",
                    "agent": "explore",
                    "read_only": True,
                },
                "prompt": "bounded prompt",
                "candidates": [fallback],
                "profile": {"minimum": 1},
                "config": {"quota": {"cooldownPath": str(Path(directory) / "cooldowns.json")}},
                "sensitive": False,
                "allow_edits": False,
            }
            results = [
                {
                    "track": "source",
                    "route": "openrouter/limited",
                    "exit_code": 1,
                    "duration_seconds": 0.1,
                    "report": "HTTP 429 rate limit",
                    "failure_kind": "rate_limit",
                },
                {
                    "track": "source",
                    "route": "nvidia/free-model",
                    "exit_code": 0,
                    "duration_seconds": 0.1,
                    "report": "done",
                    "failure_kind": None,
                },
            ]
            with patch.object(MODULE, "launch_worker", side_effect=results) as launch:
                with patch.object(MODULE.router, "select_candidate", return_value=fallback):
                    result = MODULE.launch_with_failover(item, timeout=10, allow_auto=False)
            self.assertEqual(2, launch.call_count)
            self.assertEqual("nvidia/free-model", result["route"])
            self.assertEqual("rate_limit", result["failovers"][0]["reason"])

    def test_read_only_worker_blacklists_end_of_life_model_and_fails_over(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "config.json"
            config_path.write_text(
                MODULE.json.dumps({"blacklist": {"models": [], "providers": []}}),
                encoding="utf-8",
            )
            fallback = MODULE.router.Candidate(
                route="nvidia/live-model",
                provider="nvidia",
                model="live-model",
                name="Live model",
                status="active",
                input_cost=0,
                output_cost=0,
                cache_read_cost=0,
                context_limit=128000,
                output_limit=16000,
                tool_call=True,
                reasoning=True,
                attachment=False,
                pdf=False,
                billing="free",
                free_allowed=True,
            )
            item = {
                "track": {"id": "source", "task": "Inspect source", "files": [], "read_only": True},
                "selection": {
                    "route": "nvidia/dead-model",
                    "provider": "nvidia",
                    "profile": "coding",
                    "aa": "fresh",
                    "agent": "explore",
                    "read_only": True,
                },
                "prompt": "bounded prompt",
                "candidates": [fallback],
                "profile": {"minimum": 1},
                "config": {
                    "quota": {"cooldownPath": str(Path(directory) / "cooldowns.json")},
                    "tpsProbe": {"enabled": False},
                },
                "sensitive": False,
                "allow_edits": False,
                "config_path": str(config_path),
            }
            results = [
                {
                    "track": "source",
                    "route": "nvidia/dead-model",
                    "exit_code": 1,
                    "duration_seconds": 0.1,
                    "report": "HTTP 410 Gone: model reached its end of life and is no longer available",
                    "failure_kind": "model_eol",
                },
                {
                    "track": "source",
                    "route": "nvidia/live-model",
                    "exit_code": 0,
                    "duration_seconds": 0.1,
                    "report": "done",
                    "failure_kind": None,
                },
            ]
            seen: dict[str, set[str]] = {}

            def patched_select(
                candidates, profile, config, sensitive, excluded_routes=None, excluded_providers=None
            ):
                seen["excluded_providers"] = set(excluded_providers or ())
                return fallback

            with patch.object(MODULE, "launch_worker", side_effect=results) as launch:
                with patch.object(MODULE.router, "select_candidate", side_effect=patched_select):
                    result = MODULE.launch_with_failover(item, timeout=10, allow_auto=False)
            self.assertEqual(2, launch.call_count)
            self.assertEqual("nvidia/live-model", result["route"])
            self.assertEqual("model_eol", result["failovers"][0]["reason"])
            self.assertEqual(set(), seen["excluded_providers"])
            payload = MODULE.json.loads(config_path.read_text(encoding="utf-8"))
            self.assertIn("nvidia/dead-model", payload["blacklist"]["models"])

    def test_end_of_life_model_blacklisted_even_when_edits_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            config_path = Path(directory) / "config.json"
            config_path.write_text(MODULE.json.dumps({"blacklist": {"models": []}}), encoding="utf-8")
            item = {
                "track": {"id": "source", "task": "Inspect source", "files": [], "read_only": False},
                "selection": {
                    "route": "nvidia/dead-model",
                    "provider": "nvidia",
                    "profile": "coding",
                    "aa": "fresh",
                    "agent": "explore",
                    "read_only": False,
                },
                "prompt": "bounded prompt",
                "candidates": [],
                "profile": {"minimum": 1},
                "config": {},
                "sensitive": False,
                "allow_edits": True,
                "config_path": str(config_path),
            }
            results = [
                {
                    "track": "source",
                    "route": "nvidia/dead-model",
                    "exit_code": 1,
                    "duration_seconds": 0.1,
                    "report": "model reached its end of life",
                    "failure_kind": "model_eol",
                }
            ]
            with patch.object(MODULE, "launch_worker", side_effect=results) as launch:
                result = MODULE.launch_with_failover(item, timeout=10, allow_auto=True)
            self.assertEqual(1, launch.call_count)
            payload = MODULE.json.loads(config_path.read_text(encoding="utf-8"))
            self.assertIn("nvidia/dead-model", payload["blacklist"]["models"])


if __name__ == "__main__":
    unittest.main()
