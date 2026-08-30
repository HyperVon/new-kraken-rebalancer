#!/usr/bin/env python3
"""Inventory agent guidance and surface exact repeated prose candidates.

This is intentionally read-only unless --output is supplied. Its token figure
is a rough characters/4 proxy, not a tokenizer measurement or a loading claim.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
from collections import defaultdict
from pathlib import Path


EXACT_NAMES = {
    "AGENTS.md",
    "OPERATING.md",
    "CLAUDE.md",
    ".cursorrules",
    "SKILL.md",
    "copilot-instructions.md",
}
GUIDANCE_ROOTS = {".agents", ".clinerules", ".codex", ".kilo", ".opencode"}
SKIP_DIRS = {
    ".git",
    ".gradle",
    ".idea",
    ".openai",
    ".worktrees",
    "build",
    "node_modules",
    "out",
    "tmp",
}
HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
WORD_RE = re.compile(r"\S+")
LINK_RE = re.compile(r"\[[^\]]+\]\(([^)]+)\)")
FRONTMATTER_NAME_RE = re.compile(r"^name:\s*['\"]?([^'\"\n]+)", re.MULTILINE)


def is_candidate(root: Path, path: Path) -> bool:
    relative = path.relative_to(root)
    parts = relative.parts
    name = path.name

    if name in EXACT_NAMES:
        return True
    if ".cursor" in parts and "rules" in parts and path.suffix == ".mdc":
        return True
    if ".agents" in parts and path.suffix == ".md":
        return ".agents" in parts[:2] or "skills" in parts
    if any(directory in parts for directory in GUIDANCE_ROOTS) and path.suffix in {
        ".md",
        ".mdc",
    }:
        return True
    if relative == Path(".github/copilot-instructions.md"):
        return True
    return False


def scope_for(root: Path, path: Path) -> str:
    relative = path.relative_to(root)
    parts = relative.parts
    if path.name.endswith("-backlog.md") or "memory" in parts:
        return "archive/related"
    if relative in {
        Path(".agents/AGENTS.md"),
        Path(".agents/OPERATING.md"),
        Path("AGENTS.md"),
        Path("CLAUDE.md"),
        Path(".github/copilot-instructions.md"),
    }:
        return "core entrypoint"
    if ".cursor" in parts or ".clinerules" in parts:
        return "harness projection"
    if ".agents" in parts and "skills" in parts:
        return "conditional skill"
    if ".kilo" in parts or ".opencode" in parts:
        return "harness-specific"
    return "related"


def role_for(root: Path, path: Path) -> str:
    relative = path.relative_to(root)
    parts = relative.parts
    if path.name == "SKILL.md":
        return "skill"
    if ".cursor" in parts or ".clinerules" in parts:
        return "harness projection"
    if relative == Path(".agents/AGENTS.md"):
        return "canonical rules/index"
    if relative == Path(".agents/OPERATING.md"):
        return "canonical operating norms"
    if path.name in {"AGENTS.md", "OPERATING.md", "CLAUDE.md", ".cursorrules"}:
        return "entrypoint/rules"
    if "skills" in parts:
        return "skill reference"
    if path.name.endswith("-backlog.md") or "memory" in parts:
        return "archive/related guidance"
    return "harness/agent guidance"


def iter_files(root: Path):
    for directory, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(name for name in dirnames if name not in SKIP_DIRS)
        for filename in sorted(filenames):
            path = Path(directory) / filename
            if path.is_file() and is_candidate(root, path):
                yield path


def frontmatter_name(text: str) -> str:
    if not text.startswith("---\n"):
        return ""
    end = text.find("\n---", 4)
    if end < 0:
        return ""
    match = FRONTMATTER_NAME_RE.search(text[4:end])
    return match.group(1).strip() if match else ""


def headings(text: str) -> list[str]:
    return [
        match.group(2)
        for line in text.splitlines()
        if (match := HEADING_RE.match(line))
    ]


def normalized_blocks(text: str):
    in_fence = False
    blocks: list[str] = []
    current: list[str] = []
    for line in text.splitlines():
        if line.strip().startswith("```"):
            in_fence = not in_fence
            if current:
                current = []
            continue
        if in_fence or not line.strip():
            if current:
                blocks.append(" ".join(current))
                current = []
            continue
        current.append(line.strip())
    if current:
        blocks.append(" ".join(current))

    for block in blocks:
        normalized = re.sub(r"\s+", " ", block).strip()
        if len(normalized) < 100 or normalized.startswith("#"):
            continue
        if normalized.count("|") >= 2:
            continue
        yield normalized


def read_records(root: Path, scope: str):
    records = []
    blocks: defaultdict[str, list[str]] = defaultdict(list)
    for path in iter_files(root):
        file_scope = scope_for(root, path)
        if scope == "active" and file_scope == "archive/related":
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        relative = path.relative_to(root).as_posix()
        characters = len(text)
        record = {
            "path": relative,
            "scope": file_scope,
            "role": role_for(root, path),
            "lines": len(text.splitlines()),
            "words": len(WORD_RE.findall(text)),
            "characters": characters,
            "proxy_tokens": math.ceil(characters / 4),
            "headings": headings(text),
            "links": len(LINK_RE.findall(text)),
            "frontmatter_name": frontmatter_name(text),
        }
        records.append(record)
        for block in normalized_blocks(text):
            if relative not in blocks[block]:
                blocks[block].append(relative)

    repeated = []
    for block, paths in blocks.items():
        if len(paths) < 2:
            continue
        repeated.append(
            {
                "characters": len(block),
                "proxy_tokens": math.ceil(len(block) / 4),
                "possible_saved_characters": len(block) * (len(paths) - 1),
                "paths": paths,
                "text": block,
            }
        )
    repeated.sort(
        key=lambda item: (item["possible_saved_characters"], item["characters"]),
        reverse=True,
    )
    records.sort(key=lambda item: item["path"])
    return records, repeated


def as_markdown(root: Path, records, repeated) -> str:
    total = {key: sum(record[key] for record in records) for key in ("lines", "words", "characters", "proxy_tokens")}
    active = [record for record in records if record["scope"] != "archive/related"]
    active_total = {
        key: sum(record[key] for record in active)
        for key in ("lines", "words", "characters", "proxy_tokens")
    }
    lines = [
        "# Guidance inventory",
        "",
        f"Root: `{root}`",
        f"Files: **{len(records)}** | Lines: **{total['lines']:,}** | Words: **{total['words']:,}** | Characters: **{total['characters']:,}** | Rough tokens (`characters / 4`): **{total['proxy_tokens']:,}**",
        f"Active guidance subset: **{len(active)}** files | Lines: **{active_total['lines']:,}** | Words: **{active_total['words']:,}** | Rough tokens: **{active_total['proxy_tokens']:,}**",
        "",
        "| File | Scope | Role | Lines | Words | Rough tokens | Headings | Links |",
        "| :--- | :--- | :--- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for record in records:
        heading_count = len(record["headings"])
        lines.append(
            f"| `{record['path']}` | {record['scope']} | {record['role']} | {record['lines']:,} | {record['words']:,} | {record['proxy_tokens']:,} | {heading_count} | {record['links']} |"
        )
    lines.extend(["", "## Exact repeated prose candidates", ""])
    if not repeated:
        lines.append("No repeated prose blocks met the 100-character threshold.")
    else:
        for index, item in enumerate(repeated[:20], start=1):
            lines.extend(
                [
                    f"{index}. **{item['characters']:,} chars / ~{item['proxy_tokens']:,} proxy tokens**; possible duplicate saving if one copy remains: **{item['possible_saved_characters']:,} chars**",
                    f"   Files: {', '.join(f'`{path}`' for path in item['paths'])}",
                    f"   Text: {item['text'][:240]}{'...' if len(item['text']) > 240 else ''}",
                ]
            )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="Repository root to inspect")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    parser.add_argument(
        "--scope",
        choices=("active", "all"),
        default="active",
        help="Inspect active guidance (default) or include archive/related files",
    )
    parser.add_argument("--output", help="Optional output file; stdout is used otherwise")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    if not root.is_dir():
        print(f"error: root is not a directory: {root}", file=sys.stderr)
        return 2
    records, repeated = read_records(root, args.scope)
    if args.format == "json":
        payload = {
            "root": str(root),
            "scope": args.scope,
            "files": records,
            "repeated_blocks": repeated,
        }
        output = json.dumps(payload, indent=2) + "\n"
    else:
        output = as_markdown(root, records, repeated)

    if args.output:
        Path(args.output).write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
