#!/usr/bin/env python3
"""Validate the .agents/skills catalog and markdown links for Kraken Rebalancer."""

from __future__ import annotations

import re
import subprocess
import sys
import unicodedata
from copy import deepcopy
from pathlib import Path
from urllib.parse import unquote

try:
    import yaml
except ImportError:
    yaml = None

if yaml is not None:
    class _CatalogLoader(yaml.SafeLoader):
        """Safe PyYAML loader with YAML 1.2 booleans and unique keys."""
        yaml_implicit_resolvers = deepcopy(yaml.SafeLoader.yaml_implicit_resolvers)

    _YAML_BOOL_TAG = "tag:yaml.org,2002:bool"
    for first_character, resolvers in _CatalogLoader.yaml_implicit_resolvers.items():
        _CatalogLoader.yaml_implicit_resolvers[first_character] = [
            (tag, pattern) for tag, pattern in resolvers if tag != _YAML_BOOL_TAG
        ]
    _CatalogLoader.add_implicit_resolver(
        _YAML_BOOL_TAG,
        re.compile(r"^(?:true|True|TRUE|false|False|FALSE)$"),
        list("tTfF"),
    )

    def _construct_unique_mapping(loader, node, deep=False):
        mapping = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node, deep=deep)
            if key in mapping:
                raise yaml.constructor.ConstructorError(
                    "while constructing a mapping",
                    node.start_mark,
                    f"found duplicate key {key!r}",
                    key_node.start_mark,
                )
            mapping[key] = loader.construct_object(value_node, deep=deep)
        return mapping

    _CatalogLoader.add_constructor(
        yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
        _construct_unique_mapping,
    )
else:
    _CatalogLoader = None


ROOT = Path(__file__).resolve().parents[2]
AGENTS_ROOT = ROOT / ".agents"
SKILLS_ROOT = AGENTS_ROOT / "skills"
AGENTS_MD = AGENTS_ROOT / "AGENTS.md"

CATALOG_LINK = re.compile(r"\[([^\]]+)\]\((?:skills/|\.agents/skills/)?([^/\s)]+)/SKILL\.md\)")
MARKDOWN_LINK = re.compile(r"\]\((?:<([^>]+)>|([^\s)]+))\)")
MAX_SKILL_NAME_LENGTH = 64
MAX_DESCRIPTION_LENGTH = 1024
MAX_COMPATIBILITY_LENGTH = 500
ALLOWED_FIELDS = frozenset(
    {"name", "description", "license", "allowed-tools", "metadata", "compatibility"}
)


def tracked_markdown_files() -> list[Path]:
    """Return tracked markdown files or disk search when git is unavailable."""
    try:
        result = subprocess.run(
            ["git", "ls-files", "--", "*.md"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        return [ROOT / line for line in result.stdout.splitlines() if line]
    except (OSError, subprocess.CalledProcessError):
        return sorted(path for path in ROOT.rglob("*.md") if ".git" not in path.parts and "node_modules" not in path.parts)


def _frontmatter_parts(content: str) -> tuple[str, str]:
    lines = content.split("\n")
    if not lines or lines[0] != "---":
        raise ValueError("frontmatter must start with YAML frontmatter (---)")

    for index, line in enumerate(lines[1:], start=1):
        if line == "---":
            return "\n".join(lines[1:index]), "\n".join(lines[index + 1 :])
        if line.startswith("---"):
            raise ValueError("frontmatter closing marker is invalid")
    raise ValueError("frontmatter closing --- is missing")


def _parse_frontmatter_fallback(frontmatter: str) -> dict:
    """Fallback simple parser when PyYAML is not installed."""
    data: dict[str, str] = {}
    current_key = None
    multiline_val = []

    for line in frontmatter.splitlines():
        if not line.strip():
            continue
        key_match = re.match(r"^([a-zA-Z0-9_-]+):\s*(?:>-\s*|>\s*|\|\s*)?(.*)$", line)
        if key_match and not line.startswith(" "):
            if current_key and multiline_val:
                data[current_key] = " ".join(multiline_val).strip()
                multiline_val = []
            current_key = key_match.group(1)
            if current_key in data:
                raise ValueError(f"found duplicate key {current_key!r}")
            val = key_match.group(2).strip()
            if val:
                multiline_val.append(val)
        elif current_key and line.startswith(" "):
            multiline_val.append(line.strip())

    if current_key and multiline_val:
        data[current_key] = " ".join(multiline_val).strip()

    return data


def _parse_frontmatter(content: str) -> tuple[dict, str]:
    frontmatter, body = _frontmatter_parts(content)
    if yaml is not None:
        try:
            metadata = yaml.load(frontmatter, Loader=_CatalogLoader)
        except yaml.YAMLError as exc:
            raise ValueError(f"invalid YAML in frontmatter: {exc}") from exc
    else:
        metadata = _parse_frontmatter_fallback(frontmatter)

    if not isinstance(metadata, dict):
        raise ValueError("frontmatter must be a YAML mapping")
    return metadata, body


def _validate_metadata(metadata: dict, skill_dir: Path | None = None) -> tuple[str, str]:
    errors: list[str] = []

    unexpected_fields = sorted(str(field) for field in metadata if field not in ALLOWED_FIELDS)
    if unexpected_fields:
        errors.append(f"Unexpected fields in frontmatter: {', '.join(unexpected_fields)}")

    name: str | None = None
    if "name" not in metadata:
        errors.append("Missing required field in frontmatter: name")
    elif not isinstance(metadata["name"], str) or not metadata["name"].strip():
        errors.append("Field 'name' must be a non-empty string")
    else:
        raw_name = metadata["name"]
        name = unicodedata.normalize("NFKC", raw_name)
        if len(raw_name) > MAX_SKILL_NAME_LENGTH or len(name) > MAX_SKILL_NAME_LENGTH:
            errors.append(
                f"Skill name '{name}' exceeds {MAX_SKILL_NAME_LENGTH} character limit ({len(name)} chars)"
            )
        if raw_name != raw_name.lower() or name != name.lower():
            errors.append(f"Skill name '{raw_name}' must be lowercase")
        if raw_name.startswith("-") or raw_name.endswith("-"):
            errors.append("Skill name cannot start or end with a hyphen")
        if "--" in raw_name:
            errors.append("Skill name cannot contain consecutive hyphens")
        if not all(character.isalnum() or character == "-" for character in raw_name):
            errors.append(
                f"Skill name '{raw_name}' contains invalid characters. Only letters, digits, and hyphens are allowed."
            )
        if skill_dir is not None:
            directory_name = unicodedata.normalize("NFKC", skill_dir.name)
            if directory_name != name:
                errors.append(f"Directory name '{skill_dir.name}' must match skill name '{name}'")

    description: str | None = None
    if "description" not in metadata:
        errors.append("Missing required field in frontmatter: description")
    elif not isinstance(metadata["description"], str) or not metadata["description"].strip():
        errors.append("Field 'description' must be a non-empty string")
    else:
        description = metadata["description"].strip()
        if len(metadata["description"]) > MAX_DESCRIPTION_LENGTH:
            errors.append(
                f"Description exceeds {MAX_DESCRIPTION_LENGTH} character limit ({len(metadata['description'])} chars)"
            )

    for field in ("license", "allowed-tools", "compatibility"):
        if field in metadata:
            if not isinstance(metadata[field], str):
                errors.append(f"Field '{field}' must be a string")
            elif field == "compatibility" and len(metadata[field]) > MAX_COMPATIBILITY_LENGTH:
                errors.append(
                    f"Field 'compatibility' exceeds {MAX_COMPATIBILITY_LENGTH} character limit"
                )

    if errors:
        raise ValueError("; ".join(errors))
    assert name is not None
    assert description is not None
    return name, description


def catalog_names(agents_md: Path) -> list[str]:
    """Return visible skill links from the Skill index table in .agents/AGENTS.md."""
    names = []
    if not agents_md.is_file():
        return names
    in_index_section = False
    for line in agents_md.read_text(encoding="utf-8").splitlines():
        if line.startswith("## Skill index"):
            in_index_section = True
            continue
        elif in_index_section and line.startswith("## "):
            break
        if in_index_section:
            for match in CATALOG_LINK.finditer(line):
                target = match.group(2)
                names.append(unicodedata.normalize("NFKC", unquote(target)))
    return names


def parse_frontmatter(path: Path, *, check_directory: bool = False) -> tuple[str, str]:
    """Return validated skill name and description from one SKILL.md file."""
    metadata, body = _parse_frontmatter(path.read_text(encoding="utf-8"))
    name, description = _validate_metadata(metadata, path.parent if check_directory else None)
    if not body.strip():
        raise ValueError("skill body is empty")
    return name, description


def local_link_errors(path: Path) -> list[str]:
    errors: list[str] = []
    text = path.read_text(encoding="utf-8")
    for match in MARKDOWN_LINK.finditer(text):
        target = (match.group(1) or match.group(2) or "").strip()
        if not target or target.startswith(("#", "http://", "https://", "mailto:", "//")):
            continue
        # Skip special schemes or GitHub web branch links (../../tree/...)
        if "tree/" in target or (":" in target and not target.startswith("./") and not target.startswith("../")):
            continue
        target = unquote(target.split("#", 1)[0].split("?", 1)[0])
        if not target:
            continue
        candidate = (path.parent / target).resolve()
        try:
            candidate.relative_to(ROOT)
        except ValueError:
            errors.append(f"{path.relative_to(ROOT)}: link escapes repository: {target}")
            continue
        if not candidate.exists():
            errors.append(f"{path.relative_to(ROOT)}: missing link target: {target}")
    return errors


def validate() -> list[str]:
    errors: list[str] = []
    if not SKILLS_ROOT.is_dir():
        return [".agents/skills/ directory is missing"]

    skills = sorted(path for path in SKILLS_ROOT.iterdir() if path.is_dir())
    names: dict[str, Path] = {}
    for skill_dir in skills:
        skill_file = skill_dir / "SKILL.md"
        if not skill_file.is_file():
            errors.append(f"{skill_dir.relative_to(ROOT)}: SKILL.md is missing")
            continue
        try:
            name, _description = parse_frontmatter(skill_file, check_directory=True)
        except ValueError as exc:
            errors.append(f"{skill_file.relative_to(ROOT)}: {exc}")
            continue
        if name in names:
            errors.append(f"duplicate skill name {name!r}: {names[name]} and {skill_file}")
        names[name] = skill_file

    if AGENTS_MD.is_file():
        catalog_entries = set(catalog_names(AGENTS_MD))
        skill_names = set(names.keys())
        missing_in_index = skill_names - catalog_entries
        for name in sorted(missing_in_index):
            errors.append(f".agents/AGENTS.md: skill {name!r} missing from index table")
        unknown_in_index = catalog_entries - skill_names
        for name in sorted(unknown_in_index):
            errors.append(f".agents/AGENTS.md: index lists removed or unknown skill {name!r}")

    for markdown in tracked_markdown_files():
        if markdown.exists():
            errors.extend(local_link_errors(markdown))
    return errors


def main() -> int:
    errors = validate()
    if errors:
        print("Skill catalog validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    count = sum(1 for path in SKILLS_ROOT.iterdir() if path.is_dir())
    print(f"Skill catalog validation passed: {count} skills verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
