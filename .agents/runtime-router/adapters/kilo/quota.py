"""Kraken-owned optional quota adapters.

The OpenCode quota plugin is an integration input, not an ARR dependency.
This module resolves only the command named by ``OPENCODE_QUOTA_COMMAND`` and
returns redacted :class:`QuotaEvidence`; raw plugin output is never persisted
or included in errors.  A missing plugin leaves free routing usable and keeps
paid quota explicitly unknown.
"""

from __future__ import annotations

import json
import os
import shlex
import subprocess
import time
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from agent_runtime_router import Candidate, QuotaStatus
from agent_runtime_router.quota import QuotaEvidence, QuotaEvidenceStatus
from agent_runtime_router.errors import RouterInputError

MAX_OUTPUT_BYTES = 512 * 1024
MAX_TIMEOUT_SECONDS = 60.0


class QuotaAdapterError(RouterInputError):
    """A safe plugin failure code."""


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if value == value and value not in (float("inf"), float("-inf")) else None


def _command_from_env(name: str) -> list[str] | None:
    raw = os.environ.get(name, "").strip()
    if not raw:
        default_plugin = Path.home() / ".config/kilo/node_modules/@slkiser/opencode-quota/dist/bin/opencode-quota.js"
        node_bin = Path("/opt/homebrew/bin/node")
        if default_plugin.is_file() and node_bin.is_file():
            return [str(node_bin), str(default_plugin), "show", "--json"]
        return None
    try:
        command = shlex.split(raw)
    except ValueError as exc:
        raise QuotaAdapterError("quota_command_invalid") from exc
    if not command or not Path(command[0]).is_absolute():
        raise QuotaAdapterError("quota_command_not_absolute")
    if any("\x00" in item or "\n" in item or "\r" in item for item in command):
        raise QuotaAdapterError("quota_command_invalid")
    return command


def _run_plugin(command: Sequence[str], *, timeout_seconds: float) -> Mapping[str, Any]:
    if timeout_seconds <= 0 or timeout_seconds > MAX_TIMEOUT_SECONDS:
        raise QuotaAdapterError("quota_timeout_invalid")
    try:
        completed = subprocess.run(
            list(command),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=timeout_seconds,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise QuotaAdapterError("quota_timeout") from exc
    except OSError as exc:
        raise QuotaAdapterError("quota_command_failed") from exc
    if len(completed.stdout) > MAX_OUTPUT_BYTES or completed.returncode != 0:
        raise QuotaAdapterError("quota_command_failed")
    try:
        value = json.loads(completed.stdout.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise QuotaAdapterError("quota_unparseable") from exc
    if not isinstance(value, Mapping):
        raise QuotaAdapterError("quota_unparseable")
    return value


def _provider_record(payload: Mapping[str, Any], provider: str) -> Mapping[str, Any] | None:
    providers = payload.get("providers")
    if isinstance(providers, Mapping) and isinstance(providers.get(provider), Mapping):
        return providers[provider]
    direct = payload.get(provider)
    if isinstance(direct, Mapping):
        return direct
    if payload.get("provider") == provider:
        return payload
    return None


def _evidence_for(candidate: Candidate, raw: Mapping[str, Any], *, now: float, max_age_seconds: float, minimum_percent: float, harness_id: str) -> QuotaEvidence:
    observed = _number(raw.get("observedAt", raw.get("fetchedAt", raw.get("timestamp")))) or now
    if observed > now + 1:
        observed = now
    expires = observed + max_age_seconds
    percent = _number(raw.get("remainingPercent", raw.get("quotaPercent", raw.get("remaining_percent"))))
    balance = _number(raw.get("remainingBalance", raw.get("balance", raw.get("credits"))))
    currency = str(raw.get("currency"))[:12] if raw.get("currency") else None
    entries = raw.get("entries")
    if isinstance(entries, list) and entries:
        for entry in entries:
            if not isinstance(entry, Mapping):
                continue
            entry_pct = _number(entry.get("percentRemaining", entry.get("remainingPercent")))
            if entry_pct is not None:
                percent = entry_pct if percent is None else min(percent, entry_pct)
            entry_val = entry.get("value")
            if isinstance(entry_val, str) and entry_val.startswith("$"):
                try:
                    parsed_val = float(entry_val.replace("$", "").replace(",", "").strip())
                    balance = parsed_val if balance is None else min(balance, parsed_val)
                    currency = "USD"
                except ValueError:
                    pass
            elif isinstance(entry_val, (int, float)):
                balance = float(entry_val) if balance is None else min(balance, float(entry_val))

    if percent is not None and percent <= minimum_percent:
        quota_status = QuotaStatus.EXHAUSTED
    elif balance is not None and balance <= 0:
        quota_status = QuotaStatus.EXHAUSTED
    elif percent is not None or balance is not None:
        quota_status = QuotaStatus.AVAILABLE
    else:
        quota_status = QuotaStatus.UNKNOWN
    if raw.get("blocked") is True or str(raw.get("status", "")).lower() in {"blocked", "rate_limited"}:
        quota_status = QuotaStatus.BLOCKED
    return QuotaEvidence(
        candidate_id=candidate.candidate_id,
        provider=candidate.provider,
        account_scope=str(raw.get("account", raw.get("accountScope", candidate.provider)))[:120] or candidate.provider,
        source="opencode-quota-plugin",
        observed_at_epoch_seconds=observed,
        expires_at_epoch_seconds=expires,
        status=QuotaEvidenceStatus.BEST_EFFORT,
        quota_status=quota_status,
        remaining_percent=percent,
        remaining_balance=balance,
        currency=str(raw.get("currency"))[:12] if raw.get("currency") else None,
        minimum_percent=minimum_percent,
        secrets_redacted=True,
        harness_id=harness_id,
    )


def collect_quota_evidence(
    candidates: Sequence[Candidate],
    provider_policy: Mapping[str, Any],
    *,
    harness_id: str = "kilo",
    now: float | None = None,
    approve: bool = False,
    cache_path: Path | None = None,
) -> dict[str, QuotaEvidence]:
    """Collect optional account quota after explicit approval or load fresh cached evidence."""

    current = time.time() if now is None else float(now)
    settings = provider_policy.get("quota", {}) if isinstance(provider_policy, Mapping) else {}
    plugin = settings.get("plugin", {}) if isinstance(settings, Mapping) else {}
    if not isinstance(plugin, Mapping) or not plugin.get("enabled", True):
        return {}

    if cache_path is None:
        cache_path = Path.cwd() / ".agents" / "runtime-router" / "harnesses" / harness_id / "quota.json"

    max_age = max(1.0, min(float(plugin.get("maxAgeSeconds", 300)), 86_400.0))
    plan_max_age = 86_400.0 if not approve else max_age
    minimum = max(0.0, min(float(plugin.get("minimumRemainingPercent", 1)), 100.0))

    if not approve:
        if cache_path.is_file():
            try:
                cached_data = json.loads(cache_path.read_text(encoding="utf-8"))
                cached_time = float(cached_data.get("timestamp", 0))
                if current - cached_time <= plan_max_age:
                    payload = cached_data.get("payload", {})
                    result: dict[str, QuotaEvidence] = {}
                    for candidate in candidates:
                        raw = _provider_record(payload, candidate.provider)
                        if raw is None:
                            continue
                        result[candidate.candidate_id] = _evidence_for(
                            candidate,
                            raw,
                            now=current,
                            max_age_seconds=plan_max_age,
                            minimum_percent=minimum,
                            harness_id=harness_id,
                        )
                    return result
            except Exception:
                pass
        return {}

    command = _command_from_env(str(plugin.get("commandEnv", "OPENCODE_QUOTA_COMMAND")))
    if command is None:
        return {}
    try:
        payload = _run_plugin(command, timeout_seconds=float(plugin.get("timeoutSeconds", 45)))
    except QuotaAdapterError:
        return {}

    try:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        cache_path.write_text(
            json.dumps({"timestamp": current, "payload": payload}, indent=2),
            encoding="utf-8",
        )
    except Exception:
        pass

    result = {}
    for candidate in candidates:
        raw = _provider_record(payload, candidate.provider)
        if raw is None:
            continue
        result[candidate.candidate_id] = _evidence_for(
            candidate,
            raw,
            now=current,
            max_age_seconds=max_age,
            minimum_percent=minimum,
            harness_id=harness_id,
        )
    return result


__all__ = ["QuotaAdapterError", "collect_quota_evidence"]
