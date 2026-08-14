"""Emit a bounded ARR discovery report for the target-owned Kilo catalog.

This is only the subprocess boundary used by ``harness discover``.  Provider
policy and model filtering remain in ``catalog.py``; credentials and native
Kilo behavior remain outside ARR core.  The wrapper emits only the validated
DiscoveryReport schema and never prints command output or exceptions.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from agent_runtime_router.harnesses.contracts import (
    DiscoveryReport,
    DiscoveryRequest,
    EvidenceStatus,
    ProbeEvidence,
)

from adapter import load_json, KrakenCatalogSource


def _report(error_code: str) -> DiscoveryReport:
    safe = error_code if error_code and error_code.replace("_", "").isalnum() else "discovery_failed"
    return DiscoveryReport(
        adapter_id="kilo",
        status=EvidenceStatus.UNKNOWN,
        probes=(
            ProbeEvidence(
                "kilo-models",
                "kilo-cli",
                EvidenceStatus.UNKNOWN,
                error_code=safe[:80],
            ),
        ),
        error_code=safe[:80],
        secrets_redacted=True,
    )


def _target_root() -> Path:
    value = os.environ.get("ARR_TARGET_ROOT")
    if value:
        candidate = Path(value).expanduser().resolve()
    else:
        candidate = Path.cwd().resolve()
    if not candidate.is_dir() or candidate.is_symlink():
        raise ValueError("target_invalid")
    return candidate


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--kilo", required=True)
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--timeout", type=float, default=900.0)
    parser.add_argument("--max-candidates", type=int, default=5000)
    args = parser.parse_args(argv)
    try:
        target = _target_root()
        executable = Path(args.kilo).expanduser()
        if not executable.is_absolute() or executable.is_symlink() or not executable.is_file():
            raise ValueError("kilo_executable_invalid")
        policy_path = target / ".agents/runtime-router/adapters/kilo/provider-policy.json"
        policy = load_json(policy_path)
        request = DiscoveryRequest(
            target_root=str(target),
            refresh=bool(args.refresh),
            max_candidates=max(1, min(int(args.max_candidates), 5000)),
            timeout_seconds=max(1.0, min(float(args.timeout), 900.0)),
        )
        report = KrakenCatalogSource(str(executable), policy).discover(request)
    except Exception:
        report = _report("discovery_failed")
    try:
        validated = DiscoveryReport.from_mapping(report.to_dict())
        print(json.dumps(validated.to_dict(), sort_keys=True, separators=(",", ":")))
    except Exception:
        print(json.dumps(_report("report_invalid").to_dict(), sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
