# Architecture Patterns & Standards (autonomous-code-optimizer)

Reference for [SKILL.md](SKILL.md) Pass 1 and Pass 3. These are the
**load-bearing patterns and coding standards** the optimizer must preserve and
raise — not a redesign menu. For clean-sheet redesign brainstorming use
[architecture-review](../architecture-review/SKILL.md) instead.

Before merging or deleting a type, name which pattern it implements.

---

## Architecture patterns in this repo (preserve under optimization)

### 1. Ports & adapters (hexagonal-lite)

- **Ports:** interfaces in `service/` and `repository/`
  (`KrakenService`, `PortfolioManager`, `OrderExecutor`, `TradeRepository`, …).
- **Adapters:** `service/impl/*`, `repository/impl/*`, Ktor
  `controller` / `view`.
- **Rule:** Domain orchestration depends on **ports**, not concrete adapters.
  Optimizing "away" an interface that has a Fake/Mock in tests is a regression
  unless the fake moves with it.

### 2. Strategy + coroutine-context pin (`DynamicKrakenService`)

- `simulation` selects **which** adapter (`KrakenServiceImpl` vs
  `SimulatedKrakenService`); `dryRun` is enforced **inside** the adapter's
  `executeOrder`, not in the router.
- `withStableBackend` pins the adapter on `CoroutineContext` for the whole
  cycle/sync so a mid-cycle settings flip cannot split sell/buy across backends.
- **Rule:** Never "simplify" by resolving the backend on every call inside a
  money path. Nested pins must reuse the outer pin (already implemented — do
  not break).

### 3. Façade (`TradeHistoryServiceImpl`)

- Public history API is a thin façade over Sync / SnapshotStore / Query /
  (Reconstruction via Sync).
- Controllers and `PortfolioManager` talk to the **façade port**, not the
  collaborators.
- **Rule:** Do not re-inline sync/query into `DashboardController` or merge
  SnapshotStore + Sync into one class during cleanup. Add methods on the
  façade; keep collaborator SRP.

### 4. Pure-ish domain calculator (`RebalancerEngine` + `PortfolioCalculations`)

- Rebalance math has **no** network, SQLite, Koin, or Ktor.
- Logging is allowed for cycle diagnostics; `:common` `ViewText` (and other
  pure shared strings) may appear in failure messages — still treat as
  calculation logic, not a view adapter.
- `PortfolioAnalyzerImpl` owns I/O (balances, prices, ATH) and **delegates**
  math to the engine.
- **Rule:** New trigger/ATH/dust logic goes in the engine/calculations — not in
  the executor or a view. Price-safety abort (`Result.Failure`) stays in the
  calculator path before orders.

### 5. Orchestrator / brain / brawn SRP

| Role | Type | Pattern job |
| :--- | :--- | :--- |
| Orchestrator | `PortfolioManagerImpl` | Loop, config `collectLatest`, cycleId/MDC, pin, wire analyzer → executor → snapshot |
| Brain | `PortfolioAnalyzerImpl` | Snapshot inputs + analysis; no `executeOrder` |
| Brawn | `OrderExecutorImpl` | Place/settle only; no ATH/target % math |

### 6. SSR component composition (not a SPA)

- `view/component/*` render DTOs + `Settings`; `DashboardView` composes pages;
  `DashboardController` maps HTTP ↔ services ↔ HTML/JSON.
- HTMX + SSE shell owns live updates; `:frontend-js` owns charts/DOM after swap.
- **Rule:** Do not introduce client-side routing or a second SSE stack while
  "optimizing."

### 7. Hot vs cold Flow ownership

- Hot `SharedFlow`: config watch, snapshot broadcast (UI).
- Cold `Flow`: paginated sync, USD settle poll (execution — never broadcast).
- **Rule:** Promoting a cold poll to a hot SharedFlow "for reuse" is an
  architecture bug. See [docs/FLOWS.md](../../../docs/FLOWS.md).

### 8. Explicit success/failure types

- Domain analysis: `com.gemini.krakenbot.model.Result` (KMP; not
  `kotlin.Result`).
- Orders: `OrderResult` sealed interface.
- **Rule:** Prefer these over nullable-or-throw control flow on money/analysis
  paths. Do not replace with generic `Either` libraries.

---

## Layer dependency rules (Pass 3 scan)

Allowed directions (→ = "may depend on"):

```text
controller  →  service ports, view, :common (Routes/DTOs), api mappers
view/*      →  :common view utils, wire DTOs/models for display, Settings;
               display-only helpers from PortfolioCalculations (percent bars)
               — not KrakenService / OrderExecutor / repositories
service/impl → service ports, repository ports, util, :common models/config
RebalancerEngine / PortfolioCalculations → service domain types (AnalysisResult,
  AssetPrices, …), sibling calculations, :common config/models/util/ViewText,
  logging — no repository, no KrakenService, no Ktor, no Koin
repository/impl → Exposed, util (safeTransaction*), models
:common     →  nothing JVM/JS-specific
frontend-js →  DOM/Chart.js + :common Ids/text (no JVM services)
```

**Fail Pass 3 if you find:**

- `view/**` importing `KrakenService`, `OrderExecutor`, or writing repositories
- `RebalancerEngine` / `PortfolioCalculations` importing `repository` or
  `KrakenService`
- `controller` calling `KrakenServiceImpl` / `SimulatedKrakenService` concrete
  types
- New business rules inside `SettingsFormComponent` / chart components beyond
  display formatting (must not place orders or change targets)

Quick ripgrep (from repository root; treat hits as defects unless justified):

```bash
rg 'KrakenService|OrderExecutor|TradeRepository' src/main/kotlin/com/gemini/krakenbot/view --glob '*.kt'
rg 'repository\.|KrakenService' src/main/kotlin/com/gemini/krakenbot/service/impl/RebalancerEngine.kt \
  src/main/kotlin/com/gemini/krakenbot/service/impl/PortfolioCalculations.kt
rg 'KrakenServiceImpl|SimulatedKrakenService' src/main/kotlin/com/gemini/krakenbot/controller --glob '*.kt'
```

---

## Refactor decision rules (Pass 3)

### Extract when

- A function mixes **I/O + pure math** → split so math is unit-testable without
  fakes (engine/calculations pattern).
- A type has **two reasons to change** (e.g. sync pagination vs snapshot
  broadcast) → separate collaborators behind a façade.
- The same **BigDecimal scale/rounding** appears 3+ times → `toUsdScale()` /
  `PrecisionConstants` / shared helper.
- A view block is copied across pages → `view/component/*` + `:common`
  strings/IDs.

### Do **not** extract when

- A one-off private helper used once — leave it local.
- "Future flexibility" ports with a single impl and no test fake need — YAGNI.
- Cross-layer "utils" that would let views call Kraken or engines touch DB.
- New sealed hierarchies for problems already solved by `Result` /
  `OrderResult`.

### Size / smell triggers (investigate, don't blindly split)

- `*Impl` file continuously growing with unrelated sections (sync + HTTP +
  math).
- Constructor arity climbing because the type absorbed collaborators (prefer
  façade + smaller types, like history).
- Comments explaining *what the next 40 lines do* → extract a named function
  instead of a comment (see
  [complex-code-comments](../complex-code-comments/SKILL.md)).

---

## DI / module shape (do not "simplify" away)

See [koin-di-and-config](../koin-di-and-config/SKILL.md).

- Keep `coreModule` (domain/persistence/exchange) separate from `webModule`
  (views/controller).
- `KrakenService` binding stays `DynamicKrakenService` wrapping real + sim.
- `PortfolioManagerImpl`: **explicit** `single { … krakenService = get() }` —
  `singleOf` skips the nullable param and disables cycle pinning.
- `KrakenServiceImpl`: **explicit** `single { … }` so default `RateLimiter()`
  remains; tests inject recording limiters.
- New services: prefer constructor injection of **ports**; register in the
  matching module; avoid service locators (`get()` outside Koin DSL / route
  inject).

---

## Kotlin craft standards (Pass 1 + ongoing)

- **Errors:** use domain `Result` / `OrderResult` on analysis/order paths; do
  not swallow exceptions with empty `catch`. Always rethrow
  `CancellationException` before other catches.
- **Nulls:** prefer `?.` / `?:` / early `return`; avoid `!!` in `src/main`
  (scan with `rg '\!\!'`).
- **Concurrency:** no `GlobalScope`; blocking DB/network under `Dispatchers.IO`
  via `safeTransactionIO` / `withContext`; structured concurrency for loops.
- **Money:** `BigDecimal` + `toUsdScale()` / `toCryptoScale()` /
  `PrecisionConstants`; no `Double`/`Float`/`toDouble()` in production money
  paths; tests: `shouldBeEqualComparingTo` only.
- **Transactions:** repository writes through `safeTransaction` /
  `safeTransactionIO` — no bare `transaction { }` that bypasses error wrapping.
- **Strings/IDs:** UI and route literals in `:common` (`ViewText`, `HtmlIds`,
  `CssClass`, `Routes`); no raw duplicated labels in components.
- **Imports:** no FQNs unless collision; no absolute user paths / machine
  hostnames.
- **Comments:** only non-obvious intent/invariants (pinning, fail-closed,
  pair-alias exact match). Delete stale comments; do not strip the *why* on
  money-path traps.
- **API surface:** keep façade/port methods intentional; don't expose
  collaborator types to controllers "temporarily."
