#!/usr/bin/env python3
"""Check every Gradle/npm dependency, plugin, and the Gradle wrapper for newer
stable releases.

Parses the project's build files (no version catalog exists — versions are inline
string literals, some behind local `val`/`var` variables), resolves those
variables, and queries the relevant registries for the latest STABLE version:

  * Maven Central          -> JVM/KMP artifacts
  * Gradle Plugin Portal   -> plugin marker artifacts (fallback)
  * npm registry           -> npm(...) / devNpm(...) / yarn resolution(...) pins
  * services.gradle.org    -> Gradle wrapper distribution

Pre-releases (RC / M / alpha / beta / eap / dev / SNAPSHOT) are ignored when
choosing "latest". Exit code is 0 always; this is a read-only report.

Usage:
    ./.agents/skills/dependency-upgrade/scripts/check_updates.py
"""
from __future__ import annotations

import json
import re
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# Resolve the repo root from this script's location: <root>/.agents/skills/dependency-upgrade/scripts/
ROOT = Path(__file__).resolve().parents[4]

BUILD_FILES = [
    ROOT / "build.gradle.kts",
    ROOT / "common" / "build.gradle.kts",
    ROOT / "frontend-js" / "build.gradle.kts",
    ROOT / "settings.gradle.kts",
]
WRAPPER_PROPS = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"

TIMEOUT = 20
PRERELEASE = re.compile(r"(?i)(-rc|-m\d|alpha|beta|eap|dev|snapshot|-b\d|preview|pr\d)")

# --- version parsing -------------------------------------------------------


def split_version(v: str) -> tuple:
    """Split a version into a comparable tuple of ints, ignoring suffixes."""
    parts = re.split(r"[.\-+]", v)
    out = []
    for p in parts:
        if p.isdigit():
            out.append(int(p))
        else:
            # stop at first non-numeric component (suffix)
            break
    return tuple(out)


def is_stable(v: str) -> bool:
    return not PRERELEASE.search(v)


def newer(latest: str, current: str) -> bool:
    try:
        return split_version(latest) > split_version(current)
    except Exception:
        return latest != current


# --- build file extraction -------------------------------------------------


def resolve_vars(text: str) -> dict[str, str]:
    """Collect local `val/var xVersion = "1.2.3"` declarations."""
    vars_: dict[str, str] = {}
    for m in re.finditer(r'(?:val|var)\s+(\w+)\s*=\s*"([^"]+)"', text):
        vars_[m.group(1)] = m.group(2)
    return vars_


def subst(version: str, vars_: dict[str, str]) -> str | None:
    version = version.strip()
    m = re.fullmatch(r"\$\{?(\w+)\}?", version)
    if m:
        return vars_.get(m.group(1))
    return version if version and "$" not in version else None


def parse_maven(text: str, vars_: dict[str, str], deps: dict):
    # "group:artifact:version" inside implementation(...) / testImplementation(...) / platform(...)
    for m in re.finditer(r'"([\w.\-]+):([\w.\-]+):([^"]+)"', text):
        group, artifact, raw = m.group(1), m.group(2), m.group(3)
        ver = subst(raw, vars_)
        if ver:
            deps.setdefault(("maven", group, artifact), ver)
    # kotlin("jvm") version "2.4.0" / kotlin("multiplatform") ...
    for m in re.finditer(r'kotlin\("([\w.\-]+)"\)\s+version\s+"([^"]+)"', text):
        deps.setdefault(("maven", "org.jetbrains.kotlin", "kotlin-gradle-plugin"), m.group(2))
    # id("plugin.id") version "x"
    for m in re.finditer(r'id\("([\w.\-]+)"\)\s+version\s+"([^"]+)"', text):
        ver = subst(m.group(2), vars_)
        if ver:
            deps.setdefault(("plugin", m.group(1)), ver)


def parse_npm(text: str, deps: dict):
    # npm("pkg", "1.2.3") / devNpm(...) — package may be scoped (@scope/name)
    for m in re.finditer(r'(?:devNpm|npm)\(\s*"(@?[\w.\-/]+)"\s*,\s*"([^"]+)"', text):
        deps.setdefault(("npm", m.group(1)), m.group(2))
    # yarn resolution("pkg", "1.2.3")
    for m in re.finditer(r'resolution\(\s*"(@?[\w.\-/]+)"\s*,\s*"([^"]+)"', text):
        deps.setdefault(("npm", m.group(1)), m.group(2))


def collect_deps() -> dict:
    deps: dict = {}
    for f in BUILD_FILES:
        if not f.exists():
            continue
        text = f.read_text()
        vars_ = resolve_vars(text)
        parse_maven(text, vars_, deps)
        parse_npm(text, deps)
    return deps


def gradle_current() -> str | None:
    if not WRAPPER_PROPS.exists():
        return None
    m = re.search(r"gradle-([\d.]+)-", WRAPPER_PROPS.read_text())
    return m.group(1) if m else None


# --- registry lookups ------------------------------------------------------


def http_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "dep-check"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.load(r)


def _metadata_versions(url: str) -> list[str] | None:
    """Fetch and parse the <version> entries from a maven-metadata.xml URL."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "dep-check"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            xml = r.read().decode()
    except Exception:
        return None
    return re.findall(r"<version>([^<]+)</version>", xml)


def latest_maven(group: str, artifact: str) -> str | None:
    # repo1 maven-metadata.xml is fast and cacheable; the solr search API is
    # heavily rate-limited and unreliable under concurrency.
    path = group.replace(".", "/")
    url = f"https://repo1.maven.org/maven2/{path}/{artifact}/maven-metadata.xml"
    versions = _metadata_versions(url)
    if not versions:
        return None
    stable = [v for v in versions if is_stable(v)]
    return max(stable, key=split_version) if stable else None


def latest_plugin(plugin_id: str) -> str | None:
    # Plugin marker on the Gradle Plugin Portal maven mirror.
    marker = f"{plugin_id}.gradle.plugin"
    path = plugin_id.replace(".", "/")
    url = f"https://plugins.gradle.org/m2/{path}/{marker}/maven-metadata.xml"
    versions = _metadata_versions(url)
    if not versions:
        return None
    stable = [v for v in versions if is_stable(v)]
    return max(stable, key=split_version) if stable else None


def latest_npm(pkg: str) -> str | None:
    try:
        data = http_json(f"https://registry.npmjs.org/{pkg}/latest")
        v = data.get("version")
        return v if v and is_stable(v) else None
    except Exception:
        return None


def latest_gradle() -> str | None:
    try:
        return http_json("https://services.gradle.org/versions/current").get("version")
    except Exception:
        return None


def resolve(kind_key, current: str):
    kind = kind_key[0]
    if kind == "maven":
        _, group, artifact = kind_key
        name = f"{group}:{artifact}"
        latest = latest_maven(group, artifact)
    elif kind == "plugin":
        _, pid = kind_key
        name = f"plugin {pid}"
        latest = latest_plugin(pid) or latest_maven(pid, f"{pid}.gradle.plugin")
    else:
        _, pkg = kind_key
        name = f"npm {pkg}"
        latest = latest_npm(pkg)
    return name, current, latest


# --- main ------------------------------------------------------------------


def main() -> int:
    deps = collect_deps()
    if not deps:
        print("No dependencies parsed — run from within the repo.", file=sys.stderr)
        return 0

    rows = []
    with ThreadPoolExecutor(max_workers=12) as pool:
        futures = [pool.submit(resolve, k, v) for k, v in deps.items()]
        for fut in futures:
            rows.append(fut.result())

    # Gradle wrapper
    gc = gradle_current()
    if gc:
        rows.append(("Gradle (wrapper)", gc, latest_gradle()))

    rows.sort(key=lambda r: r[0].lower())
    name_w = max(len(r[0]) for r in rows)
    cur_w = max(len(r[1]) for r in rows)

    outdated = 0
    unknown = 0
    print(f"{'DEPENDENCY':<{name_w}}  {'CURRENT':<{cur_w}}  {'LATEST':<14}  STATUS")
    print("-" * (name_w + cur_w + 40))
    for name, current, latest in rows:
        if latest is None:
            status = "unknown"
            unknown += 1
        elif newer(latest, current):
            status = "UPDATE"
            outdated += 1
        else:
            status = "ok"
        print(f"{name:<{name_w}}  {current:<{cur_w}}  {(latest or '?'):<14}  {status}")

    print("-" * (name_w + cur_w + 40))
    print(f"{len(rows)} deps  |  {outdated} updates available  |  {unknown} unknown")
    return 0


if __name__ == "__main__":
    sys.exit(main())
