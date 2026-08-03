import importlib.util
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
        self.assertIn("docs/", prompt)

    def test_compact_output_keeps_bounded_tail(self):
        output = "\n".join(f"line {index}" for index in range(30))
        lines = MODULE.compact_output(output).splitlines()
        self.assertEqual(MODULE.MAX_REPORT_LINES, len(lines))
        self.assertEqual("line 29", lines[-1])

    def test_compact_output_decodes_timeout_bytes(self):
        self.assertEqual("worker failed", MODULE.compact_output(b"worker failed"))

    def test_worker_launch_passes_selected_route_and_agent(self):
        item = {
            "track": {"id": "source", "task": "Inspect source", "files": [], "read_only": True},
            "selection": {"route": "openai/gpt-5.4", "agent": "explore"},
            "prompt": "bounded prompt",
        }
        completed = MODULE.subprocess.CompletedProcess([], 0, "report", "")
        with patch.object(MODULE.subprocess, "run", return_value=completed) as run:
            result = MODULE.launch_worker(item, timeout=10, allow_auto=False)
        command = run.call_args.args[0]
        self.assertEqual(0, result["exit_code"])
        self.assertIn("--model", command)
        self.assertEqual("openai/gpt-5.4", command[command.index("--model") + 1])
        self.assertEqual("explore", command[command.index("--agent") + 1])

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


if __name__ == "__main__":
    unittest.main()
