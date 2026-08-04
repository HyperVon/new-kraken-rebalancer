"""Bounded track presets for project skills that support parallel discovery."""

from __future__ import annotations

from typing import Any


def _track(
    track_id: str,
    profile: str,
    agent: str,
    files: list[str],
    focus: str,
) -> dict[str, Any]:
    return {
        "id": track_id,
        "profile": profile,
        "agent": agent,
        "files": files,
        "read_only": True,
        "task": focus,
    }


WORKFLOW_TRACKS: dict[str, list[dict[str, Any]]] = {
    "documentation-adversarial-review": [
        _track(
            "finding-verification",
            "agentic",
            "documentation-contract-auditor",
            ["README.md", "docs/", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md", ".agents/skills/documentation-review/"],
            "Independently verify every parent-supplied documentation finding against current source, tests, and build truth.",
        ),
        _track(
            "completeness-sweep",
            "agentic",
            "explore",
            ["README.md", "docs/", ".agents/", ".cursor/", "src/", "common/", "frontend-js/", "build.gradle.kts", ".github/workflows/"],
            "Search for documentation issues missed by the parent review and report only distinct evidence-backed findings.",
        ),
        _track(
            "severity-evidence",
            "critical",
            "documentation-contract-auditor",
            ["README.md", "docs/", "SECURITY.md", "CONTRIBUTING.md", ".agents/"],
            "Challenge finding severity, categorization, evidence strength, and proposed fixes; reject preference-only claims.",
        ),
    ],
    "documentation-review": [
        _track(
            "product-docs",
            "routine",
            "documentation-contract-auditor",
            ["README.md", "docs/USER_GUIDE.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md"],
            "Audit product, setup, security, and end-user documentation against current source truth.",
        ),
        _track(
            "runtime-contracts",
            "coding",
            "explore",
            ["docs/ALGORITHM.md", "docs/FLOWS.md", "docs/EVALUATION.md", "src/", "common/", "frontend-js/"],
            "Audit algorithm, Flow/SSE, evaluation, and runtime-contract documentation against implementation and tests.",
        ),
        _track(
            "agent-guidance",
            "routine",
            "agent-guidance-auditor",
            [".agents/", ".cursor/rules/", "CLAUDE.md", ".github/copilot-instructions.md", ".kilo/"],
            "Audit agent rules, skills, Kilo workflows, projections, and links for drift or contradictory delegation claims.",
        ),
        _track(
            "build-config",
            "coding",
            "explore",
            ["build.gradle.kts", "gradle/", ".github/workflows/", "rebalancer-config-template.json", ".kilo/"],
            "Audit build, CI, configuration-template, toolchain, coverage, and script claims against current files.",
        ),
    ],
    "autonomous-code-optimizer": [
        _track(
            "static-security",
            "coding",
            "explore",
            ["src/", "common/", "frontend-js/", "codegen/"],
            "Perform a read-only Pass 1 scan for warnings, FQNs, secrets, dead code, and static quality issues.",
        ),
        _track(
            "money-concurrency",
            "critical",
            "explore",
            ["src/main/", "common/src/", "src/test/", "common/src/commonTest/"],
            "Perform a read-only scan of money paths, modes, cancellation, concurrency, persistence, and retry invariants.",
        ),
        _track(
            "architecture-layers",
            "agentic",
            "explore",
            ["src/main/", "common/src/", "frontend-js/src/", "codegen/src/"],
            "Perform a read-only architecture, layering, SRP, KMP-purity, and DI-shape scan using the owning architecture guidance.",
        ),
        _track(
            "tests-guidance",
            "routine",
            "documentation-contract-auditor",
            ["src/test/", "common/src/commonTest/", "frontend-js/src/test/", ".agents/", "docs/"],
            "Perform a read-only scan for missing regression coverage, stale guidance, and verification gaps related to optimizer findings.",
        ),
    ],
    "continuous-improvement": [
        _track("code", "coding", "explore", ["src/", "common/", "codegen/"], "Discover high-value code and architecture improvements without editing."),
        _track("ui", "coding", "explore", ["frontend-js/", "src/main/", "docs/images/"], "Discover bounded UI, SSR, CSS, and client behavior improvements without editing."),
        _track("docs", "routine", "documentation-contract-auditor", ["README.md", "docs/", ".agents/"], "Discover documentation and agent-guidance improvements against current source truth."),
        _track("dependencies", "routine", "explore", ["build.gradle.kts", "gradle/", "package.json", ".github/workflows/"], "Discover dependency, toolchain, and CI improvements without changing versions."),
    ],
    "continuous-quality": [
        _track("runtime-edges", "critical", "explore", ["src/main/", "common/src/"], "Discover runtime, money-path, mode, persistence, and concurrency edge cases without editing."),
        _track("tests-evaluation", "coding", "explore", ["src/test/", "common/src/commonTest/", "frontend-js/src/test/", "docs/EVALUATION.md"], "Discover missing tests, evaluation scenarios, and coverage gaps without editing."),
        _track("history-flows", "agentic", "explore", ["src/main/", "docs/FLOWS.md", "docs/ALGORITHM.md"], "Discover history-sync, Flow, SSE, and state-transition regressions without editing."),
        _track("ui-regressions", "coding", "explore", ["frontend-js/", "src/main/", "docs/USER_GUIDE.md"], "Discover UI, SSR, HTMX, and browser regression cases without editing."),
    ],
    "adversarial-pr-review": [
        _track("runtime-correctness", "coding", "explore", ["src/", "common/", "frontend-js/"], "Review changed runtime behavior and direct source dependencies for concrete regressions."),
        _track("tooling-security", "agentic", "agent-guidance-auditor", [".kilo/", ".agents/", ".cursor/", ".github/", "build.gradle.kts"], "Review changed tooling, permissions, credentials, configuration, and workflow safety claims."),
        _track("tests-docs", "routine", "documentation-contract-auditor", ["src/test/", "common/src/commonTest/", "docs/", "CHANGELOG.md"], "Review changed tests and documentation for contract gaps or misleading verification claims."),
    ],
    "ai-slop-detector": [
        _track("production-build", "coding", "explore", ["src/", "common/", "frontend-js/", "build.gradle.kts"], "Audit production and build artifacts for needless complexity, invented integrations, and unprotected behavior."),
        _track("tests-evaluation", "coding", "explore", ["src/test/", "common/src/commonTest/", "frontend-js/src/test/", "docs/EVALUATION.md"], "Audit tests and evaluations for mirror coverage, dead assertions, and missing behavior protection."),
        _track("docs-skills-rules", "routine", "agent-guidance-auditor", ["docs/", ".agents/", ".cursor/", "CLAUDE.md"], "Audit documentation and agent artifacts for stale claims, dead instructions, and architecture drift."),
        _track("ui-assets", "coding", "explore", ["src/main/", "frontend-js/", "docs/images/"], "Audit UI and asset artifacts for duplicated behavior, invented integrations, and misleading visuals."),
    ],
    "complex-code-comments": [
        _track("backend-comments", "coding", "explore", ["src/main/"], "Find non-obvious backend and money-path logic that needs a concise why-comment or has stale comments."),
        _track("common-frontend-comments", "coding", "explore", ["common/src/", "frontend-js/src/"], "Find complex shared and client logic that needs comment hygiene without editing files."),
        _track("tests-guidance-comments", "routine", "documentation-contract-auditor", ["src/test/", "common/src/commonTest/", ".agents/", "docs/"], "Find stale or noisy comments in tests and guidance without editing files."),
    ],
    "dependency-upgrade": [
        _track("kotlin-gradle", "routine", "explore", ["build.gradle.kts", "gradle/", "codegen/"], "Detect Kotlin, Gradle, KSP, and JVM toolchain upgrade opportunities without editing."),
        _track("server-stack", "routine", "explore", ["src/", "common/", "build.gradle.kts"], "Detect Ktor, Koin, Exposed, coroutines, and server dependency upgrade opportunities without editing."),
        _track("frontend-ci", "routine", "explore", ["frontend-js/", "package.json", ".github/workflows/"], "Detect Kotlin/JS, npm, browser-test, and CI upgrade opportunities without editing."),
    ],
    "architecture-review": [
        _track("backend-domain", "agentic", "explore", ["src/main/", "common/src/"], "Map backend, domain, trading, persistence, and configuration architecture for alternatives."),
        _track("ui-flows", "agentic", "explore", ["src/main/", "frontend-js/src/", "common/src/", "docs/FLOWS.md"], "Map HTTP, SSR, HTMX, frontend, and reactive-flow architecture for alternatives."),
        _track("operations-security", "critical", "agent-guidance-auditor", [".agents/", ".kilo/", ".github/", "SECURITY.md", "CONTRIBUTING.md"], "Map product, security, operations, and agent-harness architecture for alternatives."),
    ],
    "rules-and-skills-audit": [
        _track("canonical-rules", "routine", "agent-guidance-auditor", [".agents/AGENTS.md", ".agents/OPERATING.md"], "Audit canonical rules and always-on operating norms for conflicts and stale assumptions."),
        _track("domain-skills", "routine", "agent-guidance-auditor", [".agents/skills/"], "Audit domain skills for routing, scope, safety, and code-contract drift."),
        _track("projections-harness", "routine", "agent-guidance-auditor", [".cursor/", ".kilo/", "CLAUDE.md", ".github/copilot-instructions.md"], "Audit harness projections, Kilo commands, and cross-file guidance links for drift."),
    ],
    "skill-reviewer": [
        _track("domain-content", "agentic", "agent-guidance-auditor", [".agents/skills/"], "Review domain skill content for missing patterns, anti-patterns, and verification checklists."),
        _track("workflow-content", "routine", "agent-guidance-auditor", [".agents/skills/parallel-multi-agent", ".agents/skills/continuous-improvement", ".agents/skills/continuous-quality", ".agents/skills/adversarial-pr-review"], "Review orchestration skills for coherent delegation, integration, and stop contracts."),
        _track("harness-index", "routine", "agent-guidance-auditor", [".agents/AGENTS.md", ".agents/OPERATING.md", ".cursor/", ".kilo/", "CLAUDE.md"], "Review indexes, projections, and harness routing for drift and dead references."),
    ],
}


DISTINCT_ROUTE_WORKFLOWS = frozenset({"adversarial-pr-review", "documentation-adversarial-review"})


def requires_distinct_routes(workflow: str | None) -> bool:
    return workflow in DISTINCT_ROUTE_WORKFLOWS


def available_workflows() -> tuple[str, ...]:
    return tuple(sorted(WORKFLOW_TRACKS))


def build_tracks(workflow: str, parent_task: str) -> list[dict[str, Any]]:
    if workflow not in WORKFLOW_TRACKS:
        raise KeyError(workflow)
    return [
        {
            **track,
            "task": f"Parent request:\n{parent_task}\n\nTrack focus:\n{track['task']}",
        }
        for track in WORKFLOW_TRACKS[workflow]
    ]
