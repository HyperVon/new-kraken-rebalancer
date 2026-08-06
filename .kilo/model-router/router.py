#!/usr/bin/env python3
"""Select and launch the cheapest capable authenticated Kilo route.

The launcher uses Kilo's own provider credentials and exact provider/model
identifiers. Artificial Analysis is an optional capability and benchmark-cost
prior; Kilo catalog pricing is the fallback when it is not configured.
"""

from __future__ import annotations

import argparse
import copy
import fnmatch
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass, field
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

import availability
import fileio

SKILL_REFERENCE_PATTERN = re.compile(r"(?<![A-Za-z0-9_-])/([A-Za-z0-9][A-Za-z0-9_-]*)")

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG_PATH = ROOT / ".kilo" / "model-router" / "config"
AA_BASE_URL = "https://artificialanalysis.ai/api/v2"
MAX_FAILOVER_ATTEMPTS = 3


def _progress(message: str) -> None:
    """Live phase progress on stderr, which stays line-buffered even when piped."""
    stamp = time.strftime("%H:%M:%S")
    print(f"[router {stamp}] {message}", file=sys.stderr, flush=True)


def blacklist_model(config_path: str | None, route: str) -> bool:
    """Persistently add an end-of-life model route to the blacklist config."""
    path = Path(config_path).expanduser() if config_path else DEFAULT_CONFIG_PATH
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    if not isinstance(payload, dict):
        return False
    blacklist = payload.get("blacklist")
    if not isinstance(blacklist, dict):
        blacklist = {}
        payload["blacklist"] = blacklist
    models = blacklist.get("models")
    if not isinstance(models, list):
        models = []
        blacklist["models"] = models
    if route in models:
        return True
    models.append(route)
    try:
        fileio.atomic_write(path, json.dumps(payload, indent=2) + "\n")
    except OSError:
        return False
    return True

DEFAULT_PROFILES: dict[str, dict[str, Any]] = {
    "trivial": {
        "metric": "artificial_analysis_intelligence_index",
        "minimum": 24,
        "context": 32_000,
        "input_tokens": 4_000,
        "output_tokens": 1_500,
        "variantPreference": ["low", "medium", "none", "instant"],
    },
    "routine": {
        "metric": "artificial_analysis_coding_index",
        "minimum": 30,
        "context": 32_000,
        "input_tokens": 6_000,
        "output_tokens": 2_000,
        "variantPreference": ["low", "medium", "none", "instant"],
    },
    "coding": {
        "metric": "artificial_analysis_coding_index",
        "minimum": 45,
        "secondary": {"artificial_analysis_agentic_index": 15},
        "context": 64_000,
        "input_tokens": 10_000,
        "output_tokens": 4_000,
        "variantPreference": ["medium", "high", "thinking", "max", "xhigh"],
    },
    "complex-coding": {
        "metric": "artificial_analysis_coding_index",
        "minimum": 55,
        "margin": 5,
        "secondary": {"artificial_analysis_agentic_index": 20},
        "requiresReasoning": True,
        "context": 96_000,
        "input_tokens": 12_000,
        "output_tokens": 5_000,
        "variantPreference": ["xhigh", "max", "high", "thinking", "medium"],
    },
    "agentic": {
        "metric": "artificial_analysis_agentic_index",
        "minimum": 30,
        "margin": 5,
        "secondary": {"artificial_analysis_coding_index": 15},
        "requiresReasoning": True,
        "context": 64_000,
        "input_tokens": 12_000,
        "output_tokens": 5_000,
        "variantPreference": ["high", "thinking", "max", "xhigh", "medium"],
    },
    "quick-review": {
        "metric": "artificial_analysis_intelligence_index",
        "minimum": 34,
        "secondary": {
            "artificial_analysis_agentic_index": 20,
            "artificial_analysis_coding_index": 20,
        },
        "requiresReasoning": True,
        "context": 96_000,
        "input_tokens": 16_000,
        "output_tokens": 8_000,
        "variantPreference": ["high", "xhigh", "max", "thinking", "medium"],
    },
    "detailed-review": {
        "metric": "artificial_analysis_intelligence_index",
        "minimum": 40,
        "margin": 5,
        "secondary": {
            "artificial_analysis_agentic_index": 25,
            "artificial_analysis_coding_index": 20,
        },
        "requiresReasoning": True,
        "context": 128_000,
        "input_tokens": 16_000,
        "output_tokens": 8_000,
        "variantPreference": ["xhigh", "max", "high", "thinking", "medium"],
    },
    "critical": {
        "metric": "artificial_analysis_intelligence_index",
        "minimum": 45,
        "margin": 5,
        "secondary": {"artificial_analysis_agentic_index": 25},
        "requiresReasoning": True,
        "context": 128_000,
        "input_tokens": 16_000,
        "output_tokens": 8_000,
        "variantPreference": ["max", "xhigh", "high", "thinking", "medium"],
    },
}

DEFAULT_CONFIG: dict[str, Any] = {
    "artificialAnalysis": {
        "enabled": True,
        "apiKeyEnv": "ARTIFICIAL_ANALYSIS_API_KEY",
        "cacheHours": 24,
    },
    "catalog": {
        "cacheHours": 2,
    },
    "quota": {
        "plugin": {
            "enabled": True,
            "commandEnv": "OPENCODE_QUOTA_COMMAND",
            "maxAgeSeconds": 300,
            "minimumRemainingPercent": 1,
            "timeoutSeconds": 45,
        },
        "cooldown": {
            "rateLimitSeconds": 60,
            "maxSeconds": 3600,
            "creditsSeconds": 3600,
            "providerUnavailableSeconds": 300,
            "authenticationSeconds": 3600,
        },
    },
    "tpsProbe": {
        "enabled": True,
        "onlyFree": True,
        "minTps": 20,
        "probeCharacters": 1000,
        "maxTokens": 400,
        "timeoutSeconds": 60,
        "cacheMinutes": 60,
        "maxProbesPerRun": 3,
    },
    "providers": {
        "kilo": {
            "enabled": True,
            "billing": "account-priced",
            "include": ["kilo-auto/efficient"],
        },
        "opencode-go": {
            "enabled": True,
            "billing": "subscription/account-priced",
            "include": ["*"],
        },
        "openai": {
            "enabled": True,
            "billing": "subscription/account-priced",
            "include": ["*"],
            "probeEndpoint": "https://api.openai.com/v1/chat/completions",
            "probeApiKeyEnv": "OPENAI_API_KEY",
        },
        "openrouter": {
            "enabled": True,
            "billing": "paid",
            "include": ["*"],
            "probeEndpoint": "https://openrouter.ai/api/v1/chat/completions",
            "probeApiKeyEnv": "OPENROUTER_API_KEY",
        },
        "nvidia": {
            "enabled": True,
            "billing": "free",
            "freeOnly": True,
            "allowFree": True,
            "include": ["*"],
            "probeEndpoint": "https://integrate.api.nvidia.com/v1/chat/completions",
            "probeApiKeyEnv": "NVIDIA_API_KEY",
        },
        "ollama": {
            "enabled": False,
            "requiresAuth": False,
            "billing": "free",
            "freeOnly": True,
            "allowFree": True,
            "include": ["*"],
            "probeEndpoint": "http://localhost:11434/v1/chat/completions",
        },
    },
    "models": {},
    "blacklist": {
        "models": [],
        "providers": [],
    },
    "policy": {
        "allowPaid": True,
        "allowFree": True,
        "denyFreeForSensitive": True,
        "useAaCostPerTask": True,
    },
    "profiles": DEFAULT_PROFILES,
}

AUTH_PROVIDER_LABELS = {
    "kilo": ("kilo gateway", "kilo"),
    "opencode-go": ("opencode go", "opencode-go"),
    "openai": ("openai", "openai"),
    "openrouter": ("openrouter", "openrouter"),
    "nvidia": ("nvidia", "nvidia"),
}

PROVIDER_ENV_VARS = {
    "openrouter": ("OPENROUTER_API_KEY",),
    "openai": ("OPENAI_API_KEY",),
    "nvidia": ("NVIDIA_API_KEY", "NVIDIA_NIM_API_KEY"),
}


@dataclass
class Candidate:
    route: str
    provider: str
    model: str
    name: str
    status: str
    input_cost: float | None
    output_cost: float | None
    cache_read_cost: float | None
    context_limit: int
    output_limit: int
    tool_call: bool
    reasoning: bool
    attachment: bool
    pdf: bool
    billing: str
    api_url: str = ""
    aa: dict[str, Any] | None = None
    aa_match: str = "none"
    quality: float | None = None
    quality_known: bool = False
    quality_source: str = "unavailable"
    aa_cost_per_task: float | None = None
    estimated_token_cost: float | None = None
    effective_cost: float | None = None
    effective_cost_source: str = "unavailable"
    free_allowed: bool = False
    quota_state: str = "unknown"
    quota_percent: float | None = None
    quota_source: str = "unavailable"
    quota_blocked_until: float = 0.0
    variants: dict[str, Mapping[str, Any]] = field(default_factory=dict)
    preferred_variant: str | None = None
    variant: str | None = None
    rejection: str | None = None


class RouterError(RuntimeError):
    """An expected, user-actionable routing failure."""


def deep_merge(base: Mapping[str, Any], override: Mapping[str, Any]) -> dict[str, Any]:
    result = dict(base)
    for key, value in override.items():
        if isinstance(value, Mapping) and isinstance(result.get(key), Mapping):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def parse_json_text(text: str) -> Any:
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    cleaned: list[str] = []
    in_string = False
    escaped = False
    index = 0
    while index < len(text):
        character = text[index]
        next_character = text[index + 1] if index + 1 < len(text) else ""
        if in_string:
            cleaned.append(character)
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            index += 1
            continue
        if character == '"':
            in_string = True
            cleaned.append(character)
            index += 1
        elif character == "/" and next_character == "/":
            index += 2
            while index < len(text) and text[index] not in "\r\n":
                index += 1
        elif character == "/" and next_character == "*":
            index += 2
            while index + 1 < len(text) and text[index : index + 2] != "*/":
                index += 1
            index += 2
        else:
            cleaned.append(character)
            index += 1
    return json.loads(re.sub(r",\s*([}\]])", r"\1", "".join(cleaned)))


def load_config(path: Path) -> dict[str, Any]:
    if not path.exists():
        return DEFAULT_CONFIG
    try:
        loaded = parse_json_text(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RouterError(f"cannot read router config {path}: {error}") from error
    if not isinstance(loaded, Mapping):
        raise RouterError(f"router config {path} must contain a JSON object")
    return deep_merge(DEFAULT_CONFIG, loaded)


def run_command(command: Sequence[str]) -> str:
    completed = subprocess.run(command, capture_output=True, text=True, check=False)
    if completed.returncode:
        detail = completed.stderr.strip().splitlines()
        suffix = detail[-1] if detail else f"exit {completed.returncode}"
        raise RouterError(f"command failed: {' '.join(command[:3])}... ({suffix})")
    return completed.stdout


def parse_config_provider_ids(payload: Any) -> set[str]:
    if not isinstance(payload, Mapping):
        return set()
    found: set[str] = set()
    provider_config = payload.get("provider")
    if isinstance(provider_config, Mapping):
        found.update(str(provider) for provider in provider_config)
    enabled = payload.get("enabled_providers")
    if isinstance(enabled, Sequence) and not isinstance(enabled, (str, bytes)):
        found.update(str(provider) for provider in enabled)
    return found


def parse_config_file(path: Path) -> set[str]:
    try:
        payload = parse_json_text(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return set()
    return parse_config_provider_ids(payload)


def configured_provider_ids() -> set[str]:
    try:
        output = run_command(["kilo", "auth", "list"]).lower()
    except RouterError:
        output = ""
    found: set[str] = set()
    for provider, (label, provider_id) in AUTH_PROVIDER_LABELS.items():
        if label in output:
            found.add(provider_id)
        if any(os.environ.get(variable) for variable in PROVIDER_ENV_VARS.get(provider, ())):
            found.add(provider_id)

    config_paths: list[Path] = []
    if os.environ.get("KILO_CONFIG"):
        config_paths.append(Path(os.environ["KILO_CONFIG"]).expanduser())
    config_paths.extend(
        [
            ROOT / "kilo.json",
            ROOT / "kilo.jsonc",
            ROOT / ".kilo" / "kilo.json",
            ROOT / ".kilo" / "kilo.jsonc",
            ROOT / "opencode.json",
            ROOT / "opencode.jsonc",
            ROOT / ".kilo" / "opencode.json",
            ROOT / ".kilo" / "opencode.jsonc",
            Path.home() / ".config" / "kilo" / "kilo.json",
            Path.home() / ".config" / "kilo" / "kilo.jsonc",
            Path.home() / ".config" / "kilo" / "opencode.json",
            Path.home() / ".config" / "kilo" / "opencode.jsonc",
            Path.home() / ".config" / "opencode" / "opencode.json",
            Path.home() / ".config" / "opencode" / "opencode.jsonc",
        ]
    )
    for path in config_paths:
        found.update(parse_config_file(path))
    inline_config = os.environ.get("KILO_CONFIG_CONTENT")
    if inline_config:
        try:
            found.update(parse_config_provider_ids(json.loads(inline_config)))
        except json.JSONDecodeError:
            pass
    return found


def parse_catalog_output(provider: str, output: str) -> list[dict[str, Any]]:
    decoder = json.JSONDecoder()
    models: dict[str, dict[str, Any]] = {}
    for match in re.finditer(r"(?m)^\s*\{", output):
        try:
            value, _ = decoder.raw_decode(output[match.start() :].lstrip())
        except json.JSONDecodeError:
            continue
        if not isinstance(value, Mapping) or not value.get("id"):
            continue
        provider_id = str(value.get("providerID") or provider)
        route = f"{provider_id}/{value['id']}"
        models[route] = dict(value)
    return list(models.values())


def catalog_cache_path(provider: str) -> Path:
    return Path.home() / ".cache" / "kilo" / "model-router" / f"catalog-{provider}.json"


def catalog_for_provider(provider: str, refresh: bool, cache_hours: float) -> list[dict[str, Any]]:
    cache = catalog_cache_path(provider)
    if not refresh and cache.exists():
        try:
            cached = json.loads(cache.read_text(encoding="utf-8"))
            if time.time() - float(cached.get("fetchedAt", 0)) < cache_hours * 3600:
                _progress(f"provider catalog cached: {provider}")
                return cached.get("models", [])
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            pass
    _progress(f"fetching provider catalog: {provider}")
    command = ["kilo", "models", provider, "--verbose"]
    if refresh:
        command.append("--refresh")
    models = parse_catalog_output(provider, run_command(command))
    try:
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps({"fetchedAt": time.time(), "models": models}), encoding="utf-8")
    except OSError:
        pass
    return models


def fetch_catalog(config: Mapping[str, Any], refresh: bool) -> tuple[list[dict[str, Any]], list[str]]:
    providers = config.get("providers", {})
    if not isinstance(providers, Mapping):
        raise RouterError("router config providers must be an object")
    cache_hours = float(config.get("catalog", {}).get("cacheHours", 2))
    configured = configured_provider_ids()
    models: list[dict[str, Any]] = []
    warnings: list[str] = []
    for provider, settings in providers.items():
        if not isinstance(settings, Mapping) or not settings.get("enabled", True):
            continue
        if settings.get("requiresAuth", True) and provider not in configured:
            warnings.append(f"skipped {provider}: no Kilo auth, provider config, or environment credential detected")
            continue
        try:
            models.extend(catalog_for_provider(provider, refresh, cache_hours))
        except RouterError as error:
            warnings.append(f"skipped {provider}: {error}")
    if not models:
        raise RouterError("no authenticated provider models were discovered")
    return models, warnings


def cache_path(config: Mapping[str, Any]) -> Path:
    configured = config.get("artificialAnalysis", {}).get("cachePath")
    if configured:
        return Path(str(configured)).expanduser()
    return Path.home() / ".cache" / "kilo" / "model-router" / "aa-language-models.json"


def openrouter_benchmarks_cache_path(config: Mapping[str, Any]) -> Path:
    return Path.home() / ".cache" / "kilo" / "model-router" / "openrouter-benchmarks.json"


_OR_INDEX_MAP = {
    "intelligence_index": "artificial_analysis_intelligence_index",
    "coding_index": "artificial_analysis_coding_index",
    "agentic_index": "artificial_analysis_agentic_index",
}


def _synthesize_or_record(model_id: str, raw: Mapping[str, Any]) -> dict[str, Any]:
    benchmarks = raw.get("benchmarks") if isinstance(raw, Mapping) else None
    aa = benchmarks.get("artificial_analysis") if isinstance(benchmarks, Mapping) else None
    indexed: dict[str, float] = {}
    if isinstance(aa, Mapping):
        for src, dst in _OR_INDEX_MAP.items():
            value = number(aa.get(src))
            if value is not None:
                indexed[dst] = value
    pricing = raw.get("pricing") if isinstance(raw, Mapping) else {}
    return {
        "slug": model_id,
        "name": str(raw.get("name", model_id)) if isinstance(raw, Mapping) else model_id,
        "evaluations": indexed,
        "source": "openrouter",
    }


def load_openrouter_benchmarks(config: Mapping[str, Any], refresh: bool) -> tuple[dict[str, Any], str]:
    settings = config.get("artificialAnalysis", {})
    if not settings.get("enabled", True):
        return {}, "disabled"
    path = openrouter_benchmarks_cache_path(config)
    cache_hours = float(settings.get("cacheHours", 24))
    if not refresh and path.exists():
        try:
            cached = json.loads(path.read_text(encoding="utf-8"))
            if time.time() - float(cached.get("fetchedAt", 0)) < cache_hours * 3600:
                records = cached.get("models", [])
                if records:
                    _progress("openrouter benchmarks cached")
                    return {r["slug"]: r for r in records if r.get("slug")}, "cached"
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            pass

    _progress("fetching openrouter benchmarks (openrouter.ai/api/v1/models)")
    records: list[dict[str, Any]] = []
    try:
        request = urllib.request.Request(
            "https://openrouter.ai/api/v1/models",
            headers={"Accept": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.load(response)
        for raw in payload.get("data", []):
            model_id = raw.get("id")
            benchmarks = raw.get("benchmarks")
            if not model_id or not isinstance(benchmarks, Mapping):
                continue
            if not benchmarks.get("artificial_analysis"):
                continue
            records.append(_synthesize_or_record(str(model_id), raw))
    except (OSError, ValueError, KeyError, urllib.error.HTTPError):
        if path.exists():
            try:
                cached = json.loads(path.read_text(encoding="utf-8"))
                cached_records = cached.get("models", [])
                if cached_records:
                    return {r["slug"]: r for r in cached_records if r.get("slug")}, "fallback-after-fetch-failure"
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                pass
        return {}, "unavailable"

    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"fetchedAt": time.time(), "models": records}), encoding="utf-8")
    except OSError:
        pass
    return {r["slug"]: r for r in records if r.get("slug")}, "fresh"


def load_artificial_analysis(config: Mapping[str, Any], refresh: bool) -> tuple[dict[str, Any], str]:
    settings = config.get("artificialAnalysis", {})
    if not settings.get("enabled", True):
        return {}, "disabled"
    path = cache_path(config)
    cache_hours = float(settings.get("cacheHours", 24))
    if not refresh and path.exists():
        try:
            cached = json.loads(path.read_text(encoding="utf-8"))
            if time.time() - float(cached.get("fetchedAt", 0)) < cache_hours * 3600:
                _progress("AA model data cached")
                return {item["slug"]: item for item in cached.get("models", [])}, "cached"
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            pass

    environment_name = str(settings.get("apiKeyEnv", "ARTIFICIAL_ANALYSIS_API_KEY"))
    api_key = os.environ.get(environment_name)
    if not api_key:
        or_models, or_status = load_openrouter_benchmarks(config, refresh)
        if or_models:
            return or_models, f"{or_status} via openrouter (no AA key)"
        return {}, f"not configured ({environment_name} is unset)"

    models: list[dict[str, Any]] = []
    page = 1
    try:
        while page <= 10:
            _progress(f"fetching AA model data page {page} ({AA_BASE_URL}/language/models/free)")
            query = urllib.parse.urlencode({"page": page})
            request = urllib.request.Request(
                f"{AA_BASE_URL}/language/models/free?{query}",
                headers={"x-api-key": api_key, "Accept": "application/json"},
            )
            with urllib.request.urlopen(request, timeout=20) as response:
                payload = json.load(response)
            models.extend(payload.get("data", []))
            pagination = payload.get("pagination", {})
            if not pagination.get("has_more"):
                break
            page += 1
    except (OSError, ValueError, KeyError, urllib.error.HTTPError) as error:
        if path.exists():
            try:
                cached = json.loads(path.read_text(encoding="utf-8"))
                cached_models = cached.get("models", [])
                if cached_models:
                    return (
                        {item["slug"]: item for item in cached_models if item.get("slug")},
                        f"fallback-after-fetch-failure ({type(error).__name__})",
                    )
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                pass
        or_models, or_status = load_openrouter_benchmarks(config, refresh)
        if or_models:
            return or_models, f"{or_status} via openrouter (AA fetch failed: {type(error).__name__})"
        return {}, f"unavailable ({type(error).__name__})"

    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        new_entry = {"fetchedAt": time.time(), "models": models}
        existing_count = 0
        if path.exists():
            try:
                existing_count = len(json.loads(path.read_text(encoding="utf-8")).get("models", []))
            except (OSError, ValueError, TypeError, json.JSONDecodeError):
                existing_count = 0
        # Only persist the fresh fetch if it is at least as complete as the
        # existing cache; a partial result that erodes a good cache would
        # steadily degrade matching quality across refreshes.
        if len(models) >= existing_count:
            path.write_text(json.dumps(new_entry), encoding="utf-8")
    except OSError:
        pass
    return {item["slug"]: item for item in models if item.get("slug")}, "fresh"


def normalize(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def tokens(value: str) -> set[str]:
    return {token for token in re.split(r"[^a-z0-9]+", value.lower()) if token}


def aa_matches_cache_path() -> Path:
    return Path.home() / ".cache" / "kilo" / "model-router" / "aa-matches.json"


_AUTO_MATCH_CACHE: dict[str, dict[str, str | None]] = {}
_AUTO_MATCH_WRITTEN: set[str] = set()


def _load_auto_match_cache(fingerprint: str) -> dict[str, str | None]:
    cached = _AUTO_MATCH_CACHE.get(fingerprint)
    if cached is not None:
        return cached
    try:
        stored = json.loads(aa_matches_cache_path().read_text(encoding="utf-8"))
        if stored.get("fingerprint") == fingerprint and isinstance(stored.get("matches"), dict):
            cached = {str(k): (v if v is not None else None) for k, v in stored["matches"].items()}
            _AUTO_MATCH_WRITTEN.add(fingerprint)
        else:
            cached = {}
        _AUTO_MATCH_CACHE[fingerprint] = cached
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        cached = {}
        _AUTO_MATCH_CACHE[fingerprint] = cached
    return cached


def _save_auto_match_cache(fingerprint: str, matches: dict[str, str | None]) -> None:
    if fingerprint in _AUTO_MATCH_WRITTEN:
        return
    try:
        path = aa_matches_cache_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps({"fingerprint": fingerprint, "matches": matches}, indent=2),
            encoding="utf-8",
        )
        _AUTO_MATCH_WRITTEN.add(fingerprint)
    except OSError:
        pass


def match_artificial_analysis(
    candidate: Candidate,
    model_config: Mapping[str, Any],
    aa_models: Mapping[str, Any],
) -> tuple[dict[str, Any] | None, str]:
    override = model_config.get("aaSlug")
    if override and str(override) in aa_models:
        return aa_models[str(override)], "configured"

    fingerprint = "|".join(sorted(aa_models)) if aa_models else ""
    if fingerprint not in _AUTO_MATCH_CACHE:
        _load_auto_match_cache(fingerprint)
    cache = _AUTO_MATCH_CACHE[fingerprint]
    route_key = f"{candidate.model}|{candidate.name}"
    if route_key in cache:
        cached_slug = cache[route_key]
        return (aa_models[cached_slug] if cached_slug in aa_models else None), (
            "automatic" if cached_slug in aa_models else "none"
        )

    route_values = [candidate.model, candidate.name]
    route_normalized = [normalize(value) for value in route_values]
    route_tokens = set().union(*(tokens(value) for value in route_values))
    best: tuple[float, dict[str, Any]] | None = None
    for model in aa_models.values():
        aa_values = [str(model.get("slug", "")), str(model.get("name", ""))]
        aa_normalized = [normalize(value) for value in aa_values]
        aa_tokens = set().union(*(tokens(value) for value in aa_values))
        score = max(SequenceMatcher(None, left, right).ratio() for left in route_normalized for right in aa_normalized)
        overlap = len(route_tokens & aa_tokens) / max(1, len(route_tokens | aa_tokens))
        score = max(score, overlap)
        if best is None or score > best[0]:
            best = (score, model)
    if best is None or best[0] < 0.78:
        cache[route_key] = None
        return None, "none"
    matched_slug = str(best[1].get("slug", ""))
    cache[route_key] = matched_slug
    return best[1], "automatic"


def number(value: Any) -> float | None:
    return float(value) if isinstance(value, (int, float)) else None


def integer(value: Any) -> int:
    return int(value) if isinstance(value, (int, float)) else 0


def effective_minimum(profile: Mapping[str, Any]) -> float:
    minimum = number(profile.get("minimum")) or 0.0
    margin = number(profile.get("margin")) or 0.0
    return minimum + margin


def model_is_allowed(
    route: str,
    model: str,
    settings: Mapping[str, Any],
    blacklist: Mapping[str, Any] | None = None,
) -> bool:
    includes = settings.get("include", ["*"])
    excludes = settings.get("exclude", [])
    if not any(fnmatch.fnmatch(route, pattern) or fnmatch.fnmatch(model, pattern) for pattern in includes):
        return False
    if any(fnmatch.fnmatch(route, pattern) or fnmatch.fnmatch(model, pattern) for pattern in excludes):
        return False
    if not isinstance(blacklist, Mapping):
        return True
    model_patterns = [*blacklist.get("models", []), *blacklist.get("routes", [])]
    provider = route.split("/", 1)[0]
    return not (
        any(fnmatch.fnmatch(route, pattern) or fnmatch.fnmatch(model, pattern) for pattern in model_patterns)
        or any(fnmatch.fnmatch(provider, pattern) for pattern in blacklist.get("providers", []))
    )


def billing_class(route: str, provider_settings: Mapping[str, Any], model_settings: Mapping[str, Any]) -> str:
    if route.endswith(":free") or "/free" in route:
        return "free"
    return str(model_settings.get("billing", provider_settings.get("billing", "unknown")))


def build_candidates(
    raw_models: Iterable[Mapping[str, Any]],
    config: Mapping[str, Any],
    aa_models: Mapping[str, Any],
    availability_snapshot: Mapping[str, Any] | None = None,
) -> list[Candidate]:
    providers = config.get("providers", {})
    model_configs = config.get("models", {})
    candidates: list[Candidate] = []
    for raw in raw_models:
        provider = str(raw.get("providerID", ""))
        model = str(raw.get("id", ""))
        route = f"{provider}/{model}"
        provider_settings = providers.get(provider, {})
        model_settings = model_configs.get(route, {}) if isinstance(model_configs, Mapping) else {}
        if not isinstance(provider_settings, Mapping) or not isinstance(model_settings, Mapping):
            continue
        if not model_is_allowed(route, model, provider_settings, config.get("blacklist")):
            continue
        capabilities = raw.get("capabilities", {})
        input_capabilities = capabilities.get("input", {}) if isinstance(capabilities, Mapping) else {}
        cost = raw.get("cost", {})
        cache = cost.get("cache", {}) if isinstance(cost, Mapping) else {}
        input_cost = number(cost.get("input")) if isinstance(cost, Mapping) else None
        output_cost = number(cost.get("output")) if isinstance(cost, Mapping) else None
        if provider_settings.get("freeOnly") and (input_cost != 0.0 or output_cost != 0.0):
            continue
        raw_variants = raw.get("variants", {})
        variants = dict(raw_variants) if isinstance(raw_variants, Mapping) else {}
        configured_variant = model_settings.get("variant")
        api_info = raw.get("api")
        api_url = str(api_info.get("url") or "") if isinstance(api_info, Mapping) else ""
        candidate = Candidate(
            route=route,
            provider=provider,
            model=model,
            name=str(raw.get("name", model)),
            status=str(raw.get("status", "unknown")),
            input_cost=input_cost,
            output_cost=output_cost,
            cache_read_cost=number(cache.get("read")) if isinstance(cache, Mapping) else None,
            context_limit=integer(model_settings.get("contextLimit", raw.get("limit", {}).get("context")))
            if isinstance(model_settings, Mapping)
            else integer(raw.get("limit", {}).get("context"))
            if isinstance(raw.get("limit"), Mapping)
            else 0,
            output_limit=integer(raw.get("limit", {}).get("output")) if isinstance(raw.get("limit"), Mapping) else 0,
            tool_call=bool(capabilities.get("toolcall", capabilities.get("tool_call", False))),
            reasoning=bool(capabilities.get("reasoning", False)),
            attachment=bool(capabilities.get("attachment", False)),
            pdf=bool(input_capabilities.get("pdf", False)) if isinstance(input_capabilities, Mapping) else False,
            api_url=api_url,
            billing=("free" if provider_settings.get("freeOnly") else billing_class(route, provider_settings, model_settings)),
            free_allowed=bool(provider_settings.get("allowFree", False)),
            variants=variants,
            preferred_variant=str(configured_variant) if configured_variant else None,
        )
        aa, match = match_artificial_analysis(candidate, model_settings, aa_models)
        candidate.aa = aa
        candidate.aa_match = match
        availability.apply_to_candidate(candidate, availability_snapshot)
        candidates.append(candidate)
    if aa_models:
        fingerprint = "|".join(sorted(aa_models))
        if fingerprint in _AUTO_MATCH_CACHE:
            _save_auto_match_cache(fingerprint, _AUTO_MATCH_CACHE[fingerprint])
    return candidates


def infer_profile(task: str) -> str:
    lowered = task.lower()
    critical = (
        "security", "credential", "secret", "trading", "money", "financial",
        "architecture", "adversarial",
        # Live-money correctness for a trading rebalancer: partial fills,
        # fund/balance accounting, order settlement, and reconciliation are
        # high-stakes enough to warrant the critical floor (higher minimum +
        # margin, and free routes are blocked for sensitive prompts).
        "partial fill", "partial-fill", "funds", "funding",
        "accounting", "reconcile", "reconciliation", "settlement",
    )
    if any(term in lowered for term in critical):
        return "critical"
    # Deliberation tasks (review/audit/analysis/documentation) demand a higher reasoning
    # bar and take precedence over coding terms: "/code-review" must not drop to the
    # coding floor just because the task mentions "code".
    review = ("review", "audit", "documentation", "analysis", "analyze", "instructions", "workflow", "delegate")
    review_detailed = ("audit", "architecture", "security", "comprehensive", "thorough", "deep ", "code-review", "code review", "design review", "documentation review")
    if any(term in lowered for term in review):
        if any(term in lowered for term in review_detailed):
            return "detailed-review"
        return "quick-review"
    # Complex/deeply-coupled programming work gets a higher coding bar than routine code edits.
    complex_coding = ("refactor", "algorithm", "concurrency", "parallel", "optimize", "performance", "distributed", "scalable", "complex")
    coding = ("code", "bug", "fix", "test", "debug", "build", "gradle", "compile", "implement", "edit", "investigate", "write")
    if any(term in lowered for term in complex_coding):
        return "complex-coding"
    if any(term in lowered for term in coding):
        return "coding"
    # Light, low-reasoning maintenance vs trivial one-liners.
    routine = ("typo", "comment", "docstring", "cleanup", "minor", "small", "update ")
    trivial = ("format", "rename", "summarize", "summary", "list", "status", "lookup", "find ", "simple", "spell")
    if any(term in lowered for term in routine):
        return "routine"
    if any(term in lowered for term in trivial):
        return "trivial"
    return "agentic"


# Kilo TUI agents: ask, code, debug, explore, general, orchestrator (deprecated),
# plan, summary, title, compaction. "code" is the implementation/Code-mode agent;
# "ask" is read-only question/answer. Read-only review/audit work maps to "ask"
# (the code-review skill is read-only: recommend only, do not implement unless
# asked); everything that performs work maps to "code".
_REVIEW_PROFILES = {"quick-review", "detailed-review"}


def infer_agent(profile_name: str) -> str:
    if profile_name in _REVIEW_PROFILES:
        return "ask"
    return "code"


def profile_config(config: Mapping[str, Any], requested: str, task: str) -> tuple[str, dict[str, Any]]:
    name = infer_profile(task) if requested == "auto" else requested
    profiles = config.get("profiles", DEFAULT_PROFILES)
    profile = deep_merge(DEFAULT_PROFILES.get(name, DEFAULT_PROFILES["agentic"]), profiles.get(name, {}))
    return name, profile


def aa_quality(candidate: Candidate, profile: Mapping[str, Any]) -> tuple[float | None, str]:
    if not candidate.aa:
        return None, "unavailable"
    evaluations = candidate.aa.get("evaluations", {})
    metric = str(profile.get("metric", "artificial_analysis_intelligence_index"))
    value = number(evaluations.get(metric)) if isinstance(evaluations, Mapping) else None
    if value is None:
        return None, "unavailable"
    return value, f"Artificial Analysis {metric} ({candidate.aa_match})"


def apply_ranking_data(candidates: Iterable[Candidate], profile: Mapping[str, Any], config: Mapping[str, Any]) -> None:
    policy = config.get("policy", {})
    input_tokens = int(profile.get("input_tokens", 10_000))
    output_tokens = int(profile.get("output_tokens", 4_000))
    for candidate in candidates:
        candidate.quality, candidate.quality_source = aa_quality(candidate, profile)
        if candidate.aa:
            task_cost_container = candidate.aa.get("artificial_analysis_intelligence_index_cost")
            task_cost_container = task_cost_container if isinstance(task_cost_container, Mapping) else {}
            task_cost = task_cost_container.get("cost_per_task")
            task_cost = task_cost if isinstance(task_cost, Mapping) else {}
            candidate.aa_cost_per_task = number(task_cost.get("total_cost")) if isinstance(task_cost, Mapping) else None
        if candidate.input_cost is not None and candidate.output_cost is not None:
            candidate.estimated_token_cost = (
                candidate.input_cost * input_tokens + candidate.output_cost * output_tokens
            ) / 1_000_000
        # Only genuinely free billing is cost 0.0. Subscription / account-priced
        # routes (e.g. a token budget on an OpenCode-Go subscription) still burn
        # quota per task, so they keep their real per-task/estimated cost to let a
        # smaller model win over a large one at the same effective price.
        if candidate.billing == "free":
            candidate.effective_cost = 0.0
            candidate.effective_cost_source = candidate.billing
        elif policy.get("useAaCostPerTask", True) and candidate.aa_cost_per_task is not None:
            candidate.effective_cost = candidate.aa_cost_per_task
            candidate.effective_cost_source = "Artificial Analysis benchmark task cost"
        elif candidate.estimated_token_cost is not None:
            candidate.effective_cost = candidate.estimated_token_cost
            candidate.effective_cost_source = "Kilo catalog token estimate"


SENSITIVE_PROMPT_PATTERNS = (
    r"-----begin .*private key-----",
    r"\b(?:api[_ -]?key|access[_ -]?token|auth[_ -]?token|password|secret|private key)\b",
    r"(?:^|[\s/])(?:\.env|auth\.json|credentials?\.json|secrets?\.json|rebalancer-config\.json)(?:$|[\s/])",
    r"\b(?:pii|personal data|social security|date of birth|home address|phone number)\b",
)


def is_sensitive(task: str, profile_name: str) -> bool:
    del profile_name
    return any(re.search(pattern, task, flags=re.IGNORECASE) for pattern in SENSITIVE_PROMPT_PATTERNS)


def candidate_qualifies(candidate: Candidate, profile: Mapping[str, Any], config: Mapping[str, Any], sensitive: bool) -> bool:
    policy = config.get("policy", {})
    if candidate.quota_state in {"insufficient", "unavailable", "blocked"}:
        candidate.rejection = f"quota state is {candidate.quota_state}"
        return False
    if candidate.status not in {"active", "unknown"}:
        candidate.rejection = "catalog status is not active"
        return False
    if not candidate.tool_call:
        candidate.rejection = "tool calling is not advertised"
        return False
    if profile.get("requiresReasoning") and not candidate.reasoning:
        candidate.rejection = "reasoning support is not advertised"
        return False
    if candidate.context_limit and candidate.context_limit < int(profile.get("context", 0)):
        candidate.rejection = "context window is too small"
        return False
    if candidate.billing == "free" and not (policy.get("allowFree", False) or candidate.free_allowed):
        candidate.rejection = "free routes disabled by policy"
        return False
    if candidate.billing == "free" and sensitive and policy.get("denyFreeForSensitive", True):
        candidate.rejection = "free routes disabled for sensitive work"
        return False
    if candidate.billing == "paid" and not policy.get("allowPaid", True):
        candidate.rejection = "paid routes disabled by policy"
        return False
    minimum = effective_minimum(profile)
    if candidate.quality is None:
        candidate.rejection = "capability quality is unknown and cannot be assessed"
        return False
    if candidate.quality < minimum:
        candidate.rejection = f"quality score {candidate.quality:g} is below {minimum:g}"
        return False
    secondary = profile.get("secondary", {})
    if candidate.aa and isinstance(secondary, Mapping):
        evaluations = candidate.aa.get("evaluations", {})
        for metric, threshold in secondary.items():
            value = number(evaluations.get(metric)) if isinstance(evaluations, Mapping) else None
            if value is not None and value < float(threshold):
                candidate.rejection = f"{metric} is below {threshold}"
                return False
    return True


def select_variant(candidate: Candidate, profile: Mapping[str, Any]) -> None:
    if not candidate.variants:
        candidate.variant = None
        return
    available = set(candidate.variants)
    if candidate.preferred_variant in available:
        candidate.variant = candidate.preferred_variant
        return
    preferences = profile.get("variantPreference", [])
    if isinstance(preferences, Sequence) and not isinstance(preferences, (str, bytes)):
        for preferred in preferences:
            if preferred in available:
                candidate.variant = str(preferred)
                return
    candidate.variant = next(iter(candidate.variants))


TPS_CACHE_PATH = Path.home() / ".cache" / "kilo" / "model-router" / "tps.json"

# A generation of roughly 1000 characters, so the measured rate reflects
# sustained throughput instead of first-token latency.
TPS_PROBE_PROMPT = (
    "Write a clear technical explanation of how an automated portfolio "
    "rebalancer detects allocation deviations and places orders, in plain "
    "English, at least one thousand characters long. Do not stop early."
)


def read_tps_cache() -> dict[str, dict[str, Any]]:
    try:
        return json.loads(TPS_CACHE_PATH.read_text())
    except (OSError, ValueError):
        return {}


def write_tps_cache(cache: Mapping[str, Any]) -> None:
    try:
        TPS_CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
        TPS_CACHE_PATH.write_text(json.dumps(dict(cache), indent=2))
    except OSError:
        pass


def cached_tps(route: str, config: Mapping[str, Any]) -> float | None:
    """Fresh cached tokens/sec for a route, or None when absent or stale."""
    cache_minutes = float(config.get("tpsProbe", {}).get("cacheMinutes", 60))
    if cache_minutes <= 0:
        return None
    entry = read_tps_cache().get(route)
    if not entry:
        return None
    try:
        if time.time() - float(entry["measured_at"]) > cache_minutes * 60:
            return None
        return float(entry["tps"])
    except (KeyError, TypeError, ValueError):
        return None


AUTH_STORE_PATH = Path.home() / ".local" / "share" / "kilo" / "auth.json"
KILO_CONFIG_PATHS = (
    Path.home() / ".config" / "kilo" / "kilo.jsonc",
    Path.home() / ".config" / "kilo" / "kilo.json",
)


def _endpoint_for(candidate: Candidate) -> str:
    base = candidate.api_url.strip().rstrip("/")
    if not base:
        return ""
    if base.endswith("/chat/completions"):
        return base
    return f"{base}/chat/completions"


def _auth_store_key(provider: str) -> str | None:
    names = (provider, "opencode") if provider == "opencode-go" else (provider,)
    try:
        data = json.loads(AUTH_STORE_PATH.read_text())
    except (OSError, ValueError):
        return None
    for name in names:
        entry = data.get(name)
        if not isinstance(entry, dict):
            continue
        secret = entry.get("key") if entry.get("type") == "api" else entry.get("access")
        if isinstance(secret, str) and secret and secret != "0":
            return secret
    return None


def _load_jsonc(path: Path) -> Any:
    try:
        raw = path.read_text()
    except OSError:
        return None
    try:
        return json.loads(raw)
    except ValueError:
        pass
    stripped = re.sub(r"(^|[\s\[{,])//.*", r"\1", raw, flags=re.M)
    stripped = re.sub(r",\s*([}\]])", r"\1", stripped)
    try:
        return json.loads(stripped)
    except ValueError:
        return None


def _config_api_key(provider: str) -> str | None:
    for path in KILO_CONFIG_PATHS:
        data = _load_jsonc(path)
        if not isinstance(data, dict):
            continue
        provider_config = data.get("provider", {}).get(provider)
        if not isinstance(provider_config, dict):
            continue
        options = provider_config.get("options")
        if isinstance(options, dict):
            key = options.get("apiKey")
            if isinstance(key, str) and key and key != "0":
                return key
    return None


def _is_timeout(error: Exception) -> bool:
    if isinstance(error, TimeoutError):
        return True
    return isinstance(error, urllib.error.URLError) and isinstance(getattr(error, "reason", None), TimeoutError)


def probe_tps(candidate: Candidate, config: Mapping[str, Any]) -> tuple[float | None, str]:
    """Measure sustained tokens/sec for a candidate's route.

    Uses the provider's OpenAI-compatible chat endpoint. Returns (tps, source);
    tps is None when the route has no probe endpoint, no API key, or the probe
    failed — an unknown measurement never blocks selection.
    """
    cached = cached_tps(candidate.route, config)
    if cached is not None:
        return cached, "cache"
    probe = config.get("tpsProbe", {})
    provider = config.get("providers", {}).get(candidate.provider, {})
    endpoint = provider.get("probeEndpoint") or _endpoint_for(candidate)
    if not endpoint:
        return None, "no probe endpoint"
    key_env = provider.get("probeApiKeyEnv")
    api_key = os.environ.get(key_env) if key_env else None
    if not api_key:
        api_key = _auth_store_key(candidate.provider)
    if not api_key:
        api_key = _config_api_key(candidate.provider)
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    body = {
        "model": candidate.model,
        "messages": [{"role": "user", "content": TPS_PROBE_PROMPT}],
        "max_tokens": int(probe.get("maxTokens", 400)),
        "stream": False,
    }
    request = urllib.request.Request(
        endpoint, data=json.dumps(body).encode(), headers=headers, method="POST"
    )
    min_tps = float(probe.get("minTps", 20))
    probe_chars = float(probe.get("probeCharacters", 1000))
    timeout = float(probe.get("timeoutSeconds", 60))
    if min_tps > 0 and probe_chars > 0:
        timeout = min(timeout, probe_chars / min_tps)
    started = time.monotonic()
    _progress(f"TPS probe {candidate.route}: {endpoint} (timeout {timeout:.0f}s)")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode())
    except (urllib.error.URLError, OSError, ValueError) as error:
        if _is_timeout(error):
            if float(probe.get("cacheMinutes", 60)) > 0:
                cache = read_tps_cache()
                cache[candidate.route] = {"tps": 0.0, "measured_at": time.time(), "model": candidate.model}
                write_tps_cache(cache)
            _progress(f"TPS probe {candidate.route}: timed out after {timeout:.0f}s -> excluded")
            return 0.0, "timeout"
        _progress(f"TPS probe {candidate.route}: failed ({type(error).__name__})")
        return None, f"probe failed ({type(error).__name__})"
    elapsed = time.monotonic() - started
    usage = payload.get("usage")
    tokens = usage.get("completion_tokens") if isinstance(usage, dict) else None
    if tokens is None:
        content = (payload.get("choices") or [{}])[0].get("message", {}).get("content") or ""
        tokens = max(1, len(content) // 4)
    if elapsed <= 0 or not isinstance(tokens, (int, float)) or tokens <= 0:
        _progress(f"TPS probe {candidate.route}: no measurable output")
        return None, "probe produced no measurable output"
    tps = round(float(tokens) / elapsed, 2)
    if float(probe.get("cacheMinutes", 60)) > 0:
        cache = read_tps_cache()
        cache[candidate.route] = {"tps": tps, "measured_at": time.time(), "model": candidate.model}
        write_tps_cache(cache)
    _progress(f"TPS probe {candidate.route}: {tps} tps")
    return tps, "probe"


def select_with_tps_guard(
    candidates: Sequence[Candidate],
    profile: Mapping[str, Any],
    config: Mapping[str, Any],
    sensitive: bool,
    excluded_routes: set[str] | None = None,
    excluded_providers: set[str] | None = None,
) -> tuple[Candidate, list[str]]:
    """select_candidate plus a live throughput sanity probe for free routes.

    When tpsProbe is enabled and the selected route is a free-billing route
    with a probe endpoint, measure sustained tokens/sec (~1000-character
    generation). Routes below tpsProbe.minTps are excluded and the next best
    is chosen, bounded by tpsProbe.maxProbesPerRun. Unknown measurements
    never block. If every free route falls below the threshold, the next
    cheapest qualifying route (typically paid) is selected; if only slow
    free routes remain, the best available is kept with a warning instead of
    failing the run.
    """
    warnings: list[str] = []
    probe = config.get("tpsProbe", {})
    enabled = bool(probe.get("enabled", True))
    max_probes = max(0, int(probe.get("maxProbesPerRun", 3)))
    only_free = bool(probe.get("onlyFree", True))
    min_tps = float(probe.get("minTps", 20))
    excluded = set(excluded_routes or ())
    probe_excluded: set[str] = set()

    if not enabled or max_probes <= 0:
        return (
            select_candidate(
                candidates,
                profile,
                config,
                sensitive,
                excluded_routes=excluded,
                excluded_providers=excluded_providers,
            ),
            warnings,
        )

    for _ in range(max_probes):
        try:
            selected = select_candidate(
                candidates,
                profile,
                config,
                sensitive,
                excluded_routes=excluded | probe_excluded,
                excluded_providers=excluded_providers,
            )
        except RouterError:
            # Probing excluded every remaining candidate: keep the best
            # available route rather than fail the run.
            warnings.append(
                "no qualifying route outside the probed-slow free routes; kept the best available route"
            )
            return (
                select_candidate(
                    candidates,
                    profile,
                    config,
                    sensitive,
                    excluded_routes=excluded,
                    excluded_providers=excluded_providers,
                ),
                warnings,
            )
        if only_free and selected.billing != "free":
            if probe_excluded:
                warnings.append(
                    "free routes fell below the tps threshold; selected the next cheapest qualifying route"
                )
            return selected, warnings
        if not config.get("providers", {}).get(selected.provider, {}).get("probeEndpoint"):
            return selected, warnings
        tps, _source = probe_tps(selected, config)
        if tps is None:
            return selected, warnings
        if tps >= min_tps:
            return selected, warnings
        warnings.append(
            f"free route {selected.route} measured {tps:g} tps (below the {min_tps:g} minimum); trying the next best route"
        )
        probe_excluded.add(selected.route)

    free_routes = {candidate.route for candidate in candidates if candidate.billing == "free"}
    warnings.append(
        "free routes fell below the tps threshold; selected the next cheapest qualifying route"
    )
    try:
        return (
            select_candidate(
                candidates,
                profile,
                config,
                sensitive,
                excluded_routes=excluded | free_routes,
                excluded_providers=excluded_providers,
            ),
            warnings,
        )
    except RouterError:
        warnings.append("no qualifying route outside the slow free routes; kept the best available free route")
        return (
            select_candidate(
                candidates,
                profile,
                config,
                sensitive,
                excluded_routes=excluded | probe_excluded,
                excluded_providers=excluded_providers,
            ),
            warnings,
        )


def select_candidate(
    candidates: Sequence[Candidate],
    profile: Mapping[str, Any],
    config: Mapping[str, Any],
    sensitive: bool,
    excluded_routes: set[str] | None = None,
    excluded_providers: set[str] | None = None,
) -> Candidate:
    apply_ranking_data(candidates, profile, config)
    excluded_routes = excluded_routes or set()
    excluded_providers = excluded_providers or set()
    usable = [
        candidate
        for candidate in candidates
        if candidate.route not in excluded_routes
        and candidate.provider not in excluded_providers
        and candidate_qualifies(candidate, profile, config, sensitive)
    ]
    if not usable:
        reasons: dict[str, int] = {}
        for candidate in candidates:
            if candidate.route in excluded_routes or candidate.provider in excluded_providers:
                continue
            candidate_qualifies(candidate, profile, config, sensitive)
            if candidate.rejection:
                reasons[candidate.rejection] = reasons.get(candidate.rejection, 0) + 1
        detail = ""
        if reasons:
            summary = ", ".join(f"{reason} ({count} model{'s' if count != 1 else ''})" for reason, count in sorted(reasons.items(), key=lambda item: -item[1])[:5])
            detail = f"; top rejection reasons: {summary}"
        raise RouterError(f"no candidate satisfies the current capability, cost, and privacy policy{detail}")

    def sort_key(candidate: Candidate) -> tuple[float, float, int, float, int]:
        # Lowest effective cost wins; among (near-)equal-cost routes the profile
        # minimum is the difficulty bar, so prefer the model with the SMALLEST
        # capability headroom above it — a just-sufficient small/fast model for
        # trivial tasks, yet a genuinely strong model where the bar is high.
        # Then an already-paid subscription over PAYG, then higher quota headroom
        # as a load-spread tiebreak. Free models are not quota-metered (report
        # 'unknown'), so an unknown free quota never blocks them — free cost 0.0
        # already ranks them first.
        free = candidate.billing == "free"
        unknown_quota = 0 if (candidate.quota_state == "sufficient" or free) else 1
        cost = candidate.effective_cost if candidate.effective_cost is not None else float("inf")
        quality = candidate.quality if candidate.quality is not None else 0.0
        minimum = effective_minimum(profile)
        headroom = quality - minimum
        # Prefer an already-paid subscription/account-priced route over PAYG when
        # cost and headroom tie (avoid marginal spend).
        subscription = candidate.billing in {"subscription", "subscription/account-priced", "account-priced"}
        quota = candidate.quota_percent if candidate.quota_percent is not None else 0.0
        return cost, headroom, 0 if subscription else 1, -quota, unknown_quota

    selected = min(usable, key=sort_key)
    select_variant(selected, profile)
    return selected


def report(
    candidate: Candidate,
    profile_name: str,
    profile: Mapping[str, Any],
    aa_status: str,
    sensitive: bool,
) -> dict[str, Any]:
    return {
        "route": candidate.route,
        "provider": candidate.provider,
        "model": candidate.model,
        "variant": candidate.variant,
        "available_variants": sorted(candidate.variants),
        "profile": profile_name,
        "billing": candidate.billing,
        "cost": {
            "effective": candidate.effective_cost,
            "source": candidate.effective_cost_source,
            "aa_cost_per_task": candidate.aa_cost_per_task,
            "estimated_token_cost": candidate.estimated_token_cost,
        },
        "capability": {
            "score": candidate.quality,
            "source": candidate.quality_source,
            "minimum": profile.get("minimum"),
        },
        "availability": candidate.quota_state,
        "quota": {
            "state": candidate.quota_state,
            "remaining_percent": candidate.quota_percent,
            "source": candidate.quota_source,
        },
        "aa": aa_status,
        "tps": None,
        "free_route_guard": "blocked by prompt guard" if sensitive else "allowed by prompt guard",
        "context_limit": candidate.context_limit,
        "tool_call": candidate.tool_call,
    }


def load_selection_context(args: argparse.Namespace) -> dict[str, Any]:
    config_path = Path(args.config).expanduser() if args.config else DEFAULT_CONFIG_PATH
    config = load_config(config_path)
    raw_models, warnings = fetch_catalog(config, args.refresh)
    aa_models, aa_status = load_artificial_analysis(config, args.refresh)
    quota_snapshot = availability.snapshot(config)
    warnings.extend(quota_snapshot["warnings"])
    task = args.task
    profile_name, profile = profile_config(config, args.profile, task)
    candidates = build_candidates(raw_models, config, aa_models, quota_snapshot)
    sensitive = is_sensitive(task, profile_name)
    selected, tps_warnings = select_with_tps_guard(candidates, profile, config, sensitive)
    warnings.extend(tps_warnings)
    result = report(selected, profile_name, profile, aa_status, sensitive)
    result["tps"] = cached_tps(selected.route, config)
    result["config"] = str(config_path)
    result["warnings"] = warnings
    result["aa_matches"] = sum(candidate.aa is not None for candidate in candidates)
    return {
        "result": result,
        "warnings": warnings,
        "config": config,
        "candidates": candidates,
        "profile_name": profile_name,
        "profile": profile,
        "sensitive": sensitive,
        "task": task,
    }


def load_selection(args: argparse.Namespace) -> tuple[dict[str, Any], list[str]]:
    context = load_selection_context(args)
    return context["result"], context["warnings"]


def referenced_skill_paths(task: str) -> list[tuple[str, Path]]:
    references: list[tuple[str, Path]] = []
    seen: set[str] = set()
    for match in SKILL_REFERENCE_PATTERN.finditer(task):
        name = match.group(1)
        if name in seen:
            continue
        path = ROOT / ".agents" / "skills" / name / "SKILL.md"
        if not path.is_file():
            continue
        references.append((name, path))
        seen.add(name)
    return references


def prepare_initial_prompt(task: str) -> str:
    references = referenced_skill_paths(task)
    if not references:
        return task
    skill_bodies = "\n\n---\n\n".join(
        f"Repository skill `/{name}` (`{path.relative_to(ROOT)}`):\n\n"
        f"{path.read_text(encoding='utf-8').strip()}"
        for name, path in references
    )
    return (
        "Repository skill invocation detected. Before planning or acting, read and follow the referenced skill file(s):\n\n"
        f"{skill_bodies}"
        "\n\nThe original user request follows unchanged:\n"
        f"{task}"
    )


def build_kilo_command(args: argparse.Namespace, result: Mapping[str, Any]) -> list[str]:
    prompt = getattr(args, "initial_prompt", " ".join(args.message))
    variant = args.variant or result.get("variant")
    # Infer the TUI agent (mode) from the task profile unless the operator
    # passed an explicit --agent. Read-only review/audit profiles map to "ask";
    # implementation work maps to "code". (Orchestrator is deprecated, unused.)
    inferred_agent = args.agent or infer_agent(result.get("profile", "agentic"))
    if args.tui:
        command = ["kilo", "--model", str(result["route"])]
        command.extend(["--agent", inferred_agent])
        if args.continue_session:
            command.append("--continue")
        if args.session:
            command.extend(["--session", args.session])
        command.extend(["--prompt", prompt])
        return command

    command = ["kilo", "run", "--model", str(result["route"])]
    command.extend(["--agent", inferred_agent])
    if args.interactive:
        command.append("--interactive")
    if args.continue_session:
        command.append("--continue")
    if args.session:
        command.extend(["--session", args.session])
    if args.auto:
        command.append("--auto")
    if variant:
        command.extend(["--variant", str(variant)])
    command.append(prompt)
    return command


def tui_variant_config(args: argparse.Namespace, result: Mapping[str, Any]) -> str | None:
    variant = args.variant or result.get("variant")
    if not variant:
        return None
    existing = os.environ.get("KILO_CONFIG_CONTENT")
    base: dict[str, Any] = {}
    if existing:
        parsed = parse_json_text(existing)
        if not isinstance(parsed, Mapping):
            raise RouterError("KILO_CONFIG_CONTENT must contain a JSON object")
        base = dict(parsed)
    agent = args.agent or infer_agent(result.get("profile", "agentic"))
    overlay = {
        "model": result["route"],
        "agent": {agent: {"model": result["route"], "variant": variant}},
        "default_agent": agent,
    }
    return json.dumps(deep_merge(base, overlay))


def run_kilo_streaming(command: Sequence[str]) -> tuple[int, str]:
    process = subprocess.Popen(
        command,
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    recent: list[str] = []
    assert process.stdout is not None
    for line in process.stdout:
        print(line, end="")
        recent.append(line.rstrip())
        del recent[:-40]
    return process.wait(), "\n".join(recent)


def has_tool_activity(output: str) -> bool:
    return bool(
        re.search(
            r"\b(?:tool call|executing|apply_patch|write_file|edit_file|bash|shell|created|updated)\b",
            output,
            re.IGNORECASE,
        )
    )


def print_selection(result: Mapping[str, Any], stream: Any = sys.stdout) -> None:
    cost = result["cost"]
    capability = result["capability"]
    print(f"Selected route: {result['route']}", file=stream)
    print(f"Selected variant: {result.get('variant') or 'default'}", file=stream)
    available_variants = result.get("available_variants", [])
    if available_variants:
        print(f"Available variants: {', '.join(available_variants)}", file=stream)
    print(f"Task profile: {result['profile']}", file=stream)
    print(f"Billing: {result['billing']}", file=stream)
    if cost["effective"] is None:
        print("Cost: unknown", file=stream)
    else:
        print(f"Cost basis: ${cost['effective']:.6f} ({cost['source']})", file=stream)
    if cost["aa_cost_per_task"] is not None:
        print(f"AA benchmark cost/task: ${cost['aa_cost_per_task']:.6f}", file=stream)
    if cost["estimated_token_cost"] is not None:
        print(f"Catalog token estimate: ${cost['estimated_token_cost']:.6f}", file=stream)
    if capability["score"] is None:
        print("Capability: unknown; no Artificial Analysis route match", file=stream)
    else:
        print(f"Capability: {capability['score']:g} ({capability['source']})", file=stream)
    print(f"Availability: {result['availability']}", file=stream)
    quota = result["quota"]
    remaining = quota["remaining_percent"]
    remaining_text = f"{remaining:.1f}%" if remaining is not None else "unknown"
    print(f"Quota: {quota['state']} ({remaining_text}; {quota['source']})", file=stream)
    tps = result.get("tps")
    if tps is not None:
        print(f"Measured throughput: {tps:g} tokens/sec", file=stream)
    print(f"Free-route guard: {result['free_route_guard']}", file=stream)
    print(f"Artificial Analysis data: {result['aa']}", file=stream)
    for warning in result.get("warnings", []):
        print(f"Warning: {warning}", file=stream)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    def add_selection_options(command: argparse.ArgumentParser) -> None:
        command.add_argument("--config", help="router config path")
        command.add_argument("--profile", choices=["auto", *DEFAULT_PROFILES], default="auto")
        command.add_argument("--refresh", action="store_true", help="refresh Kilo and AA metadata")
        command.add_argument("--json", action="store_true", help="print machine-readable output")

    select = subparsers.add_parser("select", help="select a route without launching Kilo")
    add_selection_options(select)
    select.add_argument("--task", required=True, help="task prompt used for routing")

    catalog = subparsers.add_parser("catalog", help="list discovered authenticated candidates")
    catalog.add_argument("--config")
    catalog.add_argument("--refresh", action="store_true")
    catalog.add_argument("--json", action="store_true")

    run = subparsers.add_parser("run", help="select a route and launch Kilo")
    add_selection_options(run)
    run.add_argument("--agent")
    run.add_argument("--variant")
    run.add_argument("--interactive", action="store_true")
    run.add_argument("--tui", action="store_true", help="launch the full Kilo TUI")
    run.add_argument("--continue", dest="continue_session", action="store_true")
    run.add_argument("--session")
    run.add_argument("--auto", action="store_true", help="pass Kilo's dangerous auto-approval flag")
    run.add_argument("message", nargs="*", help="task prompt; put -- before messages beginning with -")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.command == "catalog":
        config_path = Path(args.config).expanduser() if args.config else DEFAULT_CONFIG_PATH
        config = load_config(config_path)
        raw_models, warnings = fetch_catalog(config, args.refresh)
        candidates = build_candidates(raw_models, config, {})
        rows = [
            {
                "route": candidate.route,
                "name": candidate.name,
                "billing": candidate.billing,
                "input_cost": candidate.input_cost,
                "output_cost": candidate.output_cost,
                "tool_call": candidate.tool_call,
                "context": candidate.context_limit,
            }
            for candidate in candidates
        ]
        if args.json:
            print(json.dumps({"models": rows, "warnings": warnings}, indent=2))
        else:
            for row in rows:
                print(f"{row['route']}\t{row['billing']}\t{row['name']}")
            for warning in warnings:
                print(f"Warning: {warning}", file=sys.stderr)
        return 0

    if args.command == "select":
        result, _ = load_selection(args)
        if args.json:
            print(json.dumps(result, indent=2))
        else:
            print_selection(result)
        return 0

    if not args.message:
        raise RouterError("run requires a task message")
    if args.tui and (args.interactive or args.auto):
        raise RouterError("--tui cannot be combined with --interactive or --auto")
    args.task = " ".join(args.message)
    args.initial_prompt = prepare_initial_prompt(args.task)
    context = load_selection_context(args)
    config_path = Path(args.config).expanduser() if args.config else DEFAULT_CONFIG_PATH
    result = context["result"]
    attempted_routes: set[str] = set()
    excluded_providers: set[str] = set()

    for _ in range(MAX_FAILOVER_ATTEMPTS):
        print_selection(result, stream=sys.stderr)
        command = build_kilo_command(args, result)
        if args.tui:
            variant_config = tui_variant_config(args, result)
            if variant_config:
                os.environ["KILO_CONFIG_CONTENT"] = variant_config
            os.chdir(ROOT)
            os.execvp(command[0], command)
        if args.interactive:
            os.execvp(command[0], command)
        exit_code, output = run_kilo_streaming(command)
        if exit_code == 0:
            return 0
        kind = availability.failure_kind(output)
        attempted_routes.add(str(result["route"]))
        if not kind or kind not in {
            "rate_limit",
            "credits",
            "provider_unavailable",
            "authentication",
            "model_eol",
        }:
            return exit_code
        if has_tool_activity(output):
            print("model-router: not retrying after tool activity", file=sys.stderr)
            return exit_code

        cooldown = availability.record_failure(
            context["config"],
            str(result["route"]),
            str(result["provider"]),
            kind,
            output,
        )
        if kind == "model_eol":
            blacklist_model(config_path, str(result["route"]))
        else:
            excluded_providers.add(str(result["provider"]))
        print(
            f"model-router: {kind} on {result['route']}; trying another route "
            f"(cooldown {cooldown}s)",
            file=sys.stderr,
        )
        candidates = copy.deepcopy(context["candidates"])
        try:
            next_candidate, _tps_warnings = select_with_tps_guard(
                candidates,
                context["profile"],
                context["config"],
                context["sensitive"],
                excluded_routes=attempted_routes,
                excluded_providers=excluded_providers,
            )
        except RouterError:
            return exit_code
        result = report(
            next_candidate,
            context["profile_name"],
            context["profile"],
            result["aa"],
            context["sensitive"],
        )
        result["tps"] = cached_tps(next_candidate.route, context["config"])
        result["warnings"] = context["warnings"]
    return 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RouterError as error:
        print(f"model-router: {error}", file=sys.stderr)
        raise SystemExit(2)
