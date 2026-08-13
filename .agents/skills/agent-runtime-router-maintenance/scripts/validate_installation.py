#!/usr/bin/env python3
"""Validate a receipt-managed Agent Runtime Router installation."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

INSTALL_ROOT = Path(".agents/.agent-runtime-router")
RECEIPT = INSTALL_ROOT / "receipt.json"
SOURCE_LOCATOR = INSTALL_ROOT / "source.json"
ROUTE_START = "<!-- agent-runtime-router:routes:start -->"
ROUTE_END = "<!-- agent-runtime-router:routes:end -->"
TRANSIENT_DIRS = {"__pycache__", ".mypy_cache", ".pytest_cache", ".ruff_cache"}


class ValidationError(RuntimeError):
    """Raised when installed router state is invalid or has drifted."""


def canonical_json(value: Any) -> bytes:
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ).encode("utf-8")


def digest_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path, label: str) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise ValidationError(f"{label} is missing or unsafe: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValidationError(f"cannot read {label}: {error}") from error
    if not isinstance(value, dict):
        raise ValidationError(f"{label} must contain an object")
    return value


def tree_manifest(root: Path) -> list[dict[str, Any]]:
    if root.is_symlink() or not root.is_dir():
        raise ValidationError(f"managed skill is missing or unsafe: {root}")
    records: list[dict[str, Any]] = []
    for directory, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames[:] = sorted(name for name in dirnames if name not in TRANSIENT_DIRS)
        current = Path(directory)
        for filename in sorted(filenames):
            path = current / filename
            if (
                path.is_symlink()
                or not path.is_file()
                or path.suffix in {".pyc", ".pyo"}
            ):
                raise ValidationError(f"managed skill contains an unsafe file: {path}")
            records.append(
                {
                    "path": path.relative_to(root).as_posix(),
                    "sha256": digest_file(path),
                    "size": path.stat().st_size,
                    "mode": path.stat().st_mode & 0o777,
                }
            )
    return records


def manifest_digest(manifest: list[dict[str, Any]]) -> str:
    return digest_bytes(canonical_json(sorted(manifest, key=lambda item: item["path"])))


def validate_relative(path: Path, label: str) -> None:
    if (
        path.is_absolute()
        or not path.parts
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        raise ValidationError(f"{label} is not a normalized relative path: {path}")


def git_ignored(target: Path) -> bool:
    try:
        result = subprocess.run(
            [
                "git",
                "-C",
                str(target),
                "check-ignore",
                "--quiet",
                "--",
                SOURCE_LOCATOR.as_posix(),
            ],
            check=False,
            capture_output=True,
            timeout=5,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as error:
        raise ValidationError(
            "Git is unavailable for source locator validation"
        ) from error
    return result.returncode == 0


def validate_target_directory(target: Path) -> Path:
    expanded = target.expanduser()
    if expanded.is_symlink() or not expanded.is_dir():
        raise ValidationError(f"target must be a real directory: {expanded}")
    return expanded.resolve()


def info_target(target: Path) -> dict[str, Any]:
    resolved = validate_target_directory(target)
    receipt = read_json(resolved / RECEIPT, "installation receipt")
    if receipt.get("schema_version") != 1:
        raise ValidationError("unsupported installation receipt schema")
    source = receipt.get("source", {})
    runtime = receipt.get("runtime", {})
    routing = receipt.get("routing", {})
    skills = receipt.get("skills", [])
    info: dict[str, Any] = {
        "schema_version": 1,
        "plan_id": receipt.get("plan_id"),
        "source": {
            "revision": source.get("revision"),
            "package": source.get("package", {}),
        },
        "runtime": {
            "package_name": runtime.get("package_name"),
            "package_version": runtime.get("package_version"),
            "package_digest": runtime.get("package_digest"),
            "package_manifest_digest": runtime.get("package_manifest_digest"),
            "runner_sha256": runtime.get("runner_sha256"),
            "package_path": runtime.get("package_path"),
        },
        "routing": {
            "path": routing.get("path"),
            "block_digest": routing.get("block_digest"),
        },
        "skills": [
            {
                "name": item.get("name"),
                "source_digest": item.get("source_digest"),
                "target_digest": item.get("target_digest"),
            }
            for item in skills
            if isinstance(item, dict) and isinstance(item.get("name"), str)
        ],
    }
    locator = resolved / SOURCE_LOCATOR
    info["source_locator"] = {
        "path": SOURCE_LOCATOR.as_posix(),
        "present": locator.is_file() and not locator.is_symlink(),
    }
    return info


def validate_target(target: Path) -> None:
    resolved = validate_target_directory(target)
    receipt = read_json(resolved / RECEIPT, "installation receipt")
    if receipt.get("schema_version") != 1:
        raise ValidationError("unsupported installation receipt schema")
    skills = receipt.get("skills")
    if not isinstance(skills, list) or not skills:
        raise ValidationError("installation receipt has no skills")
    for item in skills:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            raise ValidationError("installation receipt has an invalid skill entry")
        skill_dir = resolved / ".agents/skills" / item["name"]
        digest = manifest_digest(tree_manifest(skill_dir))
        if digest != item.get("target_digest") or digest != item.get("source_digest"):
            raise ValidationError(f"skill digest mismatch: {item['name']}")
    runtime = receipt.get("runtime")
    state = read_json(resolved / INSTALL_ROOT / "runtime.json", "runtime state")
    if not isinstance(runtime, dict) or any(
        state.get(key) != runtime.get(key)
        for key in (
            "package_name",
            "package_version",
            "version",
            "package_digest",
            "package_path",
            "package_manifest_digest",
            "runner",
            "runner_sha256",
        )
    ):
        raise ValidationError("runtime state does not match the receipt")
    raw_package_path = state.get("package_path")
    if not isinstance(raw_package_path, str):
        raise ValidationError("runtime state has no package path")
    package_path = Path(raw_package_path)
    validate_relative(package_path, "runtime package path")
    package_dir = resolved / INSTALL_ROOT / package_path
    package_digest = manifest_digest(tree_manifest(package_dir))
    if package_digest != state.get("package_manifest_digest"):
        raise ValidationError("installed runtime package differs from the receipt")
    raw_runner = state.get("runner")
    if not isinstance(raw_runner, str):
        raise ValidationError("runtime state has no runner")
    runner_path = Path(raw_runner)
    validate_relative(runner_path, "runtime runner path")
    runner = resolved / INSTALL_ROOT / runner_path
    if runner.is_symlink() or not runner.is_file():
        raise ValidationError("router runner is missing or unsafe")
    if digest_file(runner) != state.get("runner_sha256"):
        raise ValidationError("installed router runner differs from the receipt")
    locator = resolved / SOURCE_LOCATOR
    if locator.is_symlink() or not locator.is_file() or not git_ignored(resolved):
        raise ValidationError("source locator is missing, unsafe, or not ignored")
    routing = receipt.get("routing")
    if not isinstance(routing, dict) or not isinstance(routing.get("path"), str):
        raise ValidationError("installation receipt has no routing owner")
    route_file = resolved / routing["path"]
    if route_file.is_symlink() or not route_file.is_file():
        raise ValidationError("routing owner is missing or unsafe")
    text = route_file.read_text(encoding="utf-8")
    if text.count(ROUTE_START) != 1 or text.count(ROUTE_END) != 1:
        raise ValidationError("managed router route block is missing or malformed")
    body = text.split(ROUTE_START, 1)[1].split(ROUTE_END, 1)[0]
    canonical_body = body.replace("\r\n", "\n").replace("\r", "\n")
    canonical_block = f"{ROUTE_START}{canonical_body}{ROUTE_END}"
    if digest_bytes(canonical_block.encode("utf-8")) != routing.get("block_digest"):
        raise ValidationError("managed router route block differs from the receipt")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True)
    parser.add_argument(
        "--info",
        action="store_true",
        help="Print installation metadata instead of validating",
    )
    args = parser.parse_args(argv)
    try:
        if args.info:
            info = info_target(Path(args.target))
            print(json.dumps(info, sort_keys=True, indent=2))
            return 0
        validate_target(Path(args.target))
        print(
            "Validated router skills, runtime state, source locator, and managed routes."
        )
        return 0
    except ValidationError as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
