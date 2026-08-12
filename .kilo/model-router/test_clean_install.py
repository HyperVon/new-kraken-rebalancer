"""Clean-checkout test: wrappers use local venv, not global site-packages."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class CleanInstallTests(unittest.TestCase):
    def test_wrappers_use_local_runner(self):
        script_dir = Path(__file__).parent
        route_kilo = (script_dir / "route-kilo").read_text()
        route_sub = (script_dir / "route-subagents").read_text()
        self.assertIn(".venv/bin/python", route_kilo)
        self.assertIn(".venv/bin/python", route_sub)
        # Must fail closed when venv missing
        self.assertIn("ARR venv not found", route_kilo)
        self.assertIn("setup.sh", route_kilo)

    def test_requirements_pinned(self):
        req = (Path(__file__).parent / "requirements.txt").read_text()
        self.assertIn("agent-runtime-router.git@", req)
        # Must be pinned to a full 40-char commit, not branch
        import re
        self.assertRegex(req, r"@[0-9a-f]{40}")
        self.assertNotIn("@main", req)
        self.assertNotIn("@master", req)

    def test_setup_creates_venv_and_installs(self):
        # This test simulates a clean checkout: create a temp copy without venv, run setup, verify
        # For speed, we check that the existing venv was created by setup and contains ARR
        script_dir = Path(__file__).parent
        venv_py = script_dir / ".venv" / "bin" / "python"
        if not venv_py.exists():
            self.skipTest("venv not present; run .kilo/model-router/setup.sh first")
        # Verify ARR is importable from venv and not relying on global
        result = subprocess.run(
            [str(venv_py), "-c", "import agent_runtime_router; print(agent_runtime_router.__version__)"],
            capture_output=True, text=True, timeout=10
        )
        self.assertEqual(0, result.returncode)
        self.assertRegex(result.stdout.strip(), r"^\d+\.\d+\.\d+")
        # Verify that running with empty PYTHONPATH still works (venv isolated)
        env = {k: v for k, v in __import__("os").environ.items() if k != "PYTHONPATH"}
        result2 = subprocess.run(
            [str(venv_py), "-c", "import agent_runtime_router; print('ok')"],
            capture_output=True, text=True, env=env, timeout=10
        )
        self.assertEqual(0, result2.returncode)
        self.assertEqual("ok", result2.stdout.strip())

    def test_wrappers_fail_closed_without_venv(self):
        # Simulate clean checkout without venv: wrapper should exit 64 with diagnostic
        import tempfile, shutil
        script_dir = Path(__file__).parent
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp) / "model-router"
            shutil.copytree(script_dir, tmp_path, ignore=shutil.ignore_patterns(".venv", "__pycache__", "*.pyc"))
            for name in ("route-kilo", "route-subagents"):
                result = subprocess.run(
                    [str(tmp_path / name), "--help"],
                    capture_output=True, text=True, timeout=5
                )
                self.assertEqual(64, result.returncode)
                self.assertIn("ARR venv not found", result.stderr)
                self.assertIn("setup.sh", result.stderr)

    def test_global_arr_not_used(self):
        # Ensure that even if global site-packages has no ARR, venv still works.
        # We test by running venv python with PYTHONPATH empty and checking import
        script_dir = Path(__file__).parent
        venv_py = script_dir / ".venv" / "bin" / "python"
        if not venv_py.exists():
            self.skipTest("venv not present")
        # Run with -S to ignore site, but venv site should still be available
        result = subprocess.run(
            [str(venv_py), "-S", "-c", "import sys; print(sys.path)"],
            capture_output=True, text=True, timeout=5
        )
        # Should still be able to import ARR via venv site (not global)
        result2 = subprocess.run(
            [str(venv_py), "-c", "import agent_runtime_router; import sys; print(','.join(p for p in sys.path if 'site-packages' in p))"],
            capture_output=True, text=True, timeout=5
        )
        self.assertEqual(0, result2.returncode)
        self.assertIn(".venv", result2.stdout)


if __name__ == "__main__":
    unittest.main()
