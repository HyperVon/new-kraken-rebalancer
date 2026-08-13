"""Generate ignored, machine-local ARR discovery configuration for Kilo."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

EXPECTED_KILO_VERSION = "7.4.21"


def _target_root() -> Path:
    return Path(__file__).resolve().parents[4]


def _resolve_kilo(explicit: str | None) -> Path:
    value = explicit or shutil.which("kilo")
    if not value:
        raise SystemExit("kilo executable not found on PATH")
    path = Path(value).expanduser().resolve()
    if path.is_symlink() or not path.is_absolute() or not path.is_file():
        raise SystemExit("kilo executable is not a regular absolute file")
    return path


def _version(kilo: Path) -> str:
    try:
        result = subprocess.run(
            [str(kilo), "--version"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=10.0,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise SystemExit("kilo version check failed") from exc
    if result.returncode != 0:
        raise SystemExit("kilo version check failed")
    text = result.stdout[:4096].decode("utf-8", "replace")
    version = next((token.lstrip("v") for token in text.split() if token.lstrip("v").count(".") == 2), "")
    if version != EXPECTED_KILO_VERSION:
        raise SystemExit("unexpected Kilo version")
    return version


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kilo", help="absolute Kilo executable; defaults to PATH")
    args = parser.parse_args(argv)
    target = _target_root()
    kilo = _resolve_kilo(args.kilo)
    version = _version(kilo)
    adapter_dir = target / ".agents/runtime-router/adapters/kilo"
    runtime = target / ".agents/.agent-runtime-router/run.py"
    wrapper = adapter_dir / "discover.py"
    if not runtime.is_file() or not wrapper.is_file():
        raise SystemExit("receipt-managed ARR runtime or discovery wrapper is missing")
    discovery = {
        "schema_version": 1,
        "kind": "subprocess",
        "adapter_id": "kilo",
        "probe_id": "kilo-models",
        "command": [str(runtime.resolve()), "--python", str(wrapper.resolve()), "--kilo", str(kilo)],
        "cwd": str(target.resolve()),
        "timeout_seconds": 60.0,
        "max_output_bytes": 4 * 1024 * 1024,
    }
    (adapter_dir / "discovery.json").write_text(
        json.dumps(discovery, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (adapter_dir / "kilo-resolved.json").write_text(
        json.dumps(
            {"kilo_executable": str(kilo), "kilo_version": version, "expected_version": EXPECTED_KILO_VERSION},
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"adapter_id": "kilo", "kilo_version": version, "discovery": str(adapter_dir / "discovery.json")}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
