from __future__ import annotations

import importlib.util
import sys
import types
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import MagicMock, patch
from urllib.error import URLError


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "validate_mermaid.py"


def load_validator():
    playwright = types.ModuleType("playwright")
    sync_api = types.ModuleType("playwright.sync_api")
    sync_api.sync_playwright = object()
    with patch.dict(sys.modules, {"playwright": playwright, "playwright.sync_api": sync_api}):
        spec = importlib.util.spec_from_file_location("validate_mermaid_under_test", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        assert spec and spec.loader
        spec.loader.exec_module(module)
        return module


validator = load_validator()


class MermaidDownloaderTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = TemporaryDirectory()
        self.cache = Path(self.temp_dir.name) / "mermaid.js"

    def tearDown(self):
        self.temp_dir.cleanup()

    def response_with(self, contents: bytes) -> MagicMock:
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = contents
        return response

    def test_valid_cache_is_reused_without_network(self):
        self.cache.write_bytes(b"x" * (validator.MIN_MERMAID_BYTES + 1))

        with patch.object(validator, "MERMAID_CACHE", self.cache), patch.object(
            validator.urllib.request, "urlopen"
        ) as urlopen:
            self.assertEqual(validator.ensure_mermaid(), self.cache)

        urlopen.assert_not_called()

    def test_certificate_failure_never_retries_without_verification(self):
        certificate_failure = URLError("CERTIFICATE_VERIFY_FAILED")

        with patch.object(validator, "MERMAID_CACHE", self.cache), patch.object(
            validator.urllib.request, "urlopen", side_effect=certificate_failure
        ) as urlopen:
            with self.assertRaises(SystemExit) as raised:
                validator.ensure_mermaid()

        urlopen.assert_called_once_with(validator.MERMAID_URL, timeout=15)
        self.assertIn("trusted CA store", str(raised.exception))
        self.assertFalse(self.cache.exists())

    def test_short_download_is_not_cached(self):
        response = self.response_with(b"too short")

        with patch.object(validator, "MERMAID_CACHE", self.cache), patch.object(
            validator.urllib.request, "urlopen", return_value=response
        ):
            with self.assertRaises(SystemExit) as raised:
                validator.ensure_mermaid()

        self.assertIn("suspiciously short", str(raised.exception))
        self.assertFalse(self.cache.exists())

    def test_download_failure_rejects_short_existing_cache(self):
        self.cache.write_bytes(b"x" * validator.MIN_MERMAID_BYTES)

        with patch.object(validator, "MERMAID_CACHE", self.cache), patch.object(
            validator.urllib.request, "urlopen", side_effect=URLError("offline")
        ):
            with self.assertRaises(SystemExit):
                validator.ensure_mermaid()

        self.assertEqual(self.cache.stat().st_size, validator.MIN_MERMAID_BYTES)


if __name__ == "__main__":
    unittest.main()
