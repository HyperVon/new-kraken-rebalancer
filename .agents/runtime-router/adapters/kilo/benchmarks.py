"""Kraken-owned benchmark evidence bridge for ARR.

The reusable router owns the ``QualityEvidence`` contract.  Kraken owns which
benchmark source is enabled, the credential environment variable, model
aliases, and the target-local cache.  This module therefore accepts only an
injected bounded fetcher in tests and, in production, reads an API key from
the named environment variable without opening credential files.  Raw
benchmark responses are never written to disk or included in a report.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import tempfile
import time
import ssl
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping, Sequence
from dataclasses import replace
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any

from agent_runtime_router import Candidate
from agent_runtime_router.evidence import FallbackProvenance, Freshness
from agent_runtime_router.quality import QualityEvidence
from agent_runtime_router.evidence import EvidenceStatus

MAX_RESPONSE_BYTES = 4 * 1024 * 1024
MAX_PAGES = 10
MAX_RECORDS = 5_000
MAX_TIMEOUT_SECONDS = 30.0
DEFAULT_TTL_SECONDS = 86_400.0
_SAFE_METRIC = re.compile(r"^[a-z][a-z0-9_.-]{0,99}$")
_INDEX_ALIASES = {
    "intelligence_index": "artificial_analysis_intelligence_index",
    "coding_index": "artificial_analysis_coding_index",
    "agentic_index": "artificial_analysis_agentic_index",
}


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Reject redirects so a configured benchmark host cannot change scope."""

    def redirect_request(self, request: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> Any:
        raise urllib.error.HTTPError(request.full_url, code, "redirect_rejected", headers, None)


class BenchmarkError(RuntimeError):
    """A bounded, redacted benchmark-source failure."""


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    number = float(value)
    return number if math.isfinite(number) and number >= 0 else None


def _safe_metric(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = _INDEX_ALIASES.get(value, value)
    return normalized if _SAFE_METRIC.fullmatch(normalized) else None


def _bounded_json(response: Any) -> Any:
    """Read and decode a response while retaining no unbounded body."""

    try:
        body = response.read(MAX_RESPONSE_BYTES + 1)
    finally:
        response.close()
    if not isinstance(body, (bytes, bytearray)) or len(body) > MAX_RESPONSE_BYTES:
        raise BenchmarkError("benchmark_response_limit")
    try:
        return json.loads(bytes(body).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BenchmarkError("benchmark_response_invalid") from exc


def _fetch_json(
    url: str,
    *,
    headers: Mapping[str, str] | None = None,
    timeout_seconds: float = 20.0,
    fetcher: Callable[..., Any] | None = None,
) -> Any:
    if timeout_seconds <= 0 or timeout_seconds > MAX_TIMEOUT_SECONDS:
        raise BenchmarkError("benchmark_timeout_invalid")
    if fetcher is not None:
        try:
            return fetcher(url=url, headers=dict(headers or {}), timeout_seconds=timeout_seconds)
        except BenchmarkError:
            raise
        except Exception as exc:
            raise BenchmarkError("benchmark_fetch_failed") from exc
    request = urllib.request.Request(url, headers=dict(headers or {"Accept": "application/json"}))
    # Some standalone Python builds (notably the macOS framework build) ship
    # without an OpenSSL CA bundle even though the host has one.  Use an
    # existing trusted bundle when available; never disable certificate
    # verification or fall back to an unverified context.
    context: ssl.SSLContext
    configured_ca = os.environ.get("SSL_CERT_FILE")
    if configured_ca and Path(configured_ca).is_file():
        context = ssl.create_default_context(cafile=configured_ca)
    elif Path("/etc/ssl/cert.pem").is_file():
        context = ssl.create_default_context(cafile="/etc/ssl/cert.pem")
    else:
        context = ssl.create_default_context()
    try:
        opener = urllib.request.build_opener(
            _NoRedirect(), urllib.request.HTTPSHandler(context=context)
        )
        with opener.open(request, timeout=timeout_seconds) as response:
            return _bounded_json(response)
    except (OSError, ValueError, urllib.error.URLError, urllib.error.HTTPError) as exc:
        raise BenchmarkError("benchmark_fetch_failed") from exc


def _quality_values(raw: Mapping[str, Any]) -> dict[str, float]:
    values: dict[str, float] = {}
    sources: list[Any] = [raw.get("evaluations")]
    benchmarks = raw.get("benchmarks")
    if isinstance(benchmarks, Mapping):
        sources.append(benchmarks.get("artificial_analysis"))
    sources.append(raw)
    for source in sources:
        if not isinstance(source, Mapping):
            continue
        for key, value in source.items():
            metric = _safe_metric(key)
            number = _number(value)
            if metric is not None and number is not None:
                values[metric] = number
    return values


def _has_benchmark_metrics(raw: Mapping[str, Any]) -> bool:
    return any(key.startswith("artificial_analysis_") for key in _quality_values(raw))


def _identity_values(raw: Mapping[str, Any]) -> tuple[str, ...]:
    values: list[str] = []
    for key in ("slug", "id", "model", "name"):
        value = raw.get(key)
        if isinstance(value, str) and value:
            values.append(value)
    creator = raw.get("model_creator")
    creator_name = creator.get("name") if isinstance(creator, Mapping) else creator
    if isinstance(creator_name, str) and creator_name:
        values.extend(f"{creator_name}/{item}" for item in tuple(values))
    return tuple(values)


def _normalize_identity(value: str) -> str:
    value = re.sub(r"[\s:_-]*\(?free\)?$", "", value, flags=re.IGNORECASE)
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def _configured_alias_matches(alias: str, identities: Sequence[str]) -> bool:
    normalized_alias = _normalize_identity(alias)
    return any(
        alias == identity
        or alias == identity.rsplit("/", 1)[-1]
        or normalized_alias == _normalize_identity(identity.rsplit("/", 1)[-1])
        for identity in identities
    )


def _match_record(candidate: Candidate, override: Mapping[str, Any], records: Sequence[Mapping[str, Any]]) -> Mapping[str, Any] | None:
    configured_values: list[str] = []
    configured = override.get("aaSlug")
    if isinstance(configured, str) and configured:
        configured_values.append(configured)
    configured_many = override.get("aaSlugs")
    if isinstance(configured_many, list):
        configured_values.extend(
            item for item in configured_many if isinstance(item, str) and item
        )
    # Prefer an alias that actually carries quality metrics. This lets the
    # primary AA feed use ``hy3`` while the explicit OpenRouter fallback uses
    # its published ``hy3-preview`` identity, without silently binding a
    # context-only record.
    first_match: Mapping[str, Any] | None = None
    for alias in dict.fromkeys(configured_values):
        for record in records:
            if not _configured_alias_matches(alias, _identity_values(record)):
                continue
            if first_match is None:
                first_match = record
            if _has_benchmark_metrics(record):
                return record
    if first_match is not None:
        return first_match
    candidate_values = (candidate.model, candidate.candidate_id)
    normalized = {_normalize_identity(value) for value in candidate_values if value}
    best: tuple[float, Mapping[str, Any]] | None = None
    for record in records:
        identities = _identity_values(record)
        record_normalized = {_normalize_identity(value) for value in identities if value}
        if normalized.intersection(record_normalized):
            return record
        if not normalized or not record_normalized:
            continue
        score = max(
            SequenceMatcher(None, left, right).ratio()
            for left in normalized
            for right in record_normalized
        )
        if best is None or score > best[0]:
            best = (score, record)
    return best[1] if best is not None and best[0] >= 0.82 else None


def _records_from_payload(payload: Any) -> list[Mapping[str, Any]]:
    if isinstance(payload, Mapping):
        raw = payload.get("data", payload.get("models", []))
    else:
        raw = payload
    if not isinstance(raw, list):
        raise BenchmarkError("benchmark_payload_invalid")
    records = [item for item in raw if isinstance(item, Mapping)]
    if not records or len(records) > MAX_RECORDS:
        raise BenchmarkError("benchmark_record_limit")
    return records


def _source_digest(settings: Mapping[str, Any], model_settings: Mapping[str, Any] | None = None) -> str:
    aliases: dict[str, list[str]] = {}
    if isinstance(model_settings, Mapping):
        for candidate_id, override in model_settings.items():
            if not isinstance(candidate_id, str) or not isinstance(override, Mapping):
                continue
            values: list[str] = []
            aa_slug = override.get("aaSlug")
            if isinstance(aa_slug, str) and aa_slug:
                values.append(aa_slug)
            aa_slugs = override.get("aaSlugs")
            if isinstance(aa_slugs, list):
                values.extend(
                    item for item in aa_slugs if isinstance(item, str) and item
                )
            if values:
                aliases[candidate_id] = list(dict.fromkeys(values))
    safe = {
        "matching_version": 2,
        "aa_enabled": bool(settings.get("enabled", True)),
        "api_url": str(settings.get("baseUrl", "https://artificialanalysis.ai/api/v2")),
        "cache_hours": float(settings.get("cacheHours", 24)),
        # Model aliases are part of the evidence contract.  Adding a new
        # route (for example Kilo's free Hy3 route) must invalidate an older
        # quality cache that was created before that route existed.
        "model_aliases": aliases,
    }
    return hashlib.sha256(json.dumps(safe, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _cache_path(target: Path) -> Path:
    path = (target / ".agents/runtime-router/harnesses/kilo/quality.json").resolve()
    try:
        path.relative_to(target.resolve())
    except ValueError as exc:
        raise BenchmarkError("quality_cache_outside_target") from exc
    return path


def _load_cache(
    path: Path, *, source_digest: str, now: float
) -> tuple[dict[str, Mapping[str, float]], str, bool, float, float] | None:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(value, Mapping) or value.get("schema_version") != 1:
        return None
    if value.get("source_digest") != source_digest:
        return None
    if not isinstance(value.get("expires_at_epoch_seconds"), (int, float)) or float(value["expires_at_epoch_seconds"]) <= now:
        return None
    records = value.get("records")
    if not isinstance(records, Mapping):
        return None
    source = value.get("source")
    if not isinstance(source, str) or not source:
        return None
    observed = _number(value.get("observed_at_epoch_seconds"))
    expires = _number(value.get("expires_at_epoch_seconds"))
    if observed is None or expires is None or expires <= observed:
        return None
    return (
        dict(records),
        source,
        bool(value.get("fallback_used", False)),
        observed,
        expires,
    )


def _write_cache(path: Path, *, source_digest: str, source: str, fallback_used: bool, records: Mapping[str, Mapping[str, float]], observed: float, expires: float) -> None:
    value = {
        "schema_version": 1,
        "source": source,
        "source_digest": source_digest,
        "fallback_used": fallback_used,
        "observed_at_epoch_seconds": observed,
        "expires_at_epoch_seconds": expires,
        "records": {str(key): {str(metric): float(score) for metric, score in item.items()} for key, item in records.items()},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, prefix=".quality-", delete=False) as handle:
        temporary = Path(handle.name)
        json.dump(value, handle, sort_keys=True, separators=(",", ":"))
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def _fetch_sources(settings: Mapping[str, Any], *, refresh: bool, fetcher: Callable[..., Any] | None) -> tuple[list[Mapping[str, Any]], str, bool]:
    if not settings.get("enabled", True):
        return [], "disabled", False
    base = str(settings.get("baseUrl", "https://artificialanalysis.ai/api/v2")).rstrip("/")
    api_env = str(settings.get("apiKeyEnv", "ARTIFICIAL_ANALYSIS_API_KEY"))
    api_key = os.environ.get(api_env)
    if api_key:
        records: list[Mapping[str, Any]] = []
        try:
            for page in range(1, MAX_PAGES + 1):
                query = urllib.parse.urlencode({"page": page})
                payload = _fetch_json(f"{base}/language/models/free?{query}", headers={"Accept": "application/json", "x-api-key": api_key}, fetcher=fetcher)
                page_records = _records_from_payload(payload)
                records.extend(page_records)
                pagination = payload.get("pagination") if isinstance(payload, Mapping) else None
                if not isinstance(pagination, Mapping) or not pagination.get("has_more"):
                    break
            if records:
                return records[:MAX_RECORDS], "artificial-analysis", False
        except BenchmarkError:
            pass
    try:
        payload = _fetch_json("https://openrouter.ai/api/v1/models", headers={"Accept": "application/json"}, fetcher=fetcher)
        records = [record for record in _records_from_payload(payload) if _quality_values(record)]
        if records:
            return records, "openrouter-benchmark", True
    except BenchmarkError:
        pass
    return [], "unavailable", False


def apply_benchmark_quality(
    candidates: Sequence[Candidate],
    provider_policy: Mapping[str, Any],
    target: Path,
    *,
    refresh: bool = False,
    allow_network: bool = False,
    now: float | None = None,
    fetcher: Callable[..., Any] | None = None,
) -> tuple[Candidate, ...]:
    """Bind fresh target-owned benchmark scores; never invent missing scores."""

    current = time.time() if now is None else float(now)
    settings = provider_policy.get("artificialAnalysis", {})
    if not isinstance(settings, Mapping):
        return tuple(candidates)
    model_settings = provider_policy.get("models", {})
    model_settings = model_settings if isinstance(model_settings, Mapping) else {}
    digest = _source_digest(settings, model_settings)
    path = _cache_path(target)
    cached = None if refresh else _load_cache(path, source_digest=digest, now=current)
    source = "cache"
    fallback_used = False
    observed = current
    expires = current + float(settings.get("cacheHours", 24)) * 3600.0
    records: Mapping[str, Mapping[str, float]] | None = None
    if cached is not None:
        records, source, fallback_used, observed, expires = cached
    if records is None and allow_network:
        raw_records, source, fallback_used = _fetch_sources(settings, refresh=refresh, fetcher=fetcher)
        if raw_records:
            built: dict[str, dict[str, float]] = {}
            for candidate in candidates:
                override = model_settings.get(candidate.candidate_id, {})
                override = override if isinstance(override, Mapping) else {}
                match = _match_record(candidate, override, raw_records)
                values = _quality_values(match) if match is not None else {}
                if values:
                    built[candidate.candidate_id] = values
            records = built
            try:
                _write_cache(path, source_digest=digest, source=source, fallback_used=fallback_used, records=records, observed=observed, expires=expires)
            except OSError:
                pass
    if not records:
        return tuple(candidates)
    result: list[Candidate] = []
    evidence_source = source if source != "cache" else "benchmark-cache"
    fallback = FallbackProvenance("openrouter-benchmark", "primary_unavailable") if fallback_used else None
    for candidate in candidates:
        values = records.get(candidate.candidate_id)
        if not isinstance(values, Mapping):
            result.append(candidate)
            continue
        metrics = {str(metric): float(score) for metric, score in values.items() if _safe_metric(metric) is not None and _number(score) is not None}
        if not metrics:
            result.append(candidate)
            continue
        evidences = tuple(
            QualityEvidence(
                metric=metric,
                score=score,
                source=evidence_source,
                observed_at_epoch_seconds=observed,
                expires_at_epoch_seconds=expires,
                status=EvidenceStatus.BEST_EFFORT if fallback_used else EvidenceStatus.VERIFIED,
                freshness=Freshness.FRESH,
                fallback=fallback,
            )
            for metric, score in sorted(metrics.items())
        )
        result.append(replace(candidate, quality_metrics={**(candidate.quality_metrics or {}), **metrics}, quality_evidence=evidences))
    return tuple(result)


__all__ = ["BenchmarkError", "apply_benchmark_quality"]
