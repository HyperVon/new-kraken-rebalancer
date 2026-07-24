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

---

## Scope (must review)

| Document | Role |
| :--- | :--- |
| `README.md` | Product overview, stack versions, setup, package tree, screenshots |
| `CHANGELOG.md` | Keep a Changelog — note gaps only; do not rewrite history |
| `CONTRIBUTING.md` | Dev setup, PR expectations, coding guidelines |
| `SECURITY.md` | Vulnerability reporting, deploy/security guidance |
| `CODE_OF_CONDUCT.md` | Only if links/contacts are broken |
| `docs/ALGORITHM.md` | Rebalancing math & execution |
| `docs/FLOWS.md` | Flow / SharedFlow / SSE architecture |
| `docs/EVALUATION.md` | Scenario evaluation suite |
| `.agents/AGENTS.md` | Invariants, skill index, stack pins |
| `.agents/skills/*/SKILL.md` | Domain guidance must match code |
| `rebalancer-config-template.json` | Settings keys vs `Settings` / `AppConfig` |
| `.github/workflows/*` | CI commands/JDK must match CONTRIBUTING/README |

Do **not** invent new marketing docs. Prefer correcting existing files.

---

## Workflow

Copy this checklist and track progress:

```text
- [ ] Step 0: Inventory code truth
- [ ] Step 1: Audit each doc (missing / wrong / stale)
- [ ] Step 2: Produce findings report
- [ ] Step 3: Apply doc fixes
- [ ] Step 4: Sync agent skills & AGENTS index if needed
- [ ] Step 5: Lint & verify
```

### Step 0: Inventory code truth

Gather facts from code/build (do not trust docs yet):

1. **Versions** — `build.gradle.kts`, `common/build.gradle.kts`,
   `frontend-js/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`,
   JDK toolchain, Spotless/ktlint.
2. **Architecture** — packages under `src/main/kotlin/com/gemini/krakenbot/`,
   `:common`, `:frontend-js`; key types (`PortfolioManagerImpl`,
   `PortfolioAnalyzerImpl`, `PortfolioCalculations`, `OrderExecutorImpl`,
   `DynamicKrakenService`, `RateLimiter`, `TradeHistoryServiceImpl`,
   `DashboardRoutes` / `DashboardController`, view components).
3. **Config surface** — `Settings`, `AppConfig`, `Allocation`,
   `rebalancer-config-template.json` keys (`dryRun`, `simulation`,
   `fiatMaxDrawdown`, `dustThresholdUSD`, etc.).
4. **HTTP / UI** — `Routes` / `FormFields` in `:common`; SSE path; History
   summary cards contract.
5. **Algorithm** — `PortfolioCalculations`, `PortfolioAnalyzerImpl`,
   `OrderExecutorImpl` vs `docs/ALGORITHM.md`.
6. **Flows** — config `SharedFlow`, snapshot `SharedFlow`, cold poll/sync
   flows vs `docs/FLOWS.md`.
7. **Tests / evaluation** — `EvaluationScenariosTest` scenario count/names vs
   `docs/EVALUATION.md`; coverage gates in JaCoCo +
   `frontend-js/karma.config.d/coverage.js`.
8. **CI** — `.github/workflows/*` (note CodeQL disabled if still true).
9. **Security model** — no dashboard auth; CORS via `isLocalOrPrivateOrigin`.

Use `rg`, package listings, and targeted file reads. Prefer evidence over memory.

### Step 1: Audit categories

For every in-scope doc, classify findings:

| Category | Meaning | Action |
| :--- | :--- | :--- |
| **Wrong** | Contradicts code/build | Correct to match code |
| **Stale** | Was true, now outdated (versions, paths, class names) | Update |
| **Missing** | Important behavior exists in code but undocumented | Add concise coverage |
| **Orphan** | Doc describes removed APIs/packages/flags | Remove or rewrite |
| **Skill drift** | `.agents` skill/AGENTS contradicts code | Fix skill or AGENTS |

High-risk mismatch examples:

- Stack versions in README/AGENTS ≠ Gradle
- Package tree in README ≠ `src/main/kotlin` / `repository/table/`
- `dryRun` confused with `simulation`
- Rate limiting described as “Mutex only” without call-counter `RateLimiter`
- Algorithm missing fiat deployment / fiat correction / dust / 99% buy / 95% settle
- Coverage stated as vague “75%+ JS” instead of Karma 90/90/90/75
- Lint paths pointing at root `AGENTS.md` (file is `.agents/AGENTS.md`)
- Evaluation scenario table out of sync with `EvaluationScenariosTest`
- Config template missing keys present on `Settings`

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
4. **CHANGELOG** — add a `### Changed` / `### Fixed` entry under the next
   version when user-visible docs change; do not fabricate past releases.
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
npx markdownlint-cli .agents/AGENTS.md CHANGELOG.md README.md CONTRIBUTING.md SECURITY.md docs/*.md .agents/skills/**/SKILL.md
```

Spot-check:

- [ ] README tech stack versions match Gradle
- [ ] README directory tree matches packages (tables under `repository/table/`)
- [ ] ALGORITHM covers ATH/drawdown deploy, fiat correction, dust, sell→buy, 99% cap
- [ ] FLOWS matches ConfigService + TradeHistoryService + SSE route
- [ ] EVALUATION scenario list matches test class (or notes intentional subset)
- [ ] Template JSON keys ⊆ `Settings` / `AppConfig`
- [ ] AGENTS skill index links resolve to existing `SKILL.md` files
- [ ] `dryRun` vs `simulation` distinguished wherever both appear

Do not declare complete until markdown lint is clean on touched files.

---

## Doc ↔ code map (quick reference)

| Topic | Code anchors | Doc anchors |
| :--- | :--- | :--- |
| Rebalance math | `PortfolioCalculations`, `PortfolioAnalyzerImpl`, `OrderExecutorImpl` | `docs/ALGORITHM.md` |
| Flows / SSE | `ConfigServiceImpl`, `TradeHistoryServiceImpl`, `DashboardRoutes` | `docs/FLOWS.md` |
| Evaluation | `EvaluationScenariosTest`, `FakeKrakenService` | `docs/EVALUATION.md` |
| Config flags | `Settings`, `DynamicKrakenService` | README, template, dry-run skill |
| Coverage | `build.gradle.kts` JaCoCo, `karma.config.d/coverage.js` | README, AGENTS, gradle-quality-gates |
| Security | `KtorConfig.configureCORS`, `SECURITY.md` | SECURITY, AGENTS security section |

---

## Anti-patterns

- Updating docs from memory without opening the cited source file
- “Fixing” CodeQL/CI docs to claim enabled when workflow is disabled
- Collapsing `dryRun` and `simulation` into one flag
- Leaving README package trees with removed or renamed packages
- Expanding CHANGELOG with speculative unreleased features not in code
- Skipping `.agents` skills when product docs were wrong for the same fact

---

## Completion checklist

- [ ] Code inventory completed (versions, packages, config, algorithm, flows, CI)
- [ ] Findings classified (wrong / stale / missing / orphan / skill drift)
- [ ] Product docs updated to match code
- [ ] AGENTS + skills coherent with the same facts
- [ ] CHANGELOG entry if user-visible doc fixes were applied
- [ ] `markdownlint-cli` clean on touched markdown
