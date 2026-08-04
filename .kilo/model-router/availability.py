"""Quota-plugin integration and secret-free runtime route cooldowns."""

from __future__ import annotations

import json
import os
import re
import shlex
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any, Mapping

import fileio


DEFAULT_COOLDOWN_PATH = Path.home() / ".cache" / "kilo" / "model-router" / "availability.json"


def _quota_config(config: Mapping[str, Any]) -> Mapping[str, Any]:
    value = config.get("quota", {})
    return value if isinstance(value, Mapping) else {}


def _plugin_command(config: Mapping[str, Any]) -> list[str] | None:
    settings = _quota_config(config).get("plugin", {})
    settings = settings if isinstance(settings, Mapping) else {}
    if not settings.get("enabled", True):
        return None
    environment_name = str(settings.get("commandEnv", "OPENCODE_QUOTA_COMMAND"))
    configured = os.environ.get(environment_name)
    if configured:
        return shlex.split(configured)
    executable = shutil.which("opencode-quota")
    if executable:
        return [executable]

    package_root = Path.home() / ".cache" / "kilo" / "packages"
    matches = list(
        package_root.glob(
            "@slkiser/opencode-quota@*/node_modules/@slkiser/opencode-quota/dist/bin/opencode-quota.js"
        )
    )
    if not matches:
        return None
    package = max(matches, key=lambda path: path.stat().st_mtime)
    return ["node", str(package)]


def _run_json(command: list[str], arguments: list[str], timeout: int) -> dict[str, Any] | None:
    try:
        completed = subprocess.run(
            [*command, *arguments],
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        payload = json.loads(completed.stdout)
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError):
        return None
    return payload if isinstance(payload, dict) else None


def _as_provider_map(value: Any) -> dict[str, Mapping[str, Any]]:
    if isinstance(value, Mapping):
        return {str(key): item for key, item in value.items() if isinstance(item, Mapping)}
    if isinstance(value, list):
        return {
            str(item["id"]): item
            for item in value
            if isinstance(item, Mapping) and isinstance(item.get("id"), str)
        }
    return {}


def _provider_quota(
    provider: str,
    status: Mapping[str, Any] | None,
    show: Mapping[str, Any] | None,
    live_probes: Mapping[str, Mapping[str, Any]],
    max_age_seconds: float,
    minimum_remaining_percent: float,
) -> dict[str, Any]:
    status_row = _as_provider_map(status.get("providers") if status else {}).get(provider)
    show_row = _as_provider_map(show.get("providers") if show else {}).get(provider)
    live_row = live_probes.get(provider)

    if status_row and status_row.get("available") is False:
        return {"state": "unavailable", "source": "opencode-quota"}
    if live_row and live_row.get("ok") is False:
        return {"state": "unavailable", "source": "opencode-quota"}
    if show_row and show_row.get("status") not in {None, "ok"}:
        return {"state": "unknown", "source": "opencode-quota"}

    cache_age = float(show.get("cacheAgeSeconds", float("inf"))) if show else float("inf")
    if cache_age > max_age_seconds:
        return {"state": "unknown", "source": "opencode-quota", "cache_age_seconds": cache_age}

    percentages = []
    if show_row:
        for entry in show_row.get("entries", []):
            if isinstance(entry, Mapping) and isinstance(entry.get("percentRemaining"), (int, float)):
                percentages.append(float(entry["percentRemaining"]))
    if not percentages:
        return {"state": "unknown", "source": "opencode-quota", "cache_age_seconds": cache_age}

    remaining = min(percentages)
    state = "insufficient" if remaining <= minimum_remaining_percent else "sufficient"
    return {
        "state": state,
        "source": "opencode-quota",
        "remaining_percent": remaining,
        "cache_age_seconds": cache_age,
    }


def _cooldown_path(config: Mapping[str, Any]) -> Path:
    value = _quota_config(config).get("cooldownPath")
    return Path(str(value)).expanduser() if value else DEFAULT_COOLDOWN_PATH


def _load_cooldowns(config: Mapping[str, Any]) -> dict[str, Any]:
    path = _cooldown_path(config)
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"routes": {}, "providers": {}}
    return {
        "routes": payload.get("routes", {}) if isinstance(payload, Mapping) else {},
        "providers": payload.get("providers", {}) if isinstance(payload, Mapping) else {},
    }


def _save_cooldowns(config: Mapping[str, Any], state: Mapping[str, Any]) -> None:
    path = _cooldown_path(config)
    try:
        fileio.atomic_write(path, json.dumps(state, indent=2))
    except OSError:
        pass


def snapshot(config: Mapping[str, Any]) -> dict[str, Any]:
    """Return quota-plugin state plus active secret-free cooldowns."""
    settings = _quota_config(config)
    plugin_settings = settings.get("plugin", {})
    plugin_settings = plugin_settings if isinstance(plugin_settings, Mapping) else {}
    timeout = int(plugin_settings.get("timeoutSeconds", 45))
    max_age = float(plugin_settings.get("maxAgeSeconds", 300))
    minimum = float(plugin_settings.get("minimumRemainingPercent", 1))
    command = _plugin_command(config)
    warnings: list[str] = []
    status: dict[str, Any] | None = None
    show: dict[str, Any] | None = None
    if command:
        status = _run_json(command, ["status", "--json"], timeout)
        show = _run_json(command, ["show", "--json"], timeout)
        if status is None and show is None:
            warnings.append("opencode-quota returned no JSON diagnostics")
    elif plugin_settings.get("enabled", True):
        warnings.append("opencode-quota command was not found")

    live_probe_map = _as_provider_map(status.get("liveProbes") if status else {})
    providers = {
        provider: _provider_quota(provider, status, show, live_probe_map, max_age, minimum)
        for provider in config.get("providers", {})
    }
    state = _load_cooldowns(config)
    now = time.time()
    for scope in ("routes", "providers"):
        state[scope] = {
            key: value
            for key, value in state[scope].items()
            if isinstance(value, Mapping) and float(value.get("blocked_until", 0)) > now
        }
    if state["routes"] or state["providers"]:
        _save_cooldowns(config, state)
    return {"providers": providers, "cooldowns": state, "warnings": warnings}


def apply_to_candidate(candidate: Any, availability: Mapping[str, Any] | None) -> None:
    info = (availability or {}).get("providers", {}).get(candidate.provider, {})
    candidate.quota_state = str(info.get("state", "unknown"))
    candidate.quota_percent = info.get("remaining_percent")
    candidate.quota_source = str(info.get("source", "unavailable"))
    cooldowns = (availability or {}).get("cooldowns", {})
    route_cooldown = cooldowns.get("routes", {}).get(candidate.route, {})
    provider_cooldown = cooldowns.get("providers", {}).get(candidate.provider, {})
    blocked_until = max(
        float(route_cooldown.get("blocked_until", 0)),
        float(provider_cooldown.get("blocked_until", 0)),
    )
    if blocked_until > time.time():
        candidate.quota_state = "blocked"
        candidate.quota_source = str(
            route_cooldown.get("reason") or provider_cooldown.get("reason") or "runtime cooldown"
        )
        candidate.quota_blocked_until = blocked_until


def failure_kind(output: str) -> str | None:
    text = output.lower()
    if re.search(r"\b(401|unauthorized|invalid (?:api )?key|authentication)\b", text):
        return "authentication"
    if re.search(r"\b(402|payment required|insufficient credits?|out of credits?|balance|credit limit)\b", text):
        return "credits"
    if re.search(r"\b(410|end[- ]of[- ]life|no longer available)\b", text):
        return "model_eol"
    if re.search(r"\b(429|rate[- ]?limit|too many requests|resource exhausted|retry[- ]?after)\b", text):
        return "rate_limit"
    if re.search(r"\b(502|503|provider unavailable|provider overloaded|service unavailable)\b", text):
        return "provider_unavailable"
    return None


def retry_after_seconds(output: str) -> int | None:
    match = re.search(r"retry[- ]?after[^0-9]*(\d+)\s*(s|sec|seconds|m|min|minutes)?", output, re.IGNORECASE)
    if not match:
        return None
    value = int(match.group(1))
    unit = (match.group(2) or "s").lower()
    return value * (60 if unit.startswith("m") else 1)


def record_failure(config: Mapping[str, Any], route: str, provider: str, kind: str, output: str) -> int:
    settings = _quota_config(config).get("cooldown", {})
    settings = settings if isinstance(settings, Mapping) else {}
    state = _load_cooldowns(config)
    existing = state["routes"].get(route, {})
    attempts = int(existing.get("attempts", 0)) + 1
    retry_after = retry_after_seconds(output)
    if kind == "rate_limit":
        base = int(settings.get("rateLimitSeconds", 60))
        seconds = retry_after or min(base * (2 ** min(attempts - 1, 5)), int(settings.get("maxSeconds", 3600)))
    elif kind == "credits":
        seconds = int(settings.get("creditsSeconds", 3600))
    elif kind == "provider_unavailable":
        seconds = int(settings.get("providerUnavailableSeconds", 300))
    elif kind == "model_eol":
        seconds = int(settings.get("modelEolSeconds", 86400))
    elif kind == "report_contract":
        seconds = int(settings.get("reportContractSeconds", 300))
    else:
        seconds = int(settings.get("authenticationSeconds", 3600))
    blocked_until = time.time() + max(1, seconds)
    entry = {"blocked_until": blocked_until, "reason": kind, "attempts": attempts}
    state["routes"][route] = entry
    if kind in {"rate_limit", "credits", "provider_unavailable", "authentication", "report_contract"}:
        state["providers"][provider] = entry
    _save_cooldowns(config, state)
    return seconds
