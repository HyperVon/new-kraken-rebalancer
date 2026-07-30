# Agentic Development

## About this document

This document is written exclusively for human readers: project maintainers,
contributors, reviewers, and anyone interested in how the repository was built.
It explains the project's use of AI, the purpose of the checked-in agent files,
and the human responsibilities involved in continuing this style of development.

It is not an instruction file for coding agents. The operational instructions
used by AI tools live under [`.agents/`](../.agents/), with thin adapters for
individual harnesses. Nothing in this document changes agent behavior.

## Project provenance

This is an AI-native software project. The application code, tests,
documentation, and reusable agent playbook were produced through
human-directed AI coding sessions rather than a conventional all-human
implementation process.

The project owner provided the product goals, design preferences, domain
knowledge, risk decisions, acceptance criteria, and final approval. AI coding
agents contributed research, implementation, migrations, debugging, tests,
reviews, documentation, and repetitive repository maintenance.

AI assistance went well beyond code completion. It included:

- exploring alternative Java, Kotlin, Go, and TypeScript architectures;
- implementing the current Kotlin/JVM and Kotlin/JS application;
- hardening rebalancing, exchange, persistence, and history behavior;
- writing unit, integration, evaluation, browser, and regression tests;
- running formatting, build, coverage, simulation, and UI checks;
- reviewing changes with independent models;
- maintaining the README, changelog, architecture guides, and screenshots;
- converting recurring lessons into version-controlled rules and skills.

Most of the initial project and a substantial portion of its earlier evolution
were developed with Google Antigravity. It was the primary AI development
environment for an extended period, and the repository's original agent-guidance
structure grew out of that work.

Cursor, Codex, and OpenCode became part of the workflow much more recently—
roughly within the couple of weeks before this guide was written. They expanded
the project's multi-harness and multi-model practices; they were not the origin
of its AI-assisted development. The repository also contains checked-in
entrypoints for Claude Code and GitHub Copilot. Model choices have varied over
time and are not fully encoded in commit metadata.

The clearest model-specific record is the adversarial PR-review workflow. It
uses a fast reviewer role and a stronger reasoning reviewer role, currently
named as Composer 2.5 Fast and Grok 4.5 High when those models are available.
Equivalent substitutions are allowed because the review structure matters more
than a permanent dependency on one provider.

## The human role

The term “AI-developed” does not mean autonomous ownership. Humans remain
responsible for:

- deciding what the product should do;
- supplying business and operational context unavailable in the repository;
- approving changes that affect live trading, credentials, persistence, or
  data-loss risk;
- deciding whether a proposed architecture or tradeoff is appropriate;
- evaluating evidence rather than accepting confident model output;
- reviewing the final diff and release scope;
- controlling credentials, deployment, and production operation;
- accepting responsibility for financial outcomes.

The agent files make AI work more consistent and reviewable. They do not turn a
language model into a product owner, security authority, or accountable operator.

## Why the repository contains agent infrastructure

One-off prompts lose important context between sessions and differ between
harnesses. This repository stores durable development knowledge beside the code
so that a new human or AI-assisted session can recover the same architectural
and safety expectations.

The design favors ordinary Markdown and scripts over private vendor state. That
choice provides several benefits to human maintainers:

- changes to development guidance are visible in pull requests;
- historical decisions remain available after chat transcripts disappear;
- the same core guidance can be used from several coding tools;
- project-specific safety rules do not depend on model memory;
- recurring workflows become reviewable artifacts rather than personal habits;
- stale or conflicting guidance can be audited like source code.

## Repository map

| Path | What a human will find there |
| :--- | :--- |
| [`.agents/AGENTS.md`](../.agents/AGENTS.md) | Current stack, architecture map, safety invariants, quality thresholds, and skill index |
| [`.agents/OPERATING.md`](../.agents/OPERATING.md) | Harness-neutral working conventions shared across AI tools |
| [`.agents/skills/`](../.agents/skills/) | Focused workflows for particular domains and development tasks |
| [`.agents/improvement-backlog.md`](../.agents/improvement-backlog.md) | Product and engineering improvements discovered during iterative cycles |
| [`.agents/quality-backlog.md`](../.agents/quality-backlog.md) | QA findings, test gaps, defects, and deferred quality work |
| [`.agents/skill-content-backlog.md`](../.agents/skill-content-backlog.md) | Proposed and completed improvements to the agent playbook |
| [`.cursor/rules/`](../.cursor/rules/) | Cursor-native projections of the shared operating conventions |
| [`CLAUDE.md`](../CLAUDE.md) | Claude Code entrypoint linking to the portable guidance |
| [`.github/copilot-instructions.md`](../.github/copilot-instructions.md) | GitHub Copilot entrypoint linking to the portable guidance |
| [`CONTRIBUTING.md`](../CONTRIBUTING.md) | Human contribution, test, pull-request, and agent-assisted development guidance |

There is no root `AGENTS.md`. The canonical project file is
[`.agents/AGENTS.md`](../.agents/AGENTS.md). This layout keeps the portable
instruction system together under one directory instead of spreading full
copies among several harness-specific files.

## How the pieces relate

From a human maintainer's perspective, the files form four layers:

| Layer | Human meaning |
| :--- | :--- |
| Project facts | `AGENTS.md` records facts and invariants that should remain true regardless of the task or model |
| Working conventions | `OPERATING.md` records the repository's preferred way of planning, verifying, reviewing, and coordinating work |
| Task expertise | Individual skills preserve detailed procedures and domain-specific lessons that are relevant only to certain changes |
| Harness adapters | Cursor, Claude Code, and Copilot files make the portable core discoverable without becoming separate policy sources |

The human request remains the source of product intent. The checked-in files
provide project context and procedural consistency. Source code, tests, build
configuration, and verified external documentation provide factual evidence.

This separation also makes failures easier for a human to diagnose. A scope
mistake often indicates an unclear request. A technical-fact mistake may expose
stale repository guidance or insufficient source inspection. A repeated process
failure can reveal a skill that needs revision.

## Skill catalog

A skill is a version-controlled workflow centered on a `SKILL.md` file. Its
frontmatter provides a name and a short description used for routing; its body
contains the detailed procedure. Some skills include scripts, examples, or
reference material.

The repository intentionally avoids optional vendor presentation metadata in
these project skills. The portable `SKILL.md` is the shared artifact across
harnesses.

### Domain and implementation knowledge

| Skill | What it covers |
| :--- | :--- |
| [`common-kmp-module`](../.agents/skills/common-kmp-module/SKILL.md) | Shared KMP models, routes, IDs, view constants, and JVM/JS purity boundaries |
| [`coroutines-flows-sse`](../.agents/skills/coroutines-flows-sse/SKILL.md) | `Flow`, `SharedFlow`, config restarts, snapshots, and SSE architecture |
| [`dry-run-and-simulation`](../.agents/skills/dry-run-and-simulation/SKILL.md) | The distinct meanings and safety implications of `dryRun` and `simulation` |
| [`exposed-repository`](../.agents/skills/exposed-repository/SKILL.md) | Exposed tables, SQLite repositories, transactions, cascades, and upserts |
| [`frontend-js-development`](../.agents/skills/frontend-js-development/SKILL.md) | Kotlin/JS DOM behavior, HTMX hooks, charts, History controls, and client coverage |
| [`koin-di-and-config`](../.agents/skills/koin-di-and-config/SKILL.md) | Koin bindings, settings validation, atomic persistence, and config flows |
| [`kraken-api-integration`](../.agents/skills/kraken-api-integration/SKILL.md) | Kraken signing, symbols, rate limits, retries, order IDs, and credentials |
| [`ktor-html-views`](../.agents/skills/ktor-html-views/SKILL.md) | Server-rendered HTML, CSS DSL, HTMX, routes, and UI safety indicators |
| [`portfolio-rebalancing-math`](../.agents/skills/portfolio-rebalancing-math/SKILL.md) | BigDecimal portfolio math, drawdown deployment, dust, and order sequencing |
| [`trade-history-sync`](../.agents/skills/trade-history-sync/SKILL.md) | History pagination, deduplication, reconciliation, metadata, and simulation seeds |

### Engineering quality

| Skill | What it covers |
| :--- | :--- |
| [`complex-code-comments`](../.agents/skills/complex-code-comments/SKILL.md) | Useful why-comments, stale comments, and comment noise |
| [`dependency-upgrade`](../.agents/skills/dependency-upgrade/SKILL.md) | Stable dependency and toolchain upgrades plus API migrations |
| [`gradle-quality-gates`](../.agents/skills/gradle-quality-gates/SKILL.md) | Spotless, ktlint, JaCoCo, Karma, CI, and coverage verification |
| [`kotlin-refactoring-and-cleanup`](../.agents/skills/kotlin-refactoring-and-cleanup/SKILL.md) | Idiomatic Kotlin cleanup, shared constants, warnings, and duplication |
| [`reduce-code-size`](../.agents/skills/reduce-code-size/SKILL.md) | Behavior-preserving deletion, reuse, and cohesive file decomposition |
| [`todo-resolution`](../.agents/skills/todo-resolution/SKILL.md) | Discovery, implementation, verification, and retirement of actionable TODO comments |
| [`write-kotest`](../.agents/skills/write-kotest/SKILL.md) | JVM/JS Kotest, fakes, SQLite isolation, BigDecimal, and Flow tests |

### UI and documentation

| Skill | What it covers |
| :--- | :--- |
| [`changelog-and-docs-sync`](../.agents/skills/changelog-and-docs-sync/SKILL.md) | Incremental changelog, README, architecture-doc, and agent-doc synchronization |
| [`docs-screenshot-refresh`](../.agents/skills/docs-screenshot-refresh/SKILL.md) | Canonical README and User Guide screenshots produced from simulation |
| [`documentation-review`](../.agents/skills/documentation-review/SKILL.md) | Full documentation audits against current source and build truth |
| [`post-deploy-ui-smoke`](../.agents/skills/post-deploy-ui-smoke/SKILL.md) | Hard-refresh smoke testing after deployment |
| [`ui-manual-qa`](../.agents/skills/ui-manual-qa/SKILL.md) | Click-through testing of Dashboard, Settings, and History |
| [`ui-visual-implement`](../.agents/skills/ui-visual-implement/SKILL.md) | Implementation and screenshot verification of approved visual changes |
| [`ui-visual-review`](../.agents/skills/ui-visual-review/SKILL.md) | Screenshot-based visual critique without implementation |
| [`user-guide`](../.agents/skills/user-guide/SKILL.md) | Maintenance of the end-user walkthrough and embedded images |

### Review and release

| Skill | What it covers |
| :--- | :--- |
| [`adversarial-pr-review`](../.agents/skills/adversarial-pr-review/SKILL.md) | Parallel fast/strong model review, human-readable findings, fixes, and convergence |
| [`architecture-review`](../.agents/skills/architecture-review/SKILL.md) | Independent system redesign ideas without automatic implementation |
| [`code-review`](../.agents/skills/code-review/SKILL.md) | Project-specific diff review for correctness, safety, and conventions |
| [`commit-and-push`](../.agents/skills/commit-and-push/SKILL.md) | Documentation sync, full gates, deliberate commits, and branch pushes |
| [`open-pr`](../.agents/skills/open-pr/SKILL.md) | Fully verified pull-request creation with structured evidence |
| [`product-opportunity-review`](../.agents/skills/product-opportunity-review/SKILL.md) | User-needs discovery, feature opportunities, prioritization, and product roadmaps |
| [`rules-and-skills-audit`](../.agents/skills/rules-and-skills-audit/SKILL.md) | Structural conflicts, redundancy, stale guidance, and consolidation |
| [`skill-reviewer`](../.agents/skills/skill-reviewer/SKILL.md) | Content improvements for the project agent playbook |

### Orchestration and continuous work

| Skill | What it covers |
| :--- | :--- |
| [`autonomous-code-optimizer`](../.agents/skills/autonomous-code-optimizer/SKILL.md) | Multi-pass repository cleanup until a clean audit cycle |
| [`continuous-improvement`](../.agents/skills/continuous-improvement/SKILL.md) | Discovery, backlog management, bounded fixes, quality gates, and pull requests |
| [`continuous-quality`](../.agents/skills/continuous-quality/SKILL.md) | QA, edge-case invention, regression tests, fixes, and quality backlog |
| [`parallel-multi-agent`](../.agents/skills/parallel-multi-agent/SKILL.md) | Disjoint workstreams, file ownership, integration, and build coordination |

## How development has typically proceeded

The repository's current workflow emerged through repeated development cycles.
A typical cycle has included the following stages:

| Stage | Human-facing outcome |
| :--- | :--- |
| Goal definition | A concrete outcome, scope boundary, and acceptance criteria supplied by the project owner |
| Orientation | A summary of the current worktree, relevant architecture, tests, risks, and applicable project workflows |
| Implementation | A reviewable diff with focused behavior changes and regression coverage |
| Verification | Test, coverage, formatting, simulation, browser, or screenshot evidence appropriate to the change |
| Independent review | Findings from a separate model or reviewer, followed by source-based triage |
| Documentation | Updated product docs, agent guidance, screenshots, and a dated changelog entry when applicable |
| Release | A deliberate commit or pull request whose verification claims have already been completed |

For human reviewers, the important artifact is evidence at each stage. A large
diff without an orientation summary, a bug fix without a regression test, or a
visual claim without a screenshot is incomplete even when the generated prose
sounds convincing.

## Harnesses represented in the repository

| Harness | Repository support | Human takeaway |
| :--- | :--- | :--- |
| Google Antigravity | The initial project and much of its earlier development used Antigravity; the history of `.agents/AGENTS.md` records that foundation | Antigravity was the project's primary early harness, not merely another compatibility target |
| Cursor | `.cursor/rules/*.mdc` projects shared conventions into Cursor-native rules | Cursor provides the deepest checked-in adapter; some skills currently require its Canvas or browser integrations |
| Codex | The portable `.agents` files and most project skills are usable directly | Cursor-bound workflows need an equivalent artifact/tool or a small adaptation |
| OpenCode | No separate full policy copy is committed | A human can point OpenCode at the canonical `.agents` files or maintain a thin local adapter |
| Claude Code | `CLAUDE.md` links to the portable rules and skills | The adapter remains intentionally small |
| GitHub Copilot | `.github/copilot-instructions.md` links to the portable rules and skills | Copilot receives the same project context without a duplicated rulebook |
| Other tools | Ordinary Markdown, scripts, tests, and Git remain available | Any harness capable of reading repository files can reuse the core policy and domain knowledge |

Harness support is not perfectly symmetrical. The `architecture-review` skill
currently mandates a Cursor Canvas for decisions, while `ui-manual-qa` names
Cursor's browser-control integration. A human using another harness needs to
substitute an equivalent decision artifact or browser tool, or adapt those
skills before expecting their end-to-end workflows to run unchanged. These
integrations are not the source of project policy; the underlying architecture,
safety rules, checklists, and review criteria remain ordinary repository files.

## Models and multi-model review

The project does not maintain a complete ledger mapping every commit to a model.
That omission is deliberate: model availability and quality change faster than
the architecture and safety constraints of the application.

The project instead records model roles where diversity is valuable. The
adversarial PR workflow pairs:

- a faster, lower-cost reviewer for broad defect discovery; and
- a stronger reasoning reviewer for deeper correctness and safety analysis.

Composer 2.5 Fast and Grok 4.5 High are the currently documented examples for
those roles. A substitution is meaningful to a human reviewer only when the
role, capability difference, and reason for substitution remain visible.

When running under the OpenCode harness, the adversarial PR-review workflow
substitutes the following models while preserving the fast/strong role split:

- fast/cheaper reviewer: **DeepSeek V4 Flash**
- strong/high-reasoning reviewer: **MiMo V2.5**
- default session model: **Hy3**

These substitutions are recorded in the PR verification notes and final summary
when the review runs under OpenCode.

Multiple agreeing models are not proof of correctness. Related models can share
the same blind spots, repeat inaccurate documentation, or approve tautological
tests. Source inspection, executable evidence, official Kraken documentation,
and human judgment remain more authoritative than reviewer consensus.

## Quality evidence available to humans

The repository's consolidated pre-commit check is:

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

It reports the changelog-policy check, Markdown linting, Spotless/ktlint,
Gradle build results, JaCoCo coverage verification, and Kotlin/JS browser tests.
Human reviewers can compare these results with the claims in a commit or pull
request.

Some changes require evidence beyond the consolidated script:

- UI changes may need a simulation boot, click-through QA, laptop-width visual
  inspection, and updated screenshots.
- Rebalancing and exchange changes may need focused evaluation scenarios and
  verification of Kraken semantics against official documentation.
- Documentation changes need link checks, catalog consistency, and Markdown
  linting.
- Deployment observations need a hard refresh so stale browser assets are not
  mistaken for current behavior.

High coverage is a valuable guardrail, not a guarantee. A test can encode the
same misconception as the implementation, so the relevance of assertions still
requires human review.

## Safety context for human reviewers

This application can place real cryptocurrency orders. Anyone reviewing or
operating an AI-assisted change needs to understand several non-negotiable
facts:

- `dryRun` and `simulation` are separate settings with different effects.
- Examples and tests normally remain in simulation or dry-run modes.
- Kraken credentials and real account data do not belong in commits or review
  artifacts.
- BigDecimal precision, price guards, dust thresholds, sell-first ordering,
  cash caps, and the live-order submission journal protect real funds.
- An ambiguous AddOrder outcome remains uncertain and blocks unsafe follow-up
  submissions.
- Changes to live execution, credentials, persistence, or destructive behavior
  require an explicit human decision.
- Exchange-semantic claims need confirmation from official Kraken documentation,
  not model memory.

The dashboard has no user authentication and assumes a local or private network.
It should not be presented as safe for unrestricted internet exposure. Any CORS
expansion deserves deliberate human scrutiny.

## Continuing development as a human contributor

A human joining the project can get oriented through this sequence:

1. Read the product overview and technology journey in [`README.md`](../README.md).
2. Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) for the development and pull-request
   workflow.
3. Review [`.agents/AGENTS.md`](../.agents/AGENTS.md) for the current architecture,
   safety invariants, quality thresholds, and skill index.
4. Review [`.agents/OPERATING.md`](../.agents/OPERATING.md) to understand the
   conventions used across AI-assisted sessions.
5. Open the relevant skill when evaluating how a particular task has been
   automated or standardized.
6. Compare proposed changes with source, tests, and the appropriate deep guide:
   [`ALGORITHM.md`](ALGORITHM.md), [`FLOWS.md`](FLOWS.md), or
   [`EVALUATION.md`](EVALUATION.md).
7. Require evidence proportionate to the risk before approving a commit,
   pull request, deployment, or live-trading change.

No particular harness is required for ordinary contribution. Specialized
Cursor-bound workflows need an equivalent integration or a small adapter in
another harness. The important continuity comes from the version-controlled
context, repeatable checks, preserved decisions, and human oversight.

## Maintaining the playbook

Human maintainers can use the following map when the development system itself
needs an update:

| Change | Canonical location |
| :--- | :--- |
| Project fact, architecture boundary, or safety invariant | `.agents/AGENTS.md` |
| Convention shared by every harness | `.agents/OPERATING.md` and the matching Cursor projection |
| Detailed repeatable workflow | `.agents/skills/<name>/SKILL.md` |
| Cursor-only integration detail | The relevant `.cursor/rules/*.mdc` file or skill, with a portable explanation retained |
| Human explanation of the overall system | This document |
| Skill routing | The skill frontmatter and `.agents/AGENTS.md` index |
| Proposed playbook improvements | `.agents/skill-content-backlog.md` |

Instruction changes deserve the same review discipline as code changes. A stale
rule can repeatedly produce bad diffs, while a precise skill can prevent the
same mistake across many models and sessions.

The repository provides three different review lenses for this material:

- `documentation-review` checks whether the documentation matches reality;
- `skill-reviewer` looks for missing project knowledge and better guidance; and
- `rules-and-skills-audit` looks for conflicts, duplication, and unclear scope.

## Limitations and responsible use

- AI-generated code can contain subtle bugs even with high coverage.
- Coverage measures executed structure, not product correctness.
- Models and harnesses change over time; portable files reduce but do not
  eliminate variability.
- Git history records repository outcomes, not complete session transcripts or
  hidden model reasoning.
- Skills can become stale as architecture, dependencies, external APIs, and
  quality gates evolve.
- Humans remain accountable for releases, credentials, production deployment,
  and live-trading outcomes.

The purpose of this system is not to remove humans from software development.
It is to make human-directed AI work more reproducible, inspectable, portable,
and safe than a collection of one-off prompts.
