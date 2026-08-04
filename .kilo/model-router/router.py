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


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG_PATH = ROOT / ".kilo" / "model-router" / "config"
AA_BASE_URL = "https://artificialanalysis.ai/api/v2"
MAX_FAILOVER_ATTEMPTS = 3

DEFAULT_PROFILES: dict[str, dict[str, Any]] = {
    "routine": {
        "metric": "artificial_analysis_coding_index",
        "minimum": 10,
        "context": 32_000,
        "input_tokens": 4_000,
        "output_tokens": 1_500,
    },
    "coding": {
        "metric": "artificial_analysis_coding_index",
        "minimum": 20,
        "secondary": {"artificial_analysis_agentic_index": 15},
        "context": 64_000,
        "input_tokens": 10_000,
        "output_tokens": 4_000,
    },
    "agentic": {
        "metric": "artificial_analysis_agentic_index",
        "minimum": 25,
        "secondary": {"artificial_analysis_coding_index": 15},
        "requiresReasoning": True,
        "context": 64_000,
        "input_tokens": 12_000,
        "output_tokens": 5_000,
    },
    "review": {
        "metric": "artificial_analysis_intelligence_index",
        "minimum": 30,
        "secondary": {
            "artificial_analysis_agentic_index": 25,
            "artificial_analysis_coding_index": 20,
        },
        "requiresReasoning": True,
        "context": 96_000,
        "input_tokens": 16_000,
        "output_tokens": 8_000,
    },
    "critical": {
        "metric": "artificial_analysis_intelligence_index",
        "minimum": 35,
        "secondary": {"artificial_analysis_agentic_index": 25},
        "requiresReasoning": True,
        "context": 128_000,
        "input_tokens": 16_000,
        "output_tokens": 8_000,
    },
}

DEFAULT_CONFIG: dict[str, Any] = {
    "artificialAnalysis": {
        "enabled": True,
        "apiKeyEnv": "ARTIFICIAL_ANALYSIS_API_KEY",
        "cacheHours": 24,
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
        },
        "openrouter": {
            "enabled": True,
            "billing": "paid",
            "include": ["*"],
        },
        "nvidia": {
            "enabled": True,
            "billing": "free",
            "freeOnly": True,
            "allowFree": True,
            "include": ["*"],
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
        "allowUnknownCapability": True,
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


def catalog_for_provider(provider: str, refresh: bool) -> list[dict[str, Any]]:
    command = ["kilo", "models", provider, "--verbose"]
    if refresh:
        command.append("--refresh")
    return parse_catalog_output(provider, run_command(command))


def fetch_catalog(config: Mapping[str, Any], refresh: bool) -> tuple[list[dict[str, Any]], list[str]]:
    providers = config.get("providers", {})
    if not isinstance(providers, Mapping):
        raise RouterError("router config providers must be an object")
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
            models.extend(catalog_for_provider(provider, refresh))
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
                return {item["slug"]: item for item in cached.get("models", [])}, "cached"
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            pass

    environment_name = str(settings.get("apiKeyEnv", "ARTIFICIAL_ANALYSIS_API_KEY"))
    api_key = os.environ.get(environment_name)
    if not api_key:
        return {}, f"not configured ({environment_name} is unset)"

    models: list[dict[str, Any]] = []
    page = 1
    try:
        while page <= 10:
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
        return {}, f"unavailable ({type(error).__name__})"

    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"fetchedAt": time.time(), "models": models}), encoding="utf-8")
    except OSError:
        pass
    return {item["slug"]: item for item in models if item.get("slug")}, "fresh"


def normalize(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def tokens(value: str) -> set[str]:
    return {token for token in re.split(r"[^a-z0-9]+", value.lower()) if token}


def match_artificial_analysis(
    candidate: Candidate,
    model_config: Mapping[str, Any],
    aa_models: Mapping[str, Any],
) -> tuple[dict[str, Any] | None, str]:
    override = model_config.get("aaSlug")
    if override and str(override) in aa_models:
        return aa_models[str(override)], "configured"

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
        return None, "none"
    return best[1], "automatic"


def number(value: Any) -> float | None:
    return float(value) if isinstance(value, (int, float)) else None


def integer(value: Any) -> int:
    return int(value) if isinstance(value, (int, float)) else 0


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
        candidate = Candidate(
            route=route,
            provider=provider,
            model=model,
            name=str(raw.get("name", model)),
            status=str(raw.get("status", "unknown")),
            input_cost=input_cost,
            output_cost=output_cost,
            cache_read_cost=number(cache.get("read")) if isinstance(cache, Mapping) else None,
            context_limit=integer(raw.get("limit", {}).get("context")) if isinstance(raw.get("limit"), Mapping) else 0,
            output_limit=integer(raw.get("limit", {}).get("output")) if isinstance(raw.get("limit"), Mapping) else 0,
            tool_call=bool(capabilities.get("toolcall", capabilities.get("tool_call", False))),
            reasoning=bool(capabilities.get("reasoning", False)),
            attachment=bool(capabilities.get("attachment", False)),
            pdf=bool(input_capabilities.get("pdf", False)) if isinstance(input_capabilities, Mapping) else False,
            billing=("free" if provider_settings.get("freeOnly") else billing_class(route, provider_settings, model_settings)),
            free_allowed=bool(provider_settings.get("allowFree", False)),
        )
        aa, match = match_artificial_analysis(candidate, model_settings, aa_models)
        candidate.aa = aa
        candidate.aa_match = match
        availability.apply_to_candidate(candidate, availability_snapshot)
        candidates.append(candidate)
    return candidates


def infer_profile(task: str) -> str:
    lowered = task.lower()
    critical = ("security", "credential", "secret", "trading", "money", "financial", "architecture", "adversarial")
    routine = ("format", "rename", "summarize", "summary", "list", "status", "lookup", "find ", "simple")
    coding = ("code", "bug", "fix", "test", "refactor", "build", "gradle", "compile", "implement", "edit")
    if any(term in lowered for term in critical):
        return "critical"
    if any(term in lowered for term in routine) and not any(term in lowered for term in coding):
        return "routine"
    if any(term in lowered for term in coding):
        return "coding"
    review = ("review", "audit", "documentation", "analysis", "analyze", "instructions", "workflow", "delegate")
    if any(term in lowered for term in review):
        return "review"
    return "agentic"


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
        if candidate.billing in {"free", "subscription", "subscription/account-priced", "account-priced"}:
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
    minimum = number(profile.get("minimum"))
    if candidate.quality is not None and minimum is not None and candidate.quality < minimum:
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
    if candidate.quality is None and not policy.get("allowUnknownCapability", True):
        candidate.rejection = "Artificial Analysis capability mapping is unavailable"
        return False
    return True


def fallback_feature_score(candidate: Candidate) -> float:
    """Rank interface capability only when benchmark evidence is unavailable."""
    score = 3 if candidate.tool_call else 0
    score += 3 if candidate.reasoning else 0
    score += min(candidate.context_limit / 128_000, 8)
    score += 1 if candidate.attachment else 0
    score += 1 if candidate.pdf else 0
    return score


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
        raise RouterError("no candidate satisfies the current capability, cost, and privacy policy")

    def sort_key(candidate: Candidate) -> tuple[int, int, float, float, float]:
        unknown_quota = 1 if candidate.quota_state != "sufficient" else 0
        unknown = 1 if candidate.quality is None else 0
        cost = candidate.effective_cost if candidate.effective_cost is not None else float("inf")
        quality = candidate.quality if candidate.quality is not None else float("inf")
        remaining = -(candidate.quota_percent if candidate.quota_percent is not None else 0)
        return unknown, unknown_quota, cost, quality, remaining - fallback_feature_score(candidate)

    return min(usable, key=sort_key)


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
    selected = select_candidate(candidates, profile, config, sensitive)
    result = report(selected, profile_name, profile, aa_status, sensitive)
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


def build_kilo_command(args: argparse.Namespace, result: Mapping[str, Any]) -> list[str]:
    if args.tui:
        command = ["kilo", "--model", str(result["route"])]
        if args.agent:
            command.extend(["--agent", args.agent])
        if args.continue_session:
            command.append("--continue")
        if args.session:
            command.extend(["--session", args.session])
        command.extend(["--prompt", " ".join(args.message)])
        return command

    command = ["kilo", "run", "--model", str(result["route"])]
    if args.agent:
        command.extend(["--agent", args.agent])
    if args.variant:
        command.extend(["--variant", args.variant])
    if args.interactive:
        command.append("--interactive")
    if args.continue_session:
        command.append("--continue")
    if args.session:
        command.extend(["--session", args.session])
    if args.auto:
        command.append("--auto")
    command.extend(args.message)
    return command


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
    if args.tui and (args.interactive or args.variant or args.auto):
        raise RouterError("--tui cannot be combined with --interactive, --variant, or --auto")
    args.task = " ".join(args.message)
    context = load_selection_context(args)
    result = context["result"]
    attempted_routes: set[str] = set()
    excluded_providers: set[str] = set()

    for _ in range(MAX_FAILOVER_ATTEMPTS):
        print_selection(result, stream=sys.stderr)
        command = build_kilo_command(args, result)
        if args.tui:
            os.chdir(ROOT)
            os.execvp(command[0], command)
        if args.interactive:
            os.execvp(command[0], command)
        exit_code, output = run_kilo_streaming(command)
        if exit_code == 0:
            return 0
        kind = availability.failure_kind(output)
        attempted_routes.add(str(result["route"]))
        if not kind or kind not in {"rate_limit", "credits", "provider_unavailable", "authentication"}:
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
        excluded_providers.add(str(result["provider"]))
        print(
            f"model-router: {kind} on {result['route']}; trying another provider "
            f"(cooldown {cooldown}s)",
            file=sys.stderr,
        )
        candidates = copy.deepcopy(context["candidates"])
        try:
            next_candidate = select_candidate(
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
        result["warnings"] = context["warnings"]
    return 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RouterError as error:
        print(f"model-router: {error}", file=sys.stderr)
        raise SystemExit(2)
