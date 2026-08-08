# Agent Rules — Kraken Rebalancer

Primary agent rules live here: **`.agents/AGENTS.md`**.
Domain skills live under **`.agents/skills/*/SKILL.md`**. Prefer skills for deep how-to; keep this file as non-negotiable invariants and pointers.

**Skill precedence:** when a repository skill and a user-level, global, or other
non-project skill both match a task, the repository skill governs. Use the
external skill only to fill gaps that the project skill does not cover, and
never let it override repository instructions or invariants.

**Always-on operating norms** (all agent frameworks): **[OPERATING.md](OPERATING.md)**.
Cursor thin pointers live in **`.cursor/rules/*.mdc`** (committed;
each points at its OPERATING.md section). Cline thin pointers live in
**`.clinerules/`** (committed; each points at its OPERATING.md section). Thin harness entrypoints: root
**`CLAUDE.md`** (Claude Code), root **`AGENTS.md`** (universal; read by most
harnesses, Cline included), **`.github/copilot-instructions.md`** (GitHub Copilot).

Canonical deep docs:

- [`docs/AGENTIC_DEVELOPMENT.md`](../docs/AGENTIC_DEVELOPMENT.md) — human guide
  to the AI-assisted development system
- [`docs/USER_GUIDE.md`](../docs/USER_GUIDE.md) — end-user visual walkthrough
- [`docs/ALGORITHM.md`](../docs/ALGORITHM.md) — rebalancing math & execution
- [`docs/FLOWS.md`](../docs/FLOWS.md) — Kotlin Flow / SharedFlow / SSE architecture
- [`docs/EVALUATION.md`](../docs/EVALUATION.md) — scenario evaluation suite

---

## Skill index (task → skill)

| Task | Skill |
| :--- | :--- |
| Portfolio math, ATH/drawdown, orders | [portfolio-rebalancing-math](skills/portfolio-rebalancing-math/SKILL.md) |
| Kraken REST, signing, rate limits, `cl_ord_id` | [kraken-api-integration](skills/kraken-api-integration/SKILL.md) |
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
| Complex-code comments (audit / hygiene) | [complex-code-comments](skills/complex-code-comments/SKILL.md) |
| Resolve actionable source-code TODO comments | [todo-resolution](skills/todo-resolution/SKILL.md) |
| README screenshot PNGs (sim UI) | [docs-screenshot-refresh](skills/docs-screenshot-refresh/SKILL.md) |
| End-user User Guide (visual) | [user-guide](skills/user-guide/SKILL.md) |
| UI visual critique (recommend) | [ui-visual-review](skills/ui-visual-review/SKILL.md) |
| UI visual apply + verify | [ui-visual-implement](skills/ui-visual-implement/SKILL.md) |
| UI manual QA (click-through) | [ui-manual-qa](skills/ui-manual-qa/SKILL.md) |
| Post-deploy UI smoke | [post-deploy-ui-smoke](skills/post-deploy-ui-smoke/SKILL.md) |
| Refactor / cleanup | [kotlin-refactoring-and-cleanup](skills/kotlin-refactoring-and-cleanup/SKILL.md) |
| Reduce code size / split large files | [reduce-code-size](skills/reduce-code-size/SKILL.md) |
| Code review | [code-review](skills/code-review/SKILL.md) |
| Architecture review (third-party / redesign) | [architecture-review](skills/architecture-review/SKILL.md) |
| Product opportunity review / feature roadmap | [product-opportunity-review](skills/product-opportunity-review/SKILL.md) |
| Create or modify an approved project skill | [skill-authoring](skills/skill-authoring/SKILL.md) |
| Skill / agent-files review (skills, rules, AGENTS) | [skill-reviewer](skills/skill-reviewer/SKILL.md) |
| Rules / skills structural audit | [rules-and-skills-audit](skills/rules-and-skills-audit/SKILL.md) |
| Adversarial PR review (adaptive bounded multi-agent loop) | [adversarial-pr-review](skills/adversarial-pr-review/SKILL.md) |
| Dependency upgrades | [dependency-upgrade](skills/dependency-upgrade/SKILL.md) |
| Commit & push | [commit-and-push](skills/commit-and-push/SKILL.md) |
| Open PR | [open-pr](skills/open-pr/SKILL.md) |
| Update open PR (push to existing) | [commit-and-push](skills/commit-and-push/SKILL.md) → [adversarial-pr-review](skills/adversarial-pr-review/SKILL.md) |
| Evidence-based AI-slop audit / cleanup (all repo assets) | [ai-slop-detector](skills/ai-slop-detector/SKILL.md) |
| Autonomous multi-pass audit | [autonomous-code-optimizer](skills/autonomous-code-optimizer/SKILL.md) |
| Parallel multi-agent splits | [parallel-multi-agent](skills/parallel-multi-agent/SKILL.md) |
| Choose provider/model/effort or fallbacks | Use the host's native model selection; pair with [parallel-multi-agent](skills/parallel-multi-agent/SKILL.md) when fanning out |
| Native Antigravity subagent fan-out vs Kilo routed subagents | [AGENTIC_DEVELOPMENT.md](../docs/AGENTIC_DEVELOPMENT.md#models-and-adaptive-multi-agent-review) and [parallel-multi-agent](skills/parallel-multi-agent/SKILL.md) |
| Continuous improvement (whole shebang) | [continuous-improvement](skills/continuous-improvement/SKILL.md) *(writes `.agents/improvement-backlog.md`)* |
| Continuous quality (QA loop) | [continuous-quality](skills/continuous-quality/SKILL.md) *(writes `.agents/quality-backlog.md`)* |
| Full-repository parallel quality sweep (all skills) | [comprehensive-quality-overhaul](skills/comprehensive-quality-overhaul/SKILL.md) |

**Always-on norms** — full text in [OPERATING.md](OPERATING.md). Cursor and Cline
rule files (`.cursor/rules/*.mdc`, `.clinerules/*.md`) are thin pointers to its
sections; keep OPERATING.md canonical.

Do **not** gitignore `.cursor/`. Other frameworks should read OPERATING.md (or
the CLAUDE.md / Copilot stubs) so they get the same norms without Cursor.

---

## 1. Technology stack (verify against build files)

- **Language**: Kotlin **2.4.10** (KMP: JVM + JS)
- **JDK**: **25** (`java.toolchain`)
- **Backend**: Ktor **3.5.2** (Netty, Jackson, SSE, HTML), Koin **4.2.2**
- **Database**: SQLite via JetBrains Exposed **1.3.1**
- **Concurrency**: `kotlinx.coroutines` **1.11.0** — prefer `Dispatchers.IO` for DB/network; no `GlobalScope`
- **Frontend/codegen**: `kotlinx.html` + `kotlinx-css` + HTMX + Kotlin/JS (`:frontend-js` → `/static/rebalancer.js`); KSP **2.3.11** is required for Kotlin/JS Kotest discovery and the experimental JVM/common catalog processors
- **Testing**: Kotest **6.2.3**, MockK **1.14.11**, Karma/Istanbul
- **Formatting**: Spotless **8.9.0** + ktlint **1.7.1**, **120**-char line length; `allWarningsAsErrors` in all modules

### Architecture names (SRP)

| Role | Type |
| :--- | :--- |
| Entry / DI / lifecycle | `KrakenRebalancerApplication`, `AppModule` (`coreModule` + `webModule`) |
| Orchestrator | `PortfolioManagerImpl` |
| Brain (snapshot + analysis) | `PortfolioAnalyzerImpl` (REST + ATH I/O) |
| Domain rebalance math | `RebalancerEngine` (no network/DB) |
| Shared math | `PortfolioCalculations` |
| Brawn (execution) | `OrderExecutorImpl` (sell/buy sequencing + durable live submission journal) |
| Exchange gateway | `DynamicKrakenService` → `KrakenServiceImpl` or `SimulatedKrakenService` |
| Rate limit | `RateLimiter` (safeLimit **12**, decay **0.33**, `Mutex`) |
| History reconstruction | `SnapshotHistoryCalculator` (`service/impl/history/`) |
| Live history / SSE source | `TradeHistoryServiceImpl` façade → Sync / Ledger Sync (`LedgersSyncService`) / SnapshotStore / Query / Reconstruction |
| HTTP | `DashboardRoutes` / `DashboardController` |
| Views | `view/component/*`, `DashboardView`, `view/css/*` |
| Shared routes/IDs | `:common` `Routes`, `HtmlIds`, `CssClass`, `HtmlQueries`, `ViewText` and generated pure-string catalogs |

---

## 2. Algorithm critical rules

Full detail: [`docs/ALGORITHM.md`](../docs/ALGORITHM.md) and skill [portfolio-rebalancing-math](skills/portfolio-rebalancing-math/SKILL.md).

- **ATH → drawdown → fiat deployment**: `Deploy% = (DD / MaxDD)^exponent` (capped 100%); effective USD target reduced and redistributed to crypto. Math lives in `RebalancerEngine` (via `PortfolioAnalyzerImpl`).
- **Trigger**: absolute signed relative deviation ≥ `deviationTriggerPercent`
  **and** `|DeviationUSD| ≥ minimumOrderSizeUSD` (`isSignificant`).
- **Price safety**: missing/zero non-USD ticker aborts the cycle before orders.
- **Fiat correction**: if *only* USD triggers (deposit/withdrawal), redistribute among counter-balanced assets.
- **Dust**: also skips execution of orders below `minimumOrderSizeUSD`.
- **Sell then buy**: sell overweight first; after **≥1 successful sell** (and not
  dry-run), settle USD (fill-confirmed proceeds preferred; balance-poll
  fallback), fail-closed abort if unsettleable, then buys under a **99%** cycle
  cash budget. A real live AddOrder persists `PENDING` first, is attempted once,
  and becomes blocking `UNCERTAIN` after an ambiguous outcome; unresolved rows
  are never reconciled/pruned automatically. Live orders use deterministic
  `cl_ord_id`. Settle attempt
  counts / backoff and cold-Flow poll details:
  [portfolio-rebalancing-math](skills/portfolio-rebalancing-math/SKILL.md) +
  [coroutines-flows-sse](skills/coroutines-flows-sse/SKILL.md).
  Sell volumes are capped to cycle-entry holdings; repeated nonblank Kraken
  trade IDs across shifted settle pages count once.
- **Precision**: `BigDecimal` only — crypto scale **8**, USD scale **2**. Tests: `shouldBeEqualComparingTo` (never `shouldBeEqualByComparingTo` / `.equals()`).

---

## 3. dryRun vs simulation (DISTINCT)

See [dry-run-and-simulation](skills/dry-run-and-simulation/SKILL.md).

- **`simulation`**: `DynamicKrakenService` routes to `SimulatedKrakenService` (offline emulator).
- **`dryRun`**: suppresses real order placement inside the active backend
  (server logs `[DRY RUN]` / `[EMULATOR DRY RUN]`; activity log always
  `[DRY RUN]`).
- The shipped template and README default `dryRun` to `true`; `Settings.dryRun`
  has no Kotlin default and must be supplied. `simulation` defaults to `false`.
  **Never** flip `dryRun = false` casually in examples/tests aimed at live
  paths. Live trading moves real money — treat credential + live mode changes
  as high risk.
- `ConfigServiceImpl` defers runtime publication of saved/reloaded config while
  a rebalance or paginated history-sync execution session is active. Do not
  remove that boundary or let one cycle/sync mix settings or credentials.

---

## 4. `:common` ownership & purity

See [common-kmp-module](skills/common-kmp-module/SKILL.md).

Belongs in `common/src/commonMain/`: `CssClass`, `HtmlIds`, `HtmlAttrs`, `HtmxAttrs`, `ViewText`, `Routes`, `TimeRange`, `OrderSide` / `OrderType`, `PrecisionConstants`, `AppConfig` / `Settings` / `Allocation`, and wire DTOs under `api/` (`PortfolioSnapshot`, `TradeRecord`, `RebalancerComparison`, `HistoryStats`, `SyncProgressResponse`).

`commonMain` must stay **pure KMP** — no JVM-only (`java.math.BigDecimal`, SLF4J) or JS-only DOM imports.

Large pure string catalogs may use the explicit YAML/KSP resources under
`common/src/commonMain/resources/codegen/`; the JVM-only `codegen` module is a
build-time processor, not a `:common` compile dependency. Keep generated output
KMP-compatible and leave mixed semantic catalogs explicit.

---

## 5. Quality gates

See [gradle-quality-gates](skills/gradle-quality-gates/SKILL.md).

| Gate | Thresholds |
| :--- | :--- |
| JVM JaCoCo | Line / method / instruction **95%**; branch **90%** |
| JS Istanbul (Karma) | Statements / functions / lines **90%**; branches **75%** |

Before declaring work done, run the verify commands in
[gradle-quality-gates](skills/gradle-quality-gates/SKILL.md) (build + JaCoCo,
frontend browser tests, Spotless, markdownlint including `.agents/OPERATING.md`
and harness stubs).

The repository quality scripts set `NODE_OPTIONS` only for the external
`DEP0169` warning emitted by the Kotlin 2.4.10 downloaded Yarn 1.22.22 resolver
under modern Node. This does not change application launches or suppress other
Node diagnostics.

**CodeQL**: enabled for Java/Kotlin analysis on `main` pushes and pull requests
with CodeQL Action **v4.37.4**. The workflow uses the `java-kotlin` language,
`manual` build mode, and JDK **25**; keep its SHA pin and build steps aligned
with the workflow.

---

## 6. Security & local-trust dashboard

- **No user auth** on the dashboard/API — the security model is a single trusted
  operator on a local machine or private network. Do not present it as safe for
  unrestricted public hosting.
- CORS must continue to use structurally parsed origins through
  `isLocalOrPrivateOrigin`: localhost, IPv4 and IPv6 loopback, RFC1918 IPv4,
  IPv4 link-local `169.254.0.0/16`, and `*.local`. Reject paths, user-info
  tricks, public origins, malformed IPs, and private-prefix hostname lookalikes.
  Do not widen CORS as a substitute for authentication.
- Never hardcode or commit API keys, private keys, real account data, or the
  gitignored `rebalancer-config.json`. Committed tests, examples, documentation,
  screenshots, and issue/PR artifacts use placeholders or redacted values.
- Preserve raw `${ENV_VAR}` / `${ENV_VAR:default}` credential placeholders when
  unrelated settings are saved. Do not materialize resolved secrets into JSON.
- Never log HMAC signatures, API keys, private keys, or resolved credentials.
  Prefer minimal structured fields to raw private Kraken responses. Runtime logs
  can contain order identifiers, balance amounts, and asset keys; treat them as
  sensitive and redact them before sharing.
- Kraken credentials for normal application operation use least privilege:
  Query Funds, Query Closed Orders & Trades, Query Ledgers, and Create & Modify
  Orders. Do not claim Query Open Orders is required for normal operation unless
  the API surface changes and the claim is verified.
- Preserve the durable live-order journal: write `PENDING` before AddOrder;
  ambiguous outcomes become blocking `UNCERTAIN`; never retry or heuristically
  clear them. Operator reconciliation requires a database backup and follows
  local `client_order_id` → Kraken `cl_ord_id` on an open/closed order → Kraken
  order txid → TradesHistory fills. REST-based OpenOrders verification requires
  Query Open Orders & Trades in addition to the normal application permissions.
- Treat changes to authentication, network exposure, CORS, credentials,
  persistence, or live-order safety as high impact and require explicit user
  direction plus focused security tests.

Public reporting and operator guidance belongs in [`SECURITY.md`](../SECURITY.md);
development-facing security invariants remain canonical here and in the owning
domain skills.

---

## 7. Code quality invariants

- **No FQNs** unless resolving a name collision — use imports.
- **No ARIA attributes**: Do not introduce `aria-*` attributes (`aria-hidden`, `aria-sort`, `aria-label`, etc.) into HTML DSL templates, `:common` attribute catalogs, or client Kotlin/JS. Rely on standard semantic HTML elements.
- **No absolute user paths** or machine-specific hostnames in source/tests.
- Markdown: lint per [gradle-quality-gates](skills/gradle-quality-gates/SKILL.md)
  (`.agents/AGENTS.md`, `OPERATING.md`, skills, product docs, harness stubs).
- Offload blocking IO with `withContext(Dispatchers.IO)`.
- Guards and validation live at trust boundaries (external API, user input,
  config, persistence, money); no dead guards or duplicated validation inside
  them — rubric: [ai-slop-detector](skills/ai-slop-detector/SKILL.md).
- GitHub auth via `gh` CLI (`gh auth setup-git`); do not ask the user to authenticate manually.
- Keep a Changelog; sync README tree + JaCoCo exclusions when packages move — see [changelog-and-docs-sync](skills/changelog-and-docs-sync/SKILL.md).

---

## 8. Testing invariants

See [write-kotest](skills/write-kotest/SKILL.md).

- Kotest `StringSpec` + `init { }`; prefer `IsolationMode.InstancePerTest`.
  Add suppressions only for demonstrated warnings, not by default.
- In-memory SQLite only (`:memory:`).
- Prefer `FakeKrakenService` for deterministic tests; `SimulatedKrakenService` is the production emulator (not the same).
- Evaluation/E2E/chaos: `EvaluationScenariosTest` covers deterministic
  `FakeKrakenService` scenarios, while `SimulationEvaluationScenariosTest`
  covers production `SimulatedKrakenService` invariants; both are documented in
  `docs/EVALUATION.md`. Flow tests use `advanceUntilIdle()`.
- Each test kills a distinct defect class; no impossible-case, cosmetic-duplicate, or coverage-padding tests (see [ai-slop-detector](skills/ai-slop-detector/SKILL.md) § Test necessity).

---

## 9. Cursor Cloud environment

Cloud VM deltas only. Canonical setup: [README Getting Started](../README.md#getting-started);
flags: [dry-run-and-simulation](skills/dry-run-and-simulation/SKILL.md).

- **JDK:** Temurin 25 is the default `java`; no `JAVA_HOME` is required for
  `./gradlew`. Matches §1 toolchain (ignore JDK 21 if present).
- **Run (sim):** `cp rebalancer-config-template.json rebalancer-config.json`, set
  `"simulation": true`, `./gradlew run` (background per [OPERATING.md](OPERATING.md) §4 —
  poll `/api/health` until 200; first boot may block on seeding) →
  <http://localhost:8080>. UI QA / screenshots: prefer isolated `RUN_DIR` + `fatJar` in
  [ui-manual-qa](skills/ui-manual-qa/SKILL.md) / [docs-screenshot-refresh](skills/docs-screenshot-refresh/SKILL.md).
- **Build/test:** [§5 Quality gates](#5-quality-gates) and [README Testing](../README.md#testing).
  `./gradlew build` covers Gradle gates (Spotless, JVM tests, JaCoCo, Karma); still run
  `npx markdownlint-cli` when editing docs.
- **Hot-reload:** Settings UI saves restart the rebalance loop only
  ([koin-di-and-config](skills/koin-di-and-config/SKILL.md),
  [coroutines-flows-sse](skills/coroutines-flows-sse/SKILL.md)); manual
  `rebalancer-config.json` edits on disk require restart. **Kotlin / SSR / frontend
  changes require `./gradlew run` restart** (Ktor Autoreload off).
- **Settings input:** Every numeric trading field and allocation row is parsed
  strictly before persistence; missing, non-finite, malformed, or mismatched
  form values are rejected instead of defaulted or truncated.

---

## 10. Kilo Agent Manager integration

The following files are optional, Kilo-specific Agent Manager integrations under
`.kilo/`. They are not application requirements or the canonical project
workflow. The repository remains harness agnostic: other agent tools should use
the standard Gradle/README workflows and the shared guidance in `.agents/`.

- **Scope:** these hooks are for Kilo Code's Agent Manager only. They do not
  define or require a general agent protocol.

- **Setup:** `.kilo/setup-script` prepares Gradle classes in the selected
  worktree without reading `.env`, application config, databases, logs, or
  runtime data.
- **Run:** `.kilo/run-script` builds the fat JAR and starts an isolated local
  simulation for the Agent Manager Run button. It copies only
  `rebalancer-config-template.json` into a private temporary directory, forces
  both `simulation=true` and `dryRun=true`, and uses a temporary database.
- **Port:** the application defaults to `8080` and accepts the JVM property
  `kraken.server.port`. The run hook probes candidates in the
  `18080`-`19079` range and skips occupied ports. Set `KILO_AGENT_PORT` to use
  an explicit valid, unused port.
- **Health check:** the run hook polls only the local `/api/health` endpoint,
  suppresses build and application output, emits only a generic readiness
  message or failure, and forcefully terminates and reaps only its own child
  process during cleanup. It removes only its own temporary directory.

Agent Manager automatically copies root `.env` and `.env.*` files into managed
worktrees. Do not use those files for credentials in this workflow; keep them
placeholder-only or use a separate operator-managed secret mechanism. Never
commit `rebalancer-config.json`, API credentials, private keys, account data,
or runtime logs.

Bring worktree changes back with Agent Manager Apply, a normal merge, or a PR.
Do not use shared `git stash` or autostash across worktrees.
