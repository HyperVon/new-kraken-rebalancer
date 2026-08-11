#!/usr/bin/env python3
"""Validate adopted skills, relative links, receipts, and managed AGENTS routes."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

FRONTMATTER_NAME = re.compile(r"(?m)^name:\s*([a-z0-9]+(?:-[a-z0-9]+)*)\s*$")
SKILL_NAME = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
LINK = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
ROUTE = re.compile(
    r"\.agents/skills/([a-z0-9-]+)/SKILL\.md|skills/([a-z0-9-]+)/SKILL\.md"
)
ROUTE_START = "<!-- agent-guidance-kit:routes:start -->"
ROUTE_END = "<!-- agent-guidance-kit:routes:end -->"
RECEIPTS = Path(".agents/.agent-guidance-kit/receipts")


def receipt_skills(root: Path, errors: list[str]) -> set[str]:
    names: set[str] = set()
    directory = root / RECEIPTS
    if not directory.exists():
        return names
    if directory.is_symlink() or not directory.is_dir():
        errors.append(f"{RECEIPTS}: receipt directory is unsafe")
        return names
    for path in sorted(directory.glob("*.json")):
        if path.is_symlink() or not path.is_file():
            errors.append(f"{path.relative_to(root)}: receipt is unsafe")
            continue
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            errors.append(f"{path.relative_to(root)}: invalid receipt: {error}")
            continue
        skills = value.get("skills") if isinstance(value, dict) else None
        if not isinstance(skills, list):
            errors.append(f"{path.relative_to(root)}: receipt skills must be a list")
            continue
        for item in skills:
            name = item.get("name") if isinstance(item, dict) else None
            if not isinstance(name, str) or not SKILL_NAME.fullmatch(name):
                errors.append(
                    f"{path.relative_to(root)}: receipt has an invalid skill name"
                )
                continue
            names.add(name)
    return names


def validate_links(root: Path, errors: list[str]) -> None:
    skills_root = root / ".agents/skills"
    if not skills_root.is_dir() or skills_root.is_symlink():
        errors.append(".agents/skills: missing or unsafe skill root")
        return
    for skill_dir in sorted(skills_root.iterdir()):
        if skill_dir.is_symlink() or not skill_dir.is_dir():
            errors.append(
                f"{skill_dir.relative_to(root)}: skill must be a real directory"
            )
            continue
        skill_md = skill_dir / "SKILL.md"
        if skill_md.is_symlink() or not skill_md.is_file():
            errors.append(f"{skill_dir.relative_to(root)}: missing real SKILL.md")
            continue
        text = skill_md.read_text(encoding="utf-8")
        match = FRONTMATTER_NAME.search(text)
        if not match or match.group(1) != skill_dir.name:
            errors.append(
                f"{skill_md.relative_to(root)}: skill name does not match directory"
            )
        for markdown in sorted(skill_dir.rglob("*.md")):
            if markdown.is_symlink():
                errors.append(
                    f"{markdown.relative_to(root)}: symlinked Markdown is unsafe"
                )
                continue
            for raw_target in LINK.findall(markdown.read_text(encoding="utf-8")):
                target = raw_target.strip()
                split = urlsplit(target)
                if split.scheme or target.startswith("#"):
                    continue
                relative = unquote(split.path)
                if not relative:
                    continue
                resolved = (markdown.parent / relative).resolve()
                try:
                    resolved.relative_to(root)
                except ValueError:
                    errors.append(
                        f"{markdown.relative_to(root)}: link escapes target: {target}"
                    )
                    continue
                if not resolved.exists():
                    errors.append(
                        f"{markdown.relative_to(root)}: broken relative link: {target}"
                    )


def validate_routes(root: Path, adopted: set[str], errors: list[str]) -> None:
    candidates = (root / ".agents/AGENTS.md", root / "AGENTS.md")
    managed = [
        path
        for path in candidates
        if path.is_file()
        and not path.is_symlink()
        and ROUTE_START in path.read_text(encoding="utf-8")
    ]
    if len(managed) != 1:
        errors.append("managed Agent Guidance Kit route file is missing or duplicated")
        return
    route_file = managed[0]
    text = route_file.read_text(encoding="utf-8")
    if text.count(ROUTE_START) != 1 or text.count(ROUTE_END) != 1:
        errors.append(
            f"{route_file.relative_to(root)}: managed route block is missing or malformed"
        )
        return
    block = text.split(ROUTE_START, 1)[1].split(ROUTE_END, 1)[0]
    indexed = {left or right for left, right in ROUTE.findall(block)}
    missing = sorted(adopted - indexed)
    if missing:
        errors.append(
            f"{route_file.relative_to(root)}: receipt-owned skills missing from route block: "
            f"{', '.join(missing)}"
        )
    for name in sorted(adopted):
        skill_md = root / ".agents/skills" / name / "SKILL.md"
        if skill_md.is_symlink() or not skill_md.is_file():
            errors.append(f"receipt-owned skill is missing or unsafe: {name}")


def validate_target(root: Path) -> list[str]:
    errors: list[str] = []
    adopted = receipt_skills(root, errors)
    validate_links(root, errors)
    validate_routes(root, adopted, errors)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True)
    args = parser.parse_args()
    root = Path(args.target).expanduser().resolve()
    errors = validate_target(root)
    if errors:
        for error in errors:
            print(f"ERROR {error}", file=sys.stderr)
        return 1
    print(
        "Validated adopted skill links, receipt ownership, and managed AGENTS routes."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
