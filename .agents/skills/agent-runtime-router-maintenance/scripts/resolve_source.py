#!/usr/bin/env python3
"""Resolve a local Agent Runtime Router checkout for maintenance."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

ENVIRONMENT = "AGENT_RUNTIME_ROUTER_ROOT"
LOCATOR = Path(".agents/.agent-runtime-router/source.json")


class SourceResolutionError(RuntimeError):
    """Raised when a router source cannot be resolved safely."""


def validate_directory(path: Path, label: str) -> Path:
    expanded = path.expanduser()
    if not expanded.exists():
        raise SourceResolutionError(f"{label} does not exist: {expanded}")
    if expanded.is_symlink() or not expanded.is_dir():
        raise SourceResolutionError(f"{label} must be a real directory: {expanded}")
    return expanded.resolve()


def validate_router_root(path: Path) -> Path:
    root = validate_directory(path, "router root")
    required = (
        root / "pyproject.toml",
        root / ".agents/skills/bootstrap-runtime-router/SKILL.md",
        root / ".agents/skills/bootstrap-runtime-router/scripts/install_runtime.py",
        root / ".agents/skills/agent-runtime-router/SKILL.md",
        root / ".agents/skills/agent-runtime-router-maintenance/SKILL.md",
    )
    missing = [str(item.relative_to(root)) for item in required if not item.is_file()]
    unsafe = [str(item.relative_to(root)) for item in required if item.is_symlink()]
    if missing:
        raise SourceResolutionError(
            f"router root is missing required files: {', '.join(missing)}"
        )
    if unsafe:
        raise SourceResolutionError(
            f"router root has symlinked required files: {', '.join(unsafe)}"
        )
    return root


def run_git(target: Path, arguments: list[str]) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            ["git", "-C", str(target), *arguments],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as error:
        raise SourceResolutionError(
            "Git is unavailable for source locator validation"
        ) from error


def locator_is_ignored(target: Path) -> bool:
    result = run_git(target, ["check-ignore", "--quiet", "--", LOCATOR.as_posix()])
    return result.returncode == 0


def read_locator(target: Path) -> Path | None:
    locator = target / LOCATOR
    if not locator.exists() and not locator.is_symlink():
        return None
    if locator.is_symlink() or not locator.is_file():
        raise SourceResolutionError("source locator is not a real file")
    if not locator_is_ignored(target):
        raise SourceResolutionError("source locator is not ignored by Git")
    try:
        value = json.loads(locator.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SourceResolutionError(f"cannot read source locator: {error}") from error
    raw_root = value.get("router_root") if isinstance(value, dict) else None
    if not isinstance(raw_root, str) or not raw_root.strip():
        raise SourceResolutionError(
            "source locator must contain a non-empty router_root"
        )
    candidate = Path(raw_root).expanduser()
    if not candidate.is_absolute():
        candidate = target / candidate
    return validate_router_root(candidate)


def resolve_source(
    target_root: Path,
    explicit: Path | None = None,
    environment: dict[str, str] | None = None,
) -> tuple[Path, str]:
    target = validate_directory(target_root, "target root")
    if explicit is not None:
        return validate_router_root(explicit), "explicit"
    values = environment if environment is not None else os.environ
    configured = values.get(ENVIRONMENT)
    if configured:
        return validate_router_root(Path(configured)), "environment"
    located = read_locator(target)
    if located is not None:
        if located == target:
            return located, "self-source locator"
        return located, "target-local locator"
    adjacent = target.parent / "agent-runtime-router"
    try:
        return validate_router_root(adjacent), "adjacent sibling"
    except SourceResolutionError:
        pass
    raise SourceResolutionError(
        "Agent Runtime Router source is unresolved; provide --router-root or set "
        f"{ENVIRONMENT}"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    resolve = subparsers.add_parser("resolve")
    resolve.add_argument("--target", required=True)
    resolve.add_argument("--router-root")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        root, method = resolve_source(
            Path(args.target), Path(args.router_root) if args.router_root else None
        )
        print(json.dumps({"router_root": str(root), "method": method}, sort_keys=True))
        return 0
    except SourceResolutionError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
