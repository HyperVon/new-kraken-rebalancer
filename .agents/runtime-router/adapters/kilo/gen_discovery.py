"""Generate ignored, machine-local ARR discovery configuration for Kilo."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from agent_runtime_router import CapabilityEvidence, HarnessProfile, HarnessStateNamespace
from agent_runtime_router.harnesses.contracts import EvidenceStatus

# Kilo patch releases in this series retain the command shape verified by this
# target.  Treat patch upgrades as a normal local metadata refresh, but stop
# before routing when a minor or major upgrade needs the adapter contract
# reviewed again.
SUPPORTED_KILO_MAJOR_MINOR = (7, 4)
MINIMUM_SUPPORTED_KILO_PATCH = 21
_SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
_HELP_CONTRACTS = (
    (("run", "--help"), ("--model", "--agent", "--format", "--variant")),
    (("models", "--help"), ("--verbose",)),
)


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


def _is_supported_kilo_version(version: str) -> bool:
    """Return whether ``version`` is a reviewed 7.4 patch-compatible release."""

    match = _SEMVER.fullmatch(version)
    if match is None:
        return False
    major, minor, patch = (int(part) for part in match.groups())
    return (major, minor) == SUPPORTED_KILO_MAJOR_MINOR and patch >= MINIMUM_SUPPORTED_KILO_PATCH


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
    version = next(
        (token.lstrip("v") for token in text.split() if _SEMVER.fullmatch(token.lstrip("v"))),
        "",
    )
    if not _is_supported_kilo_version(version):
        raise SystemExit("unsupported Kilo major/minor version; review the target adapter first")
    return version


def _verify_help_contract(kilo: Path) -> None:
    """Verify the no-network CLI flags the target adapter actually renders.

    This makes routine patch upgrades self-service while still failing closed
    when Kilo changes a command form.  Output is deliberately inspected only
    in memory and is never persisted or reported.
    """

    for suffix, required_tokens in _HELP_CONTRACTS:
        try:
            result = subprocess.run(
                [str(kilo), *suffix],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                # Kilo 7.4.22 writes normal help text to stderr.  This is a
                # local, bounded contract check; the combined output is only
                # inspected in memory and never persisted or surfaced.
                stderr=subprocess.STDOUT,
                timeout=10.0,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise SystemExit("Kilo help contract check failed") from exc
        if result.returncode != 0:
            raise SystemExit("Kilo help contract check failed")
        output = result.stdout[:16_384].decode("utf-8", "replace")
        if not all(token in output for token in required_tokens):
            raise SystemExit("Kilo help contract is unsupported; review the target adapter first")


def _existing_relative_paths(target: Path, paths: tuple[str, ...], *, directory: bool) -> tuple[str, ...]:
    """Return only regular, target-local guidance/skill paths as profile evidence."""

    result: list[str] = []
    for relative in paths:
        path = target / relative
        if path.is_symlink():
            continue
        if (path.is_dir() if directory else path.is_file()):
            result.append(relative)
    return tuple(result)


def _profile_mapping(target: Path, version: str) -> dict[str, object]:
    """Build the minimum redacted Kilo profile required by a state pointer.

    This generator has verified only the local executable/version. Model
    listing, billing, quota, and native worker execution remain target-owned
    evidence from their separate bounded workflows.
    """

    profile = HarnessProfile(
        harness_id="kilo",
        status=EvidenceStatus.BEST_EFFORT,
        capabilities=(
            CapabilityEvidence(
                "model_listing",
                EvidenceStatus.BEST_EFFORT,
                "kilo-cli",
                "Local Kilo version is verified; listing remains a bounded target adapter probe.",
            ),
            CapabilityEvidence(
                "native_launch",
                EvidenceStatus.DOCUMENTED,
                "target-adapter",
                "Target adapter renders the verified shell-free Kilo run command shape.",
            ),
        ),
        version=version,
        version_source="kilo-cli",
        instruction_paths=_existing_relative_paths(
            target,
            ("AGENTS.md", ".agents/AGENTS.md", ".agents/OPERATING.md", ".kilo/operating.md"),
            directory=False,
        ),
        skill_paths=_existing_relative_paths(
            target,
            (".agents/skills", ".kilo/command", ".kilo/agent"),
            directory=True,
        ),
    )
    return profile.to_dict()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--kilo", help="absolute Kilo executable; defaults to PATH")
    args = parser.parse_args(argv)
    target = _target_root()
    kilo = _resolve_kilo(args.kilo)
    version = _version(kilo)
    _verify_help_contract(kilo)
    adapter_dir = target / ".agents/runtime-router/adapters/kilo"
    runtime = target / ".agents/.agent-runtime-router/run.py"
    wrapper = adapter_dir / "discover.py"
    if not runtime.is_file() or not wrapper.is_file():
        raise SystemExit("receipt-managed ARR runtime or discovery wrapper is missing")
    profile = _profile_mapping(target, version)
    try:
        namespace = HarnessStateNamespace.for_target(target, "kilo")
        namespace.write_artifact("profile", profile)
    except Exception as exc:
        raise SystemExit("unable to generate Kilo harness profile") from exc
    discovery = {
        "schema_version": 1,
        "kind": "subprocess",
        "adapter_id": "kilo",
        "probe_id": "kilo-models",
        "command": [str(runtime.resolve()), "--python", str(wrapper.resolve()), "--kilo", str(kilo)],
        "cwd": str(target.resolve()),
        # Model listings may cold-start or contact several provider backends.
        # Keep this bounded but long enough for a real refresh; the adapter
        # applies a shorter per-provider budget and one shared outer deadline.
        "timeout_seconds": 900.0,
        "max_output_bytes": 4 * 1024 * 1024,
    }
    (adapter_dir / "discovery.json").write_text(
        json.dumps(discovery, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (adapter_dir / "kilo-resolved.json").write_text(
        json.dumps(
            {
                "kilo_executable": str(kilo),
                "kilo_version": version,
                "supported_major_minor": ".".join(str(part) for part in SUPPORTED_KILO_MAJOR_MINOR),
                "minimum_supported_patch": MINIMUM_SUPPORTED_KILO_PATCH,
                "help_contract_verified": True,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "adapter_id": "kilo",
        "kilo_version": version,
        "discovery": str(adapter_dir / "discovery.json"),
        "profile": namespace.relative_root + "/profile.json",
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
