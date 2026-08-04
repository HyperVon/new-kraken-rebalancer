import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("fileio.py")
SPEC = importlib.util.spec_from_file_location("model_router_fileio", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class FileIoTests(unittest.TestCase):
    def test_round_trip_content(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "out.json"
            MODULE.atomic_write(path, '{"a": 1}\n')
            self.assertEqual('{"a": 1}\n', path.read_text(encoding="utf-8"))

    def test_preserves_existing_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config"
            path.write_text("old", encoding="utf-8")
            os.chmod(path, 0o644)
            MODULE.atomic_write(path, "new")
            self.assertEqual(0o644, stat_mode(path))

    def test_new_file_created_with_0600(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fresh.json"
            MODULE.atomic_write(path, "{}")
            self.assertEqual(0o600, stat_mode(path))

    def test_cleans_temp_file_on_write_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "target.json"
            with mock.patch("os.fsync", side_effect=OSError("disk full")):
                with self.assertRaises(OSError):
                    MODULE.atomic_write(path, "data")
            leftovers = list(Path(directory).glob(f".{path.name}.*"))
            self.assertEqual([], leftovers)
            self.assertFalse(path.exists())


def stat_mode(path: Path) -> int:
    return path.stat().st_mode & 0o777


if __name__ == "__main__":
    unittest.main()
