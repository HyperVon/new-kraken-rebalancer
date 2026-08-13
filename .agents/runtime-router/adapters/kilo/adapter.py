"""Kraken's thin Kilo adapter around the reusable ARR Kilo integration."""

from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any

from agent_runtime_router import EffortLevel
from agent_runtime_router.harnesses.contracts import (
    DiscoveryReport,
    DiscoveryRequest,
    EvidenceStatus,
    HarnessProfile,
    ProbeEvidence,
    VerificationReport,
)
from agent_runtime_router.integrations.kilo import KiloAdapter
from agent_runtime_router.observations import Freshness

from catalog import CatalogError, discover_candidates


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CatalogError("target_config_unreadable") from exc
    if not isinstance(value, dict):
        raise CatalogError("target_config_invalid")
    return value


class KrakenCatalogSource:
    """Target-owned Kilo model listing source with redacted ARR output."""

    def __init__(self, executable: str, provider_policy: dict[str, Any]) -> None:
        self.executable = str(Path(executable).resolve())
        self.provider_policy = provider_policy

    def discover(self, request: DiscoveryRequest) -> DiscoveryReport:
        try:
            candidates = discover_candidates(
                self.executable,
                self.provider_policy,
                refresh=request.refresh,
                timeout_seconds=request.timeout_seconds,
            )
        except CatalogError as exc:
            code = str(exc).split(":", 1)[0].strip() or "catalog_failed"
            return DiscoveryReport(
                adapter_id="kilo",
                status=EvidenceStatus.UNKNOWN,
                probes=(ProbeEvidence("kilo-models", "kilo-cli", EvidenceStatus.UNKNOWN, error_code=code),),
                error_code=code,
            )
        if len(candidates) > request.max_candidates:
            candidates = candidates[: request.max_candidates]
        return DiscoveryReport(
            adapter_id="kilo",
            status=EvidenceStatus.BEST_EFFORT,
            candidates=tuple(candidates),
            probes=(ProbeEvidence("kilo-models", "kilo-cli", EvidenceStatus.BEST_EFFORT, Freshness.FRESH),),
        )


def build_adapter(target_root: Path, executable: str | Path | None = None) -> KiloAdapter:
    policy_dir = target_root / ".agents" / "runtime-router" / "adapters" / "kilo"
    provider_policy = load_json(policy_dir / "provider-policy.json")
    kilo = str(executable or shutil.which("kilo") or "")
    if not kilo:
        raise CatalogError("kilo_executable_missing")
    kilo_path = Path(kilo)
    if not kilo_path.is_absolute():
        raise CatalogError("kilo_executable_not_absolute")
    profile_path = target_root / ".agents" / "runtime-router" / "active-harness.json"
    profile = None
    if profile_path.is_file():
        profile_mapping = load_json(profile_path)
        pointer_markers = {
            "namespace",
            "evidence",
            "evidence_sha256",
            "switched_at_epoch_seconds",
        }
        if pointer_markers.intersection(profile_mapping):
            if profile_mapping.get("harness_id") != "kilo":
                raise CatalogError("active_harness_mismatch")
            profile_mapping = load_json(
                target_root
                / ".agents"
                / "runtime-router"
                / "harnesses"
                / "kilo"
                / "profile.json"
            )
        profile = HarnessProfile.from_mapping(profile_mapping)
    return KiloAdapter(
        str(kilo_path),
        discovery_source=KrakenCatalogSource(str(kilo_path), provider_policy),
        profile=profile,
        version=profile.version if profile else None,
        effort_variants={
            # The native mapping is deliberately target-visible and can be
            # changed when Kilo verifies a new variant contract.
            EffortLevel.MINIMAL: "instant",
            EffortLevel.LOW: "low",
            EffortLevel.MEDIUM: "medium",
            EffortLevel.HIGH: "high",
            EffortLevel.XHIGH: "xhigh",
            EffortLevel.MAX: "max",
        },
    )


__all__ = ["KrakenCatalogSource", "build_adapter", "load_json"]
