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


if __name__ == "__main__":
    unittest.main()
