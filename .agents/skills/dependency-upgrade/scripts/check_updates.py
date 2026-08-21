#!/usr/bin/env python3
"""Check every Gradle/npm dependency, plugin, and the Gradle wrapper for newer
stable releases.

Parses the project's version catalog (gradle/libs.versions.toml) and build files,
resolves version variables and security floors, and queries the relevant
registries for the latest STABLE version:

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
import ssl
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# Resolve the repo root from this script's location: <root>/.agents/skills/dependency-upgrade/scripts/
ROOT = Path(__file__).resolve().parents[4]

LIBS_TOML = ROOT / "gradle" / "libs.versions.toml"
BUILD_FILES = [
    ROOT / "build.gradle.kts",
    ROOT / "common" / "build.gradle.kts",
    ROOT / "frontend-js" / "build.gradle.kts",
    ROOT / "engine" / "build.gradle.kts",
    ROOT / "codegen" / "build.gradle.kts",
    ROOT / "settings.gradle.kts",
]
WRAPPER_PROPS = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"

TIMEOUT = 20
PRERELEASE = re.compile(r"(?i)(-rc|-m\d|alpha|beta|eap|dev|snapshot|-b\d|preview|pr\d)")

try:
    SSL_CTX = ssl.create_default_context()
except Exception:
    SSL_CTX = ssl._create_unverified_context()

FALLBACK_SSL_CTX = ssl._create_unverified_context()

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


def satisfies_range(v: str, rng: str) -> bool:
    sv = split_version(v)
    parts = rng.strip().split()
    for part in parts:
        if part.startswith(">="):
            if sv < split_version(part[2:]):
                return False
        elif part.startswith(">"):
            if sv <= split_version(part[1:]):
                return False
        elif part.startswith("<="):
            if sv > split_version(part[2:]):
                return False
        elif part.startswith("<"):
            if sv >= split_version(part[1:]):
                return False
        elif part.startswith("^"):
            base = split_version(part[1:])
            if sv < base:
                return False
            upper = (base[0] + 1,) if base[0] > 0 else (0, (base[1] + 1 if len(base) > 1 else 1))
            if sv >= upper:
                return False
    return True


def newer(latest: str, current: str) -> bool:
    if current in ("managed", "?"):
        return False
    if any(op in current for op in (">=", "<=", ">", "<", "^", "~")):
        return not satisfies_range(latest, current)
    try:
        return split_version(latest) > split_version(current)
    except Exception:
        return latest != current


# --- build file extraction -------------------------------------------------


def extract_version(val, versions: dict[str, str]) -> str | None:
    if isinstance(val, str):
        return val
    if isinstance(val, dict):
        if "ref" in val:
            return versions.get(val["ref"])
        if "strictly" in val:
            return str(val["strictly"])
        if "prefer" in val:
            return str(val["prefer"])
        if "require" in val:
            return str(val["require"])
    return None


def parse_toml_catalog(deps: dict):
    if not LIBS_TOML.exists():
        return
    text = LIBS_TOML.read_text()
    try:
        import tomllib

        data = tomllib.loads(text)
    except Exception:
        data = {}

    versions = data.get("versions", {})
    if not versions:
        for m in re.finditer(r'(\w+)\s*=\s*"([^"]+)"', text):
            versions[m.group(1)] = m.group(2)

    libraries = data.get("libraries", {})
    for lib_key, lib_val in libraries.items():
        if isinstance(lib_val, dict):
            module = lib_val.get("module", "")
            ver_obj = lib_val.get("version")
            ver = extract_version(ver_obj, versions)
            if module and ":" in module:
                group, artifact = module.split(":", 1)
                deps.setdefault(("maven", group, artifact), str(ver or "managed"))
        elif isinstance(lib_val, str) and ":" in lib_val:
            parts = lib_val.split(":")
            if len(parts) >= 3:
                deps.setdefault(("maven", parts[0], parts[1]), parts[2])
            elif len(parts) == 2:
                deps.setdefault(("maven", parts[0], parts[1]), "managed")

    plugins = data.get("plugins", {})
    for plug_key, plug_val in plugins.items():
        if isinstance(plug_val, dict):
            pid = plug_val.get("id")
            ver_obj = plug_val.get("version")
            ver = extract_version(ver_obj, versions)
            if pid:
                deps.setdefault(("plugin", pid), str(ver or "managed"))


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

    # Security floors
    if "nettySecurityFloor" in vars_:
        deps.setdefault(("maven", "io.netty", "netty-bom"), vars_["nettySecurityFloor"])
    if "httpCoreSecurityFloor" in vars_:
        deps.setdefault(("maven", "org.apache.httpcomponents.core5", "httpcore5"), vars_["httpCoreSecurityFloor"])
    if "httpClientSecurityFloor" in vars_:
        deps.setdefault(("maven", "org.apache.httpcomponents.client5", "httpclient5"), vars_["httpClientSecurityFloor"])


def parse_npm(text: str, deps: dict):
    # npm("pkg", "1.2.3") / devNpm(...) — package may be scoped (@scope/name)
    for m in re.finditer(r'(?:devNpm|npm)\(\s*"(@?[\w.\-/]+)"\s*,\s*"([^"]+)"', text):
        deps.setdefault(("npm", m.group(1)), m.group(2))
    # yarn resolution("pkg", "1.2.3")
    for m in re.finditer(r'resolution\(\s*"(@?[\w.\-/]+)"\s*,\s*"([^"]+)"', text):
        deps.setdefault(("npm", m.group(1)), m.group(2))


def collect_deps() -> dict:
    deps: dict = {}
    parse_toml_catalog(deps)
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


def fetch_url(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "dep-check"})
    try:
        with urllib.request.urlopen(req, context=SSL_CTX, timeout=TIMEOUT) as r:
            return r.read()
    except Exception:
        with urllib.request.urlopen(req, context=FALLBACK_SSL_CTX, timeout=TIMEOUT) as r:
            return r.read()


def http_json(url: str):
    return json.loads(fetch_url(url).decode())


def _metadata_versions(url: str) -> list[str] | None:
    """Fetch and parse the <version> entries from a maven-metadata.xml URL."""
    try:
        xml = fetch_url(url).decode()
    except Exception:
        return None
    return re.findall(r"<version>([^<]+)</version>", xml)


def latest_maven(group: str, artifact: str) -> str | None:
    path = group.replace(".", "/")
    url = f"https://repo1.maven.org/maven2/{path}/{artifact}/maven-metadata.xml"
    versions = _metadata_versions(url)
    if not versions:
        return None
    stable = [v for v in versions if is_stable(v)]
    return max(stable, key=split_version) if stable else None


def latest_plugin(plugin_id: str) -> str | None:
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
        elif current == "?":
            status = "managed"
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
