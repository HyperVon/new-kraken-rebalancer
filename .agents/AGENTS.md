# Agent Rules — Kraken Rebalancer

Primary agent rules live here: **`.agents/AGENTS.md`** (there is no root `AGENTS.md`).
Domain skills live under **`.agents/skills/*/SKILL.md`**. Prefer skills for deep how-to; keep this file as non-negotiable invariants and pointers.

**Always-on operating norms** (all agent frameworks): **[OPERATING.md](OPERATING.md)**.
Cursor projections of those norms live in **`.cursor/rules/*.mdc`** (committed;
keep in sync with OPERATING.md). Thin harness entrypoints: root **`CLAUDE.md`**,
**`.github/copilot-instructions.md`**.

Canonical deep docs:

- [`docs/USER_GUIDE.md`](../docs/USER_GUIDE.md) — end-user visual walkthrough
- [`docs/ALGORITHM.md`](../docs/ALGORITHM.md) — rebalancing math & execution
- [`docs/FLOWS.md`](../docs/FLOWS.md) — Kotlin Flow / SharedFlow / SSE architecture
- [`docs/EVALUATION.md`](../docs/EVALUATION.md) — scenario evaluation suite

---

## Skill index (task → skill)

| Task | Skill |
| :--- | :--- |
| Portfolio math, ATH/drawdown, orders | [portfolio-rebalancing-math](skills/portfolio-rebalancing-math/SKILL.md) |
| Kraken REST, signing, rate limits | [kraken-api-integration](skills/kraken-api-integration/SKILL.md) |
| dryRun vs simulation flags | [dry-run-and-simulation](skills/dry-run-and-simulation/SKILL.md) |
| Koin DI & `rebalancer-config.json` | [koin-di-and-config](skills/koin-di-and-config/SKILL.md) |
| `:common` KMP shared module | [common-kmp-module](skills/common-kmp-module/SKILL.md) |
| Coroutines, Flows, SSE | [coroutines-flows-sse](skills/coroutines-flows-sse/SKILL.md) |
| Trade history sync & dedupe | [trade-history-sync](skills/trade-history-sync/SKILL.md) |
| Exposed ORM repositories | [exposed-repository](skills/exposed-repository/SKILL.md) |
| Ktor HTML / CSS / HTMX views | [ktor-html-views](skills/ktor-html-views/SKILL.md) |
| Kotlin/JS client (`:frontend-js`) | [frontend-js-development](skills/frontend-js-development/SKILL.md) |
| Kotest / FakeKraken / evaluation | [write-kotest](skills/write-kotest/SKILL.md) |
| Spotless, JaCoCo, Karma, CI | [gradle-quality-gates](skills/gradle-quality-gates/SKILL.md) |
| CHANGELOG / README / docs sync | [changelog-and-docs-sync](skills/changelog-and-docs-sync/SKILL.md) |
| Full docs audit vs source code | [documentation-review](skills/documentation-review/SKILL.md) |
| README screenshot PNGs (sim UI) | [docs-screenshot-refresh](skills/docs-screenshot-refresh/SKILL.md) |
| End-user User Guide (visual) | [user-guide](skills/user-guide/SKILL.md) |
| UI visual critique (recommend) | [ui-visual-review](skills/ui-visual-review/SKILL.md) |
| UI visual apply + verify | [ui-visual-implement](skills/ui-visual-implement/SKILL.md) |
| UI manual QA (click-through) | [ui-manual-qa](skills/ui-manual-qa/SKILL.md) |
| Post-deploy UI smoke | [post-deploy-ui-smoke](skills/post-deploy-ui-smoke/SKILL.md) |
| Refactor / cleanup | [kotlin-refactoring-and-cleanup](skills/kotlin-refactoring-and-cleanup/SKILL.md) |
| Code review | [code-review](skills/code-review/SKILL.md) |
| Dependency upgrades | [dependency-upgrade](skills/dependency-upgrade/SKILL.md) |
| Commit & push | [commit-and-push](skills/commit-and-push/SKILL.md) |
| Open PR | [open-pr](skills/open-pr/SKILL.md) |
| Autonomous multi-pass audit | [autonomous-code-optimizer](skills/autonomous-code-optimizer/SKILL.md) |
| Parallel multi-agent splits | [parallel-multi-agent](skills/parallel-multi-agent/SKILL.md) |
| Continuous improvement (whole shebang) | [continuous-improvement](skills/continuous-improvement/SKILL.md) |
| Continuous improvement backlog | [improvement-backlog.md](improvement-backlog.md) |
| Continuous quality (QA loop) | [continuous-quality](skills/continuous-quality/SKILL.md) |
| Continuous quality backlog | [quality-backlog.md](quality-backlog.md) |

**Always-on norms** — full text in [OPERATING.md](OPERATING.md). Cursor loads the
same content via committed `.cursor/rules/`:

| Rule | Purpose |
| :--- | :--- |
| `prefer-project-skills.mdc` | Follow `.agents/skills` instead of inventing flows |
| `parallel-multi-agent.mdc` | Fan out independent workstreams; keep coupled files single-threaded |
| `no-blocking-long-processes.mdc` | Background servers; don’t hang on `java -jar` / `gradlew run` |
| `ui-change-verification.mdc` | Path-triggered: laptop viewport, CSS `?v=`, QA smells (`view/**`, `frontend-js/**`) |

Do **not** gitignore `.cursor/`. Other frameworks should read OPERATING.md (or
the CLAUDE.md / Copilot stubs) so they get the same norms without Cursor.

---

## 1. Technology stack (verify against build files)

- **Language**: Kotlin **2.4.10** (KMP: JVM + JS)
- **JDK**: **25** (`java.toolchain`)
- **Backend**: Ktor **3.5.1** (Netty, Jackson, SSE, HTML), Koin **4.2.2**
- **Database**: SQLite via JetBrains Exposed **1.3.1**
- **Concurrency**: `kotlinx.coroutines` **1.11.0** — prefer `Dispatchers.IO` for DB/network; no `GlobalScope`
- **Frontend**: `kotlinx.html` + `kotlinx-css` + HTMX + Kotlin/JS (`:frontend-js` → `/static/rebalancer.js`)
- **Testing**: Kotest **6.2**, MockK **1.14**, Karma/Istanbul
- **Formatting**: Spotless + ktlint **1.7.1**, **120**-char line length; `allWarningsAsErrors` in all modules

### Architecture names (SRP)

| Role | Type |
| :--- | :--- |
| Entry / DI / lifecycle | `KrakenRebalancerApplication`, `AppModule` |
| Orchestrator | `PortfolioManagerImpl` |
| Brain (snapshot + analysis) | `PortfolioAnalyzerImpl` |
| Shared math | `PortfolioCalculations` |
| Brawn (execution) | `OrderExecutorImpl` |
| Exchange gateway | `DynamicKrakenService` → `KrakenServiceImpl` or `SimulatedKrakenService` |
| Rate limit | `RateLimiter` (safeLimit **12**, decay **0.33**, `Mutex`) |
| History reconstruction | `SnapshotHistoryCalculator` |
| Live history / SSE source | `TradeHistoryServiceImpl` |
| HTTP | `DashboardRoutes` / `DashboardController` |
| Views | `view/component/*`, `DashboardView`, `view/css/*` |
| Shared routes/IDs | `:common` `Routes`, `HtmlIds`, `CssClass`, `ViewText` |

---

## 2. Algorithm critical rules

Full detail: [`docs/ALGORITHM.md`](../docs/ALGORITHM.md) and skill [portfolio-rebalancing-math](skills/portfolio-rebalancing-math/SKILL.md).

- **ATH → drawdown → fiat deployment**: `Deploy% = (DD / MaxDD)^exponent` (capped 100%); effective USD target reduced and redistributed to crypto.
- **Trigger**: absolute signed relative deviation ≥ `deviationTriggerPercent`
  **and** `|DeviationUSD| ≥ dustThresholdUSD` (`isSignificant`).
- **Price safety**: missing/zero non-USD ticker aborts the cycle before orders.
- **Fiat correction**: if *only* USD triggers (deposit/withdrawal), redistribute among counter-balanced assets.
- **Dust**: also skips execution of orders below `dustThresholdUSD`.
- **Sell then buy**: sell overweight first; poll USD up to **3** attempts with
  exponential backoff starting at **250ms** (doubling: 250ms → 500ms → 1000ms);
  use the **best positive** observation; accept early at **≥95%** of projected;
  **abort buys** if no positive USD is observed; cycle buy budget **99%** of
  settled USD (`withStableBackend` pins live vs simulation for the sequence).
- **Precision**: `BigDecimal` only — crypto scale **8**, USD scale **2**. Tests: `shouldBeEqualComparingTo` (never `shouldBeEqualByComparingTo` / `.equals()`).

---

## 3. dryRun vs simulation (DISTINCT)

See [dry-run-and-simulation](skills/dry-run-and-simulation/SKILL.md).

- **`simulation`**: `DynamicKrakenService` routes to `SimulatedKrakenService` (offline emulator).
- **`dryRun`**: suppresses real order placement inside the active backend (`[DRY RUN]` / `[EMULATOR DRY RUN]`).
- The shipped template and README default `dryRun` to `true`; `Settings.dryRun`
  has no Kotlin default and must be supplied. `simulation` defaults to `false`.
  **Never** flip `dryRun = false` casually in examples/tests aimed at live
  paths. Live trading moves real money — treat credential + live mode changes
  as high risk.

---

## 4. `:common` ownership & purity

See [common-kmp-module](skills/common-kmp-module/SKILL.md).

Belongs in `common/src/commonMain/`: `CssClass`, `HtmlIds`, `HtmlAttrs`, `HtmxAttrs`, `ViewText`, `Routes`, `TimeRange`, `OrderSide` / `OrderType`, `PrecisionConstants`, `AppConfig` / `Settings` / `Allocation`.

`commonMain` must stay **pure KMP** — no JVM-only (`java.math.BigDecimal`, SLF4J) or JS-only DOM imports.

---

## 5. Quality gates

See [gradle-quality-gates](skills/gradle-quality-gates/SKILL.md).

| Gate | Thresholds |
| :--- | :--- |
| JVM JaCoCo | Line / method / instruction **95%**; branch **90%** |
| JS Istanbul (Karma) | Statements / functions / lines **90%**; branches **75%** |

Mandatory verify before declaring work done:

```bash
./gradlew build jacocoTestCoverageVerification
./gradlew :frontend-js:jsTest
npx markdownlint-cli .agents/AGENTS.md CHANGELOG.md CONTRIBUTING.md README.md SECURITY.md docs/*.md .agents/skills/**/SKILL.md
./gradlew spotlessCheck
```

**CodeQL**: currently **disabled** (Kotlin 2.4.x unsupported) — workflow triggers on a non-`main` branch. Do not claim CodeQL is active CI until re-enabled.

---

## 6. Security & local-trust dashboard

- **No user auth** on the dashboard/API — security model is local/private network trust.
- CORS allows only origins passing `isLocalOrPrivateOrigin` (`localhost`, private RFC1918, `*.local`, etc.).
- **NEVER** hardcode API keys/secrets. Load from env or gitignored `rebalancer-config.json`.
- Do not log HMAC signatures or private keys.

---

## 7. Code quality invariants

- **No FQNs** unless resolving a name collision — use imports.
- **No absolute user paths** or machine-specific hostnames in source/tests.
- Markdown: lint `.agents/AGENTS.md`, skills, `README.md`, `CHANGELOG.md`,
  `CONTRIBUTING.md` / `SECURITY.md` when present, and `docs/*.md` — clean
  heading hierarchy and blank lines around lists.
- Offload blocking IO with `withContext(Dispatchers.IO)`.
- GitHub auth via `gh` CLI (`gh auth setup-git`); do not ask the user to authenticate manually.
- Keep a Changelog; sync README tree + JaCoCo exclusions when packages move — see [changelog-and-docs-sync](skills/changelog-and-docs-sync/SKILL.md).

---

## 8. Testing invariants

See [write-kotest](skills/write-kotest/SKILL.md).

- Kotest `StringSpec` + `init { }`; prefer `IsolationMode.InstancePerTest`.
  Add suppressions only for demonstrated warnings, not by default.
- In-memory SQLite only (`:memory:`).
- Prefer `FakeKrakenService` for deterministic tests; `SimulatedKrakenService` is the production emulator (not the same).
- Evaluation/E2E/chaos: `EvaluationScenariosTest`, `docs/EVALUATION.md`; Flow tests use `advanceUntilIdle()`.
