"""Kraken-owned Kilo catalog and evidence translation.

This module is deliberately a target adapter rather than part of ARR.  ARR
owns the Candidate contract and routing rules; Kraken owns the enabled
providers, include/blacklist patterns, model overrides, and benchmark source
configuration captured in ``provider-policy.json``.
"""

from __future__ import annotations

import json
import math
import os
import re
import signal
import subprocess
import time
from collections.abc import Iterable, Mapping, Sequence
from fnmatch import fnmatchcase
from pathlib import Path
from typing import Any

from agent_runtime_router import (
    Availability,
    Candidate,
    CostClass,
    EffortProfile,
    EffortLevel,
    QuotaStatus,
)
from agent_runtime_router.errors import RouterInputError

MAX_OUTPUT_BYTES = 4 * 1024 * 1024
MAX_CANDIDATES = 5_000
MAX_TIMEOUT_SECONDS = 900.0
DEFAULT_PROVIDER_TIMEOUT_SECONDS = 300.0
_JSON_DECODER = json.JSONDecoder()
_SAFE_ERROR = re.compile(r"^[a-z][a-z0-9._-]{0,79}$")
_FREE_SUFFIX = re.compile(r"(?:[:\s_-]free\)?$)", re.IGNORECASE)


class CatalogError(RouterInputError):
    """A bounded, redacted catalog failure."""


def _error_code(value: str) -> str:
    return value if _SAFE_ERROR.fullmatch(value) else "catalog_failed"


def _run_bounded(command: Sequence[str], *, timeout_seconds: float) -> tuple[int, bytes]:
    if not command or any(not isinstance(item, str) or not item for item in command):
        raise CatalogError("catalog_command_invalid")
    if not Path(command[0]).is_absolute():
        raise CatalogError("catalog_executable_not_absolute")
    if timeout_seconds <= 0 or timeout_seconds > MAX_TIMEOUT_SECONDS:
        raise CatalogError("catalog_timeout_invalid")
    process = subprocess.Popen(
        list(command),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        start_new_session=(os.name != "nt"),
    )
    try:
        stdout, _ = process.communicate(timeout=timeout_seconds)
    except subprocess.TimeoutExpired as exc:
        if os.name != "nt":
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            except (OSError, ProcessLookupError):
                pass
        else:
            process.kill()
        process.communicate()
        raise CatalogError("catalog_timeout") from exc
    if len(stdout) > MAX_OUTPUT_BYTES:
        raise CatalogError("catalog_output_limit")
    return process.returncode, stdout


def _json_values(output: bytes) -> list[Mapping[str, Any]]:
    """Extract only model objects; raw command output is never persisted."""

    try:
        text = output.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise CatalogError("catalog_unparseable") from exc
    text = text.strip()
    if not text:
        raise CatalogError("catalog_empty")
    values: list[Any] = []
    try:
        values.append(json.loads(text))
    except json.JSONDecodeError:
        # Kilo --verbose historically emits one JSON object per line with
        # progress text around it.  Decode only bounded object fragments.
        offset = 0
        while offset < len(text):
            start = text.find("{", offset)
            if start < 0:
                break
            try:
                value, consumed = _JSON_DECODER.raw_decode(text[start:])
            except json.JSONDecodeError:
                offset = start + 1
                continue
            values.append(value)
            offset = start + consumed
    objects: list[Mapping[str, Any]] = []
    for value in values:
        if isinstance(value, Mapping):
            nested = value.get("data", value.get("models"))
            if isinstance(nested, list):
                values_to_add: Iterable[Any] = nested
            else:
                values_to_add = (value,)
        elif isinstance(value, list):
            values_to_add = value
        else:
            continue
        for item in values_to_add:
            if isinstance(item, Mapping):
                objects.append(item)
    if not objects:
        raise CatalogError("catalog_unparseable")
    return objects


def parse_catalog_output(provider: str, output: bytes | str) -> tuple[Mapping[str, Any], ...]:
    """Parse a Kilo model listing into bounded provider/model records."""

    if not isinstance(provider, str) or not provider:
        raise CatalogError("catalog_provider_invalid")
    encoded = output.encode("utf-8") if isinstance(output, str) else output
    records = _json_values(encoded)
    deduped: dict[str, Mapping[str, Any]] = {}
    for record in records:
        model_id = record.get("id") or record.get("model")
        if not isinstance(model_id, str) or not model_id or len(model_id) > 200:
            continue
        provider_id = record.get("providerID") or record.get("provider") or provider
        if not isinstance(provider_id, str) or not provider_id:
            continue
        deduped[f"{provider_id}/{model_id}"] = record
        if len(deduped) > MAX_CANDIDATES:
            raise CatalogError("catalog_candidate_limit")
    if not deduped:
        raise CatalogError("catalog_no_models")
    return tuple(deduped.values())


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    result = float(value)
    return result if math.isfinite(result) and result >= 0 else None


def _integer(value: Any) -> int | None:
    number = _number(value)
    return int(number) if number is not None and number >= 1 else None


def _raw_capabilities(record: Mapping[str, Any]) -> tuple[frozenset[str], bool | None, bool | None]:
    capabilities = record.get("capabilities")
    if not isinstance(capabilities, Mapping):
        return frozenset({"code", "completion"}), None, None
    values = {"code", "completion"}
    tool = capabilities.get("toolcall", capabilities.get("tool_call"))
    reasoning = capabilities.get("reasoning")
    if tool is True:
        values.add("tool_call")
    if reasoning is True:
        values.add("reasoning")
    return frozenset(values), tool if isinstance(tool, bool) else None, reasoning if isinstance(reasoning, bool) else None


def _billing(route: str, provider_settings: Mapping[str, Any], model_settings: Mapping[str, Any], record: Mapping[str, Any]) -> str:
    configured = model_settings.get("billing", provider_settings.get("billing"))
    record_billing = record.get("billing")
    costs = record.get("cost")
    input_cost = _number(costs.get("input")) if isinstance(costs, Mapping) else None
    output_cost = _number(costs.get("output")) if isinstance(costs, Mapping) else None
    # Provider billing is only a fallback.  Kilo (and other gateways) can
    # expose both account-priced and explicitly free models under one
    # provider, so a verified per-model marker must win over the provider
    # default.  ``isFree`` is emitted by Kilo's model listing; the route
    # suffix remains a portable fallback for providers that encode free
    # models in their IDs.
    if record.get("isFree") is True:
        return "free"
    if route.lower().endswith(":free") or "/free" in route.lower() or provider_settings.get("freeOnly"):
        return "free"
    valid_billing = {"free", "paid", "payg", "subscription", "subscription/account-priced", "account-priced"}
    if isinstance(record_billing, str) and record_billing in valid_billing:
        return record_billing
    if isinstance(configured, str) and configured in valid_billing:
        return configured
    # A provider-level account/subscription declaration remains the fallback
    # when the listing has no per-model billing marker. Some account-backed
    # gateways advertise a zero per-token catalog price even though requests
    # consume account credits or subscription quota; the configured fallback
    # is intentionally checked above so those routes remain paid.
    if input_cost == 0 and output_cost == 0:
        return "free"
    return "unknown"


def _allowed(route: str, model: str, provider: str, provider_settings: Mapping[str, Any], blacklist: Mapping[str, Any]) -> bool:
    includes = provider_settings.get("include", ["*"])
    excludes = provider_settings.get("exclude", [])
    if not isinstance(includes, list) or not any(fnmatchcase(route, str(pattern)) or fnmatchcase(model, str(pattern)) for pattern in includes):
        return False
    if isinstance(excludes, list) and any(fnmatchcase(route, str(pattern)) or fnmatchcase(model, str(pattern)) for pattern in excludes):
        return False
    model_patterns = blacklist.get("models", []) if isinstance(blacklist, Mapping) else []
    provider_patterns = blacklist.get("providers", []) if isinstance(blacklist, Mapping) else []
    return not (
        isinstance(model_patterns, list) and any(fnmatchcase(route, str(pattern)) or fnmatchcase(model, str(pattern)) for pattern in model_patterns)
        or isinstance(provider_patterns, list) and any(fnmatchcase(provider, str(pattern)) for pattern in provider_patterns)
    )


def _quality(record: Mapping[str, Any]) -> dict[str, float]:
    values: dict[str, float] = {}
    benchmarks = record.get("benchmarks")
    aa = benchmarks.get("artificial_analysis") if isinstance(benchmarks, Mapping) else None
    evaluations = record.get("evaluations")
    for source in (aa, evaluations):
        if not isinstance(source, Mapping):
            continue
        for key, value in source.items():
            number = _number(value)
            if number is not None and len(str(key)) <= 200:
                key_text = str(key)
                aliases = {
                    "intelligence_index": "artificial_analysis_intelligence_index",
                    "coding_index": "artificial_analysis_coding_index",
                    "agentic_index": "artificial_analysis_agentic_index",
                }
                values[aliases.get(key_text, key_text)] = number
    return values


def _effort_profiles(variants: tuple[str, ...], quality: float | None, metrics: dict[str, float]) -> tuple[EffortProfile, ...]:
    native_to_effort = {
        "instant": EffortLevel.MINIMAL,
        "minimal": EffortLevel.MINIMAL,
        "low": EffortLevel.LOW,
        "medium": EffortLevel.MEDIUM,
        "high": EffortLevel.HIGH,
        "thinking": EffortLevel.HIGH,
        "xhigh": EffortLevel.XHIGH,
        "max": EffortLevel.MAX,
    }
    result: list[EffortProfile] = []
    seen: set[EffortLevel] = set()
    for native in variants:
        effort = native_to_effort.get(native.lower())
        if effort is None or effort in seen:
            continue
        seen.add(effort)
        result.append(EffortProfile(effort=effort, quality=quality, quality_metrics=metrics or None, variant=native))
    return tuple(result)


def build_candidates(raw_models: Iterable[Mapping[str, Any]], provider_policy: Mapping[str, Any]) -> tuple[Candidate, ...]:
    providers = provider_policy.get("providers", {})
    models = provider_policy.get("models", {})
    blacklist = provider_policy.get("blacklist", {})
    if not isinstance(providers, Mapping) or not isinstance(models, Mapping):
        raise CatalogError("catalog_policy_invalid")
    result: list[Candidate] = []
    for record in raw_models:
        provider = record.get("providerID") or record.get("provider")
        model = record.get("id") or record.get("model")
        if not isinstance(provider, str) or not isinstance(model, str):
            continue
        settings = providers.get(provider)
        if not isinstance(settings, Mapping) or not settings.get("enabled", True):
            continue
        route = f"{provider}/{model}"
        override = models.get(route, {})
        if not isinstance(override, Mapping) or not _allowed(route, model, provider, settings, blacklist):
            continue
        costs = record.get("cost") if isinstance(record.get("cost"), Mapping) else {}
        input_cost = _number(costs.get("input"))
        output_cost = _number(costs.get("output"))
        free_hint = (
            override.get("billing") == "free"
            or record.get("billing") == "free"
            or record.get("isFree") is True
            or route.lower().endswith(":free")
            or "/free" in route.lower()
            or settings.get("freeOnly")
        )
        # Do not allow contradictory metadata to turn a positively priced
        # route into a free candidate.  This applies to mixed providers as
        # well as providers configured free-only.
        if free_hint and (
            (input_cost is not None and input_cost > 0)
            or (output_cost is not None and output_cost > 0)
        ):
            continue
        # A free-only target provider must not turn a catalog row with a
        # positive advertised price into a free candidate. Drop it rather
        # than allowing it to bypass paid/quota policy.
        if settings.get("freeOnly") and (
            (input_cost is not None and input_cost > 0)
            or (output_cost is not None and output_cost > 0)
        ):
            continue
        billing = _billing(route, settings, override, record)
        capabilities, tool_call, reasoning = _raw_capabilities(record)
        limits = record.get("limit") if isinstance(record.get("limit"), Mapping) else {}
        context = _integer(override.get("contextLimit", limits.get("context")))
        output_limit = _integer(override.get("outputLimit", limits.get("output")))
        variants_raw = record.get("variants")
        if isinstance(variants_raw, Mapping):
            variants = tuple(str(key) for key in variants_raw if str(key))
        elif isinstance(variants_raw, list):
            variants = tuple(str(item) for item in variants_raw if str(item))
        else:
            variants = ()
        qualities = _quality(record)
        primary = override.get("quality")
        if primary is None:
            primary = qualities.get(str(override.get("qualityMetric", "artificial_analysis_intelligence_index")))
        result.append(
            Candidate(
                provider=provider,
                model=model,
                capabilities=capabilities,
                availability=Availability.AVAILABLE if str(record.get("status", "active")) in {"active", "available"} else Availability.UNKNOWN,
                cost_class=CostClass.FREE if billing == "free" else CostClass.PAID if billing != "unknown" else CostClass.UNKNOWN,
                quota_status=QuotaStatus.UNKNOWN,
                context_window=context,
                quality=_number(primary),
                effective_cost=0.0 if billing == "free" else _number(record.get("effective_cost")),
                billing=billing,
                tool_call=tool_call,
                reasoning=reasoning,
                variants=variants,
                preferred_variant=str(override["variant"]) if override.get("variant") else None,
                quality_metrics=qualities or None,
                effort_profiles=_effort_profiles(variants, _number(primary), qualities),
                max_output_tokens=output_limit,
            )
        )
    if not result:
        raise CatalogError("catalog_no_allowed_models")
    return tuple(sorted(result, key=lambda item: item.candidate_id))


def discover_candidates(executable: str | Path, provider_policy: Mapping[str, Any], *, refresh: bool = False, timeout_seconds: float = 900.0) -> tuple[Candidate, ...]:
    """Run bounded Kilo discovery for configured providers and build ARR candidates."""

    providers = provider_policy.get("providers", {})
    if not isinstance(providers, Mapping):
        raise CatalogError("catalog_policy_invalid")
    enabled = tuple(
        str(provider)
        for provider, settings in providers.items()
        if isinstance(settings, Mapping) and settings.get("enabled", True)
    )
    if not enabled:
        raise CatalogError("catalog_no_enabled_providers")
    if isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float)):
        raise CatalogError("catalog_timeout_invalid")
    timeout_seconds = float(timeout_seconds)
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0 or timeout_seconds > MAX_TIMEOUT_SECONDS:
        raise CatalogError("catalog_timeout_invalid")
    discovery_settings = provider_policy.get("discovery", {})
    if discovery_settings is None:
        discovery_settings = {}
    if not isinstance(discovery_settings, Mapping):
        raise CatalogError("catalog_policy_invalid")
    provider_timeout = _number(
        discovery_settings.get("providerTimeoutSeconds", DEFAULT_PROVIDER_TIMEOUT_SECONDS)
    )
    if provider_timeout is None or provider_timeout <= 0 or provider_timeout > MAX_TIMEOUT_SECONDS:
        raise CatalogError("catalog_timeout_invalid")
    # The adapter has one explicit outer deadline, but each provider receives a
    # target-configured multi-minute budget.  A fixed short slice per provider
    # incorrectly labels cold/network-backed listings as unavailable.  The
    # remaining-deadline calculation still guarantees that the whole adapter
    # terminates within the ARR subprocess bound.
    deadline = time.monotonic() + timeout_seconds
    all_records: list[Mapping[str, Any]] = []
    for provider in enabled:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        # Discovery must not load Kilo's optional remote/plugin layer.  That
        # layer can block independently for a provider and consume ARR's
        # entire bounded discovery budget; quota is collected by the separate
        # target-owned quota adapter.  ``--pure`` keeps model listing local to
        # Kilo's own bounded cache while preserving the provider-specific
        # catalog and policy filtering below.
        command = [str(executable), "models", str(provider), "--verbose", "--pure"]
        if refresh:
            command.append("--refresh")
        try:
            returncode, output = _run_bounded(
                command,
                timeout_seconds=min(provider_timeout, remaining),
            )
        except CatalogError:
            # One unavailable provider must not erase usable catalogs from
            # the remaining configured providers.  The per-provider bound
            # above guarantees this loop still fits the outer ARR deadline.
            continue
        if returncode != 0:
            continue
        try:
            all_records.extend(parse_catalog_output(str(provider), output))
        except CatalogError:
            continue
    if not all_records:
        raise CatalogError("catalog_discovery_failed")
    return build_candidates(all_records, provider_policy)


__all__ = ["CatalogError", "build_candidates", "discover_candidates", "parse_catalog_output"]
