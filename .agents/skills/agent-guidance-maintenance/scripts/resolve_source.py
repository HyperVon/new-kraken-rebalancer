#!/usr/bin/env python3
"""Resolve or configure an ignored Agent Guidance Kit source locator."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

ENVIRONMENT_VARIABLE = "AGENT_GUIDANCE_KIT_ROOT"
LOCATOR = Path(".agents/.agent-guidance-kit/source.json")
IGNORE_PATTERN = "/.agents/.agent-guidance-kit/source.json"


class SourceResolutionError(RuntimeError):
    """Raised when a kit checkout cannot be resolved safely."""


def validate_directory(path: Path, label: str) -> Path:
    expanded = path.expanduser()
    if not expanded.exists():
        raise SourceResolutionError(f"{label} does not exist: {expanded}")
    if expanded.is_symlink() or not expanded.is_dir():
        raise SourceResolutionError(
            f"{label} must be a real directory, not a symlink: {expanded}"
        )
    return expanded.resolve()


def validate_kit_root(path: Path) -> Path:
    root = validate_directory(path, "kit root")
    required = (
        root / ".agents/skill-dependencies.json",
        root / ".agents/skills/bootstrap-project/SKILL.md",
        root / ".agents/skills/bootstrap-project/scripts/install_skills.py",
        root / ".agents/skills/agent-guidance-maintenance/SKILL.md",
    )
    missing = [str(item.relative_to(root)) for item in required if not item.is_file()]
    if missing:
        raise SourceResolutionError(
            f"kit root is missing required files: {', '.join(missing)}"
        )
    if any(item.is_symlink() for item in required):
        raise SourceResolutionError("kit root contains a symlinked required file")
    return root


def run_git(
    target_root: Path, arguments: list[str]
) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            ["git", "-C", str(target_root), *arguments],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as error:
        raise SourceResolutionError(
            "Git is unavailable for locator validation"
        ) from error


def git_exclude_path(target_root: Path) -> Path:
    result = run_git(
        target_root,
        ["rev-parse", "--path-format=absolute", "--git-path", "info/exclude"],
    )
    if result.returncode != 0 or not result.stdout.strip():
        raise SourceResolutionError(
            "target is not a Git worktree; use an explicit argument or environment setting"
        )
    path = Path(result.stdout.strip())
    if path.is_symlink():
        raise SourceResolutionError("Git exclude file must not be a symlink")
    return path


def locator_is_ignored(target_root: Path) -> bool:
    result = run_git(
        target_root,
        ["check-ignore", "--quiet", "--", LOCATOR.as_posix()],
    )
    return result.returncode == 0


def ensure_safe_locator_parent(target_root: Path, create: bool = False) -> Path:
    current = target_root
    for part in LOCATOR.parent.parts:
        current = current / part
        if current.exists() or current.is_symlink():
            if current.is_symlink() or not current.is_dir():
                raise SourceResolutionError(
                    f"source locator parent is unsafe: {current.relative_to(target_root)}"
                )
        elif create:
            current.mkdir()
        else:
            break
    return current


def read_locator(target_root: Path) -> Path | None:
    ensure_safe_locator_parent(target_root)
    path = target_root / LOCATOR
    if not path.exists() and not path.is_symlink():
        return None
    if path.is_symlink() or not path.is_file():
        raise SourceResolutionError(f"source locator must be a real file: {LOCATOR}")
    if not locator_is_ignored(target_root):
        raise SourceResolutionError(f"source locator is not ignored by Git: {LOCATOR}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SourceResolutionError(f"cannot read source locator: {error}") from error
    raw_root = value.get("kit_root") if isinstance(value, dict) else None
    if not isinstance(raw_root, str) or not raw_root.strip():
        raise SourceResolutionError("source locator must contain a non-empty kit_root")
    candidate = Path(raw_root).expanduser()
    if not candidate.is_absolute():
        candidate = target_root / candidate
    return validate_kit_root(candidate)


def resolve_source(
    target_root: Path,
    explicit: Path | None = None,
    environment: dict[str, str] | None = None,
) -> tuple[Path, str]:
    target = validate_directory(target_root, "target root")
    if explicit is not None:
        return validate_kit_root(explicit), "explicit"

    values = environment if environment is not None else os.environ
    configured = values.get(ENVIRONMENT_VARIABLE)
    if configured:
        return validate_kit_root(Path(configured)), "environment"

    located = read_locator(target)
    if located is not None:
        return located, "target-local locator"

    adjacent = target.parent / "agent-guidance-kit"
    try:
        return validate_kit_root(adjacent), "adjacent sibling"
    except SourceResolutionError:
        pass

    raise SourceResolutionError(
        "Agent Guidance Kit source is unresolved; provide --kit-root or set "
        f"{ENVIRONMENT_VARIABLE}"
    )


def relative_locator_value(target_root: Path, kit_root: Path) -> str:
    try:
        return os.path.relpath(kit_root, target_root)
    except ValueError:
        return str(kit_root)


def configure_locator(target_root: Path, kit_root: Path) -> Path:
    target = validate_directory(target_root, "target root")
    kit = validate_kit_root(kit_root)
    exclude = git_exclude_path(target)
    ensure_safe_locator_parent(target, create=True)
    exclude.parent.mkdir(parents=True, exist_ok=True)
    current_ignore = exclude.read_text(encoding="utf-8") if exclude.exists() else ""
    if IGNORE_PATTERN not in current_ignore.splitlines():
        with exclude.open("a", encoding="utf-8") as handle:
            if current_ignore and not current_ignore.endswith("\n"):
                handle.write("\n")
            handle.write(f"{IGNORE_PATTERN}\n")

    locator = target / LOCATOR
    payload = {"kit_root": relative_locator_value(target, kit)}
    if locator.exists() or locator.is_symlink():
        existing = read_locator(target)
        if existing != kit:
            raise SourceResolutionError(
                "existing source locator resolves to a different kit checkout"
            )
        return locator
    with locator.open("x", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write("\n")
    if not locator_is_ignored(target):
        raise SourceResolutionError(f"created locator is not ignored: {LOCATOR}")
    return locator


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    resolve_parser = subparsers.add_parser("resolve")
    resolve_parser.add_argument("--target", required=True)
    resolve_parser.add_argument("--kit-root")

    configure_parser = subparsers.add_parser("configure")
    configure_parser.add_argument("--target", required=True)
    configure_parser.add_argument("--kit-root", required=True)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "configure":
            locator = configure_locator(Path(args.target), Path(args.kit_root))
            print(f"Configured ignored source locator: {locator}")
            return 0
        root, method = resolve_source(
            Path(args.target), Path(args.kit_root) if args.kit_root else None
        )
        print(json.dumps({"kit_root": str(root), "method": method}, sort_keys=True))
        return 0
    except SourceResolutionError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
