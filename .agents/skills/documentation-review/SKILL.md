---
name: documentation-review
description: >-
  Full documentation audit against source code — finds missing, outdated, and
  incorrect content in README, CHANGELOG, SECURITY, CONTRIBUTING, docs/*,
  .agents/AGENTS.md, skills, and config templates, then updates docs to match
  reality. Use when the user asks for a documentation review, docs audit,
  sync docs with code, refresh project docs, or fix stale/wrong documentation.
---

# Full Documentation Review

Perform an end-to-end audit of project documentation against the **current
source code and build config**, then apply corrections. Source of truth is
always the code / Gradle / CI — never older docs or CHANGELOG history.

This skill is the **full audit**. For incremental “I just shipped X, touch the
relevant docs” work, use [changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md).
For regenerating README screenshots from a running simulation UI, use
[docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md) (do not try to
“fix” PNGs by editing markdown alone). For the end-user walkthrough, maintain
[docs/USER_GUIDE.md](../../../docs/USER_GUIDE.md) via
[user-guide](../user-guide/SKILL.md). For a **meta-review** of skill structure,
coverage, and agent routing (recommend-only), use
[skill-reviewer](../skill-reviewer/SKILL.md).

---

## Scope (must review)

| Document | Role |
| :--- | :--- |
| `README.md` | Product overview, stack versions, setup, package tree, screenshots |
| `docs/USER_GUIDE.md` | End-user visual walkthrough (must embed current `docs/images/*`) |
| `CHANGELOG.md` | Keep a Changelog — note gaps only; do not rewrite history |
| `CONTRIBUTING.md` | Dev setup, PR expectations, coding guidelines |
| `SECURITY.md` | Vulnerability reporting, deploy/security guidance |
| `CODE_OF_CONDUCT.md` | Only if links/contacts are broken |
| `docs/ALGORITHM.md` | Rebalancing math & execution |
| `docs/FLOWS.md` | Flow / SharedFlow / SSE architecture |
| `docs/EVALUATION.md` | Scenario evaluation suite |
| `.agents/AGENTS.md` | Invariants, skill index, stack pins |
| `.agents/OPERATING.md` | Always-on norms; must stay aligned with `.cursor/rules/*.mdc` |
| `CLAUDE.md` / `.github/copilot-instructions.md` | Thin harness entrypoints → `.agents/` |
| `.cursor/rules/*.mdc` | Cursor projections of OPERATING.md (must be committed) |
| `.agents/skills/*/SKILL.md` | Domain guidance must match code |
| `rebalancer-config-template.json` | Settings keys vs `Settings` / `AppConfig` |
| `.github/workflows/*` | CI commands/JDK must match CONTRIBUTING/README |

Do **not** invent new marketing docs. Prefer correcting existing files.

---

## Workflow

Copy this checklist and track progress:

```text
- [ ] Step 0: Inventory code truth
- [ ] Step 0.5: Decide parallel tracks and obtain route/serial approval
- [ ] Step 1: Audit each doc (missing / wrong / stale)
- [ ] Step 2: Produce findings report
- [ ] Step 3: Apply doc fixes
- [ ] Step 4: Sync agent skills & AGENTS index if needed
- [ ] Step 5: Lint & verify
```

### Step 0: Inventory code truth

Gather facts from code/build (do not trust docs yet):

1. **Versions** — `build.gradle.kts` (root aggregator), `backend/build.gradle.kts`,
    `common/build.gradle.kts`, `frontend-js/build.gradle.kts`,
    `gradle/wrapper/gradle-wrapper.properties`, JDK toolchain, Spotless/ktlint.
2. **Architecture** — packages under `backend/src/main/kotlin/com/gemini/krakenbot/`,
    `:common`, `:frontend-js`, `:backend`; key types (`PortfolioManagerImpl`,
   `PortfolioAnalyzerImpl`, `PortfolioCalculations`, `OrderExecutorImpl`,
   `DynamicKrakenService`, `RateLimiter`, `TradeHistoryServiceImpl`,
   `DashboardRoutes` / `DashboardController`, view components).
3. **Config surface** — `Settings`, `AppConfig`, `Allocation`,
   `rebalancer-config-template.json` keys (`dryRun`, `simulation`,
    `fiatMaxDrawdown`, `minimumOrderSizeUSD`, etc.).
4. **HTTP / UI** — `Routes` / `FormFields` in `:common`; SSE path; History
   summary cards contract.
5. **Algorithm** — `PortfolioCalculations`, `PortfolioAnalyzerImpl`,
   `OrderExecutorImpl` vs `docs/ALGORITHM.md`.
6. **Flows** — config `SharedFlow`, snapshot `SharedFlow`, cold poll/sync
   flows vs `docs/FLOWS.md`.
7. **Tests / evaluation** — `EvaluationScenariosTest` scenario count/names and
   the separate `SimulationEvaluationScenariosTest` production-emulator
   invariants vs `docs/EVALUATION.md`; coverage gates in JaCoCo +
   `frontend-js/karma.config.d/coverage.js`.
8. **CI** — `.github/workflows/*` (verify CodeQL language support and triggers).
9. **Security model** — no dashboard auth; CORS via `isLocalOrPrivateOrigin`.

Use `rg`, package listings, and targeted file reads. Prefer evidence over memory.

### Parallel audit handoff

The Step 0 code-truth inventory and model selection are different concerns. After
the parent has captured a bounded source/build fact sheet, a broad audit with at
least two disjoint evidence tracks must reach an explicit parallel-or-serial
decision before Step 1. Use [parallel-multi-agent](../parallel-multi-agent/SKILL.md):

| Track | Scope |
| :--- | :--- |
| Product / setup | `README.md`, `docs/USER_GUIDE.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md` |
| Runtime contracts | `docs/ALGORITHM.md`, `docs/FLOWS.md`, `docs/EVALUATION.md` |
| Agent guidance | `.agents/`, `.cursor/rules/`, harness entrypoints, skill links |
| Build / configuration | `build.gradle.kts`, CI, templates, scripts, dependency/tooling claims |

Before launching, select a host-supported route and effort when exposed for each
track. Kilo sessions inherit `kilo/kilo-auto/efficient` from `.kilo/kilo.json`;
select `kilo/kilo-auto/frontier` for high-risk or disputed documentation claims.
Kilo Auto chooses its underlying model server-side, so do not claim a specific
underlying model or recreate its catalog and fallback logic in repository
scripts. If a host Task accepts only a role and cannot expose a usable route,
keep the audit parent-owned; never use an unverified role-only worker.
Use `review_surface.sh` only for a branch-scoped changed-path surface; it does
not select or probe models.
The host prints the track matrix, route, effort, and quota evidence before
launching. The user's request for this read-only audit authorizes the
bounded discovery fan-out; use `question` only when a hard availability or scope
decision remains unresolved.
Size each track to the auditor's iteration cap and reserve the final step for
its report. Split a multi-document track before launch when its evidence reads
and checks cannot fit while preserving that final report step; a running worker
without a final report does not count as completed parallel coverage.
Workers report evidence and paths only; the parent deduplicates findings,
applies edits, runs Mermaid/Markdown/build checks, and owns the final report.

If native route selection remains unavailable, that is a delegation limitation,
not an incomplete code-truth inventory. When a broad audit was requested, use
`question` to ask whether to continue parent-owned serially or stop while route
support is configured; do not silently pre-authorize that fallback. Never
substitute an unverified role or `general` for a model. A small or coupled scope
may proceed without this handoff.

For Kilo broad audits, launch the bounded read-only audit tracks through the
host's native parallel task surface with the selected routes recorded per track.

The command prints the route/quota matrix before launching; the read-only
workflow is authorized to launch its evidence
tracks. The host owns only read-only evidence tracks; the parent remains
responsible for findings, edits, and gates.
Do this handoff before a parent-wide Context Mode or regex/search inventory;
parent-owned scans are follow-up evidence, not a substitute for the routed
track reports.

For a second-pass adversarial review of this audit, launch a distinct-route
review track with the parent findings in the task context. Launch all three
tracks concurrently in the background-capable host, then inspect the route
report before accepting any claim of independent-model confirmation.

## Evidence and claims

Treat every material documentation statement as a claim that needs a source:

- Verify behavior against code, build configuration, tests, CI, or safely
  observed simulation behavior. Cite the path, heading, command, or test that
  supports the correction.
- Separate source truth, documented intent, inference, and unresolved
  assumptions. Do not fill a missing fact with plausible wording.
- For external or time-sensitive claims, prefer primary or authoritative sources
  and record the date. Do not preserve a stale claim merely because it appears
  in an older document.
- When documentation, source, and external references contradict one another,
  classify the mismatch, resolve it using the strongest current evidence, and
  record any remaining uncertainty as a gap or deferment.
- Before changing a high-impact safety, dependency, or workflow claim, perform
  a targeted gap check and confirm that the proposed wording does not imply a
  broader guarantee than the repository actually provides.

### Step 1: Audit categories

For every in-scope doc, classify findings:

| Category | Meaning | Action |
| :--- | :--- | :--- |
| **Wrong** | Contradicts code/build | Correct to match code |
| **Stale** | Was true, now outdated (versions, paths, class names) | Update |
| **Missing** | Important behavior exists in code but undocumented | Add concise coverage |
| **Orphan** | Doc describes removed APIs/packages/flags | Remove or rewrite |
| **Skill drift** | `.agents` skill/AGENTS contradicts code | Fix skill or AGENTS |
| **Stale screenshots** | README UI images lag recent view/CSS/JS changes | Defer capture to [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md) |
| **Broken diagram** | Mermaid block fails to render in a viewer | Rewrite to syntax supported by Mermaid 8.x (see below) |

High-risk mismatch examples:

- Stack versions in README/AGENTS ≠ Gradle
- Package tree in README ≠ `backend/src/main/kotlin` / `repository/table/`
- `dryRun` confused with `simulation`
- Rate limiting described as “Mutex only” without call-counter `RateLimiter`
- Algorithm missing fiat deployment / fiat correction / dust / 99% buy / 95% settle
- Coverage stated as vague “75%+ JS” instead of Karma 90/80/90/75
- Lint paths pointing at root `AGENTS.md` (file is `.agents/AGENTS.md`)
- Evaluation suite tables out of sync with `EvaluationScenariosTest` or
  `SimulationEvaluationScenariosTest`
- Config template missing keys present on `Settings`

#### Code snippet, flag, and link verification

- **Export & signature match:** Verify that imported symbols, class names, method signatures, and parameter names match current source exports exactly.
- **CLI flag validation:** Compare flags against CLI help output (`--help`) or argument parser definitions.
- **Config syntax validation:** Parse configuration examples (`rebalancer-config-template.json`) against current parser models (`Settings`, `AppConfig`).
- **Link and anchor verification:** Verify that every relative file link resolves to a tracked file and heading anchors match exact slugified header text. Run `python3 .agents/scripts/validate_skills.py` to verify links deterministically.

#### Mermaid compatibility

GitHub ships a modern Mermaid, but IDE preview panes may still bundle 8.x, where a
diagram that renders on GitHub shows *"Syntax error in graph"*. Keep every block in
`README.md`, `docs/ALGORITHM.md`, and `docs/FLOWS.md` parseable by 8.x:

- **Quote any label containing non-ASCII or punctuation** — `B{"Deviation ≥ Trigger?"}`,
  not `B{Deviation ≥ Trigger?}`; unquoted `≥ → × ±` is a lexical error.
- **Use `participant`, not `actor`** in sequence diagrams (`actor` is newer syntax).
- `\n` and `<br/>` both work inside quoted labels; either is fine.

**Always run the validator** after editing any ```mermaid fence (do not rely on GitHub
preview alone):

```bash
python3 -m venv /tmp/kraken-screenshots
/tmp/kraken-screenshots/bin/pip install -q playwright
/tmp/kraken-screenshots/bin/python \
  .agents/skills/documentation-review/scripts/validate_mermaid.py
# Optional visual check:
#   .../validate_mermaid.py --render /tmp/mermaid-renders
```

The script downloads/caches Mermaid **8.8.0** and fails if any block does not parse.
Treat a non-zero exit as a **Broken diagram** finding and fix before declaring the
docs review complete. Incremental edits that touch diagrams should use the same
script via [changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md).

### Step 2: Findings report (before editing)

Present a short report to the user (or keep as working notes if they asked to
“just fix docs”):

```markdown
# Documentation review

## Summary
N wrong / N stale / N missing / N orphan

## Findings
### [WRONG|STALE|MISSING|ORPHAN] Title
- **Doc**: `path` (section)
- **Evidence**: `code/path` or build fact
- **Fix**: …

## Out of scope / deferred
…
```

If the user only asked to create/run the skill without “fix everything”, stop
after the report and ask before applying large edits. If they asked to update
docs, proceed to Step 3.

### Step 3: Apply fixes

Edit docs to match code. Rules:

1. **Prefer minimal diffs** — correct statements; avoid wholesale rewrites unless
   a section is irreparably wrong.
2. **Keep tone** of existing docs (README marketing + technical; ALGORITHM
   precise; FLOWS diagram-friendly).
3. **Cross-link** rather than duplicate: ALGORITHM ↔ portfolio skill; FLOWS ↔
   coroutines-flows-sse; EVALUATION ↔ write-kotest.
4. **CHANGELOG** — add a `### Changed` / `### Fixed` entry under a **dated**
   SemVer heading (never `[Unreleased]`; see
   [changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md)) when
   user-visible docs change; do not fabricate past releases.
5. **Secrets** — never paste real API keys; templates keep placeholders.
6. **Environment agnostic** — no `/Users/...` paths or machine hostnames.

### Step 4: Agent docs coherence

After product docs are fixed:

1. Update `.agents/AGENTS.md` stack pins, architecture table, and skill index
   if a new skill was added or invariants changed.
2. Fix any skill that still teaches wrong APIs (especially
   `portfolio-rebalancing-math`, `kraken-api-integration`,
   `dry-run-and-simulation`, `gradle-quality-gates`).
3. Ensure skills/scripts lint `.agents/AGENTS.md`, not a root `AGENTS.md`.

### Step 5: Verify

```bash
npx markdownlint-cli .agents/AGENTS.md .agents/OPERATING.md CLAUDE.md .github/copilot-instructions.md CHANGELOG.md README.md CONTRIBUTING.md SECURITY.md docs/*.md .agents/skills/**/SKILL.md .agents/skills/**/*.md .cursor/rules/*.mdc .kilo/command/*.md .kilo/agent/**/*.md
```

When any Mermaid fence was added or changed (or as part of a full audit):

```bash
/tmp/kraken-screenshots/bin/python \
  .agents/skills/documentation-review/scripts/validate_mermaid.py
```

Spot-check:

- [ ] README tech stack versions match Gradle
- [ ] README directory tree matches packages (tables under `repository/table/`)
- [ ] ALGORITHM covers ATH/drawdown deploy, fiat correction, dust, sell→buy, 99% cap
- [ ] FLOWS matches ConfigService + TradeHistoryService + SSE route
- [ ] EVALUATION scenario and invariant-suite lists match
      `EvaluationScenariosTest` and `SimulationEvaluationScenariosTest` (or note
      an intentional subset)
- [ ] Template JSON keys ⊆ `Settings` / `AppConfig`
- [ ] AGENTS skill index links resolve to existing `SKILL.md` files
- [ ] `dryRun` vs `simulation` distinguished wherever both appear
- [ ] Stale README screenshots flagged; refresh via docs-screenshot-refresh when visuals drifted
- [ ] Every Mermaid block parses under Mermaid 8.x (`validate_mermaid.py` exit 0)

Do not declare complete until markdown lint is clean on touched files.

---

## Doc ↔ code map (quick reference)

| Topic | Code anchors | Doc anchors |
| :--- | :--- | :--- |
| Rebalance math | `PortfolioCalculations`, `PortfolioAnalyzerImpl`, `OrderExecutorImpl` | `docs/ALGORITHM.md` |
| Flows / SSE | `ConfigServiceImpl`, `TradeHistoryServiceImpl`, `DashboardController` | `docs/FLOWS.md` |
| Evaluation | `EvaluationScenariosTest` + `FakeKrakenService`; `SimulationEvaluationScenariosTest` + `SimulatedKrakenService` | `docs/EVALUATION.md` |
| Config flags | `Settings`, `DynamicKrakenService` | README, template, dry-run skill |
| Coverage | `backend/build.gradle.kts` JaCoCo, `karma.config.d/coverage.js` | README, AGENTS, gradle-quality-gates |
| Security | `KtorConfig.configureCORS`, `SECURITY.md` | SECURITY, AGENTS security section |

---

## Anti-patterns

- Updating docs from memory without opening the cited source file
- Claiming CodeQL is enabled without verifying the workflow's supported language,
  bundle, and branch triggers
- Collapsing `dryRun` and `simulation` into one flag
- Leaving README package trees with removed or renamed packages
- Expanding CHANGELOG with speculative unreleased features not in code
- Skipping `.agents` skills when product docs were wrong for the same fact
- Updating README screenshot captions while leaving stale `docs/images/*.png`
  (use docs-screenshot-refresh instead)
- Shipping Mermaid that only renders on GitHub (skipping `validate_mermaid.py`
  against 8.x / IDE preview)
- **Narrative bloat:** adding conversational fluff or tutorial walkthroughs to reference docs
- **Internal leak:** copying internal test fixtures or private developer paths into public guides
- **Duplicate truth:** copying full procedural text across multiple docs instead of linking to the canonical owner

---

## Completion checklist

- [ ] Code inventory completed (versions, packages, config, algorithm, flows, CI)
- [ ] Findings classified (wrong / stale / missing / orphan / skill drift)
- [ ] Product docs updated to match code
- [ ] AGENTS + skills coherent with the same facts
- [ ] CHANGELOG entry if user-visible doc fixes were applied
- [ ] `markdownlint-cli` clean on touched markdown
- [ ] Mermaid fences validated with `scripts/validate_mermaid.py` when diagrams touched
