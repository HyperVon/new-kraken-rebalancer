# Skill content backlog — paste-ready drafts (2026-07-26)

**Status: APPLIED** (2026-07-26) — all drafts below were pasted into the named
skills. Keep this file as an archive / audit trail; do not re-apply blindly.
Future content-mode reviews should append new IDs (or open a dated section)
rather than re-pasting these.

Output of `skill-reviewer` in **content mode**. The companion triage canvas
(`skill-content-enhancements.canvas.tsx`) was a local Cursor artifact and is not
committed to this repo.

Severity: **P0** money/safety teaching gap · **P1** high-leverage pattern ·
**P2** valuable checklist · **P3** optional depth.

---

## P0 — money / safety teaching gaps

### [KOIN-CRED-1] Preserve credential placeholders on unrelated settings saves

- Skill: `.agents/skills/koin-di-and-config/SKILL.md`
- Gap: Agents may overwrite `rebalancer-config.json` with resolved env secrets when the settings form posts credentials unchanged.
- Why: Disk leak of API keys; violates the local-trust security model.

````markdown
### Credential persistence (never write resolved secrets)

- `ConfigServiceImpl.updateConfig` calls `configForPersistence`: when posted
  `KrakenCredentials` equals the **previous resolved** pair, persist
  `persistedKrakenCredentials` (raw `${ENV:default}` placeholders from disk),
  not the resolved runtime values.
- The settings form posts resolved credentials back unchanged — saving loop
  delay / allocations must **not** materialize env vars into JSON.
- Anti-pattern: `updateConfig` → `writeConfigAtomically(full resolved config)`
  without the placeholder-preservation branch.
- Checklist: [ ] saving non-credential fields leaves `${KRAKEN_*}` intact;
  [ ] real rotation still writes new values when the user changes keys.
````

### [KRAKEN-NONCE-1] Monotonic nonce + `Invalid nonce` recovery

- Skill: `.agents/skills/kraken-api-integration/SKILL.md`
- Gap: Skill documents HMAC signing but not the nonce lifecycle.
- Why: Kraken rejects non-monotonic nonces; all private calls (trading + sync) stall after restart or clock skew.

````markdown
### Nonce (private calls only)

- `KrakenServiceImpl` seeds an `AtomicLong` from
  `System.currentTimeMillis() * 1_000_000L` and uses `incrementAndGet()` per
  private request — nonces must stay **strictly increasing**.
- On Kraken `Invalid nonce` inside `queryPrivate`, bump the generator by
  `100_000_000L * (1 shl retryCount)` (up to 5 inner retries) before
  re-signing — never reuse the failed nonce.
- Anti-patterns: random or time-only nonces; sharing one nonce across
  concurrent private posts; logging nonce + postData beside signatures.
- NTP rollback can still seed lower — the bump-and-retry path is the intended
  mitigation.
````

### [CR-SRP-1] SRP layer do-not table

- Skill: `.agents/skills/code-review/SKILL.md` §1
- Gap: Agents still put Kraken/DB/HTML calls into domain math, or cross-call orchestrators.
- Why: Live-trading bugs from wrong-layer I/O plus untestable math.

````markdown
### SRP do-nots (flag any violation)

| Type | Must NOT | May call |
| :--- | :--- | :--- |
| `RebalancerEngine` / `PortfolioCalculations` | Network, SQLite, Koin, Ktor, kotlinx.html | Pure `BigDecimal` + `Settings` / `Allocation` inputs |
| `PortfolioAnalyzerImpl` | Place orders; write trades | Kraken reads, ATH I/O, `RebalancerEngine`, emit analysis |
| `OrderExecutorImpl` | Target math, allocation %, ATH | `executeOrder`, USD settle polls, `withStableBackend` |
| `PortfolioManagerImpl` | Inline rebalance math or raw AddOrder | Orchestrate analyzer → executor → `addSnapshot` |
| `TradeHistoryServiceImpl` façade | Reimplement sync/query in controllers | Delegate to Sync / SnapshotStore / Query / Reconstruction |
| `view/component/*` | Business rules, Kraken calls | Render DTOs + `:common` constants; receive `Settings` |
| `:common` `commonMain` | JVM/JS-only imports, SLF4J, `java.math.*` | Routes, IDs, CSS tokens, wire DTOs, KMP-safe models |
````

### [CR-BACKEND-1] Backend pin + mode routing review checklist

- Skill: `.agents/skills/code-review/SKILL.md` §3
- Gap: Reviews miss unpinned `DynamicKrakenService` reads mid-cycle, or conflate `simulation` with `dryRun`.
- Why: Backend can flip live↔sim mid sell/buy/settle; orders may hit the wrong exchange.

````markdown
### Backend pinning & modes (money path)

- [ ] Full rebalance cycle wrapped in `krakenService.withStableBackend { … }`
- [ ] Trade sync during a cycle uses the same pin
- [ ] Nested `withStableBackend` reuses the outer pin — never re-resolves
- [ ] `simulation` selects the backend in `DynamicKrakenService`; `dryRun` is
      enforced inside the active backend's `executeOrder` — not in routing
- [ ] Unpinned reads (dashboard ticker/OHLC) are fine outside cycles; never mix
      pinned and unpinned Kraken calls inside one settle/placement sequence
- [ ] Live AddOrder includes deterministic `cl_ord_id` when `cycleId` is
      non-blank; blank `cycleId` omits it
- [ ] Flag any PR setting `dryRun = false` in templates, examples, or tests
      without explicit live-trading justification
````

### [AR-MONEY-1] Money-path red-team checklist

- Skill: `.agents/skills/architecture-review/SKILL.md` Step 3
- Gap: Review stays abstract; never forces a walk of sell → settle → buy.
- Why: Architecture changes here have direct fund-loss blast radius.
- **Superseded:** live skill text was corrected in adversarial PR review
  (`ca3355d`+). Use the draft below (not the original ≥95%-abort /
  “planned sells block buys” / bare “Idempotency” wording).

````markdown
### Money-path stress (mandatory before executor/manager/exchange redesign)

Trace in code, then answer:

1. **Trigger gate** — both `isSignificant` gates (deviation % + USD dust) required?
2. **Price safety** — missing/zero non-USD ticker aborts before any AddOrder?
3. **Sell-first** — sell phase runs before buys; USD settle runs only after
   **≥1 successful** sell (and not dry-run)? (All planned sells failing still
   allows buys from opening USD — do not invent “planned sells block buys”.)
4. **Settle fail-closed** — after settle polls, buys abort only when confirmed
   USD is **≤ 0**? (≥95% of projected is **early-accept**, not the abort
   threshold; best positive below 95% still proceeds.)
5. **Cycle cash cap** — multi-buy batch respects 99% of settled USD?
6. **Open-order uniqueness** — retries reuse deterministic `cl_ord_id` (not
   `userref`)? (`cl_ord_id` is **not** full request idempotency across
   filled/canceled orders — see kraken-api-integration.)
7. **Audit trail** — trades persist `cycleId` + `orderTxid`?
8. **Mode clarity** — operator distinguishes SIMULATION / DRY RUN / LIVE
   without reading logs?

If an Evolve/Replace option weakens a yes above, severity is ≥ P0 unless the
compensating control is explicit.
````

### [AR-PIN-1] Backend pinning as an architectural seam

- Skill: `.agents/skills/architecture-review/SKILL.md` Step 3 (exchange)
- Gap: `withStableBackend` treated as an implementation detail, not a concurrency contract.
- Why: Redesigns often break pin scope or add config reads to hot paths.

````markdown
### Evaluate `withStableBackend` on every exchange redesign

**Current contract:** a CoroutineContext pin is set at cycle/sync entry; nested
calls reuse it; unpinned calls re-read `settings.simulation`.

Ask:

- Does the proposal preserve the **entry-time backend** for the whole
  sell/settle/buy sequence?
- Can a settings save mid-cycle change which Kraken implementation serves an
  in-flight order?
- If services are split, where does the pin live (manager vs executor vs sync)?
  Exactly one owner.

**Keep current** when the pin prevents sim/live flapping. **Replace** only with
an equally explicit session-scoped exchange port (e.g. `ExchangeSession` passed
through the executor) — not ad-hoc `getConfig()` in hot paths.
````

### [WK-EVAL-1] When `EvaluationScenariosTest` is mandatory

- Skill: `.agents/skills/write-kotest/SKILL.md`
- Gap: Skill lists the suite but not when new/updated scenarios are required.
- Why: Unit tests pass while end-to-end money regressions ship.

`````markdown
### When EvaluationScenariosTest is mandatory

Add or update a scenario when the diff touches:

- `RebalancerEngine`, `PortfolioCalculations`, `OrderExecutorImpl`, or
  sell/settle/buy ordering
- `DynamicKrakenService` routing, `dryRun` / `simulation`, or `cl_ord_id` mapping
- Fiat correction, dust threshold, ATH/drawdown deployment, 99% buy cap,
  95% settle

Workflow:

1. Run the suite:

   ```bash
   ./gradlew test --tests "com.gemini.krakenbot.EvaluationScenariosTest" \
     -x jacocoTestCoverageVerification
   ```

2. Refresh the outcomes table in `docs/EVALUATION.md` from
   `build/reports/scenarios_evaluation_report.md`.
3. Prefer `FakeKrakenService` + `TestFixtures.DEFAULT_TEST_SETTINGS`; never a
   file-backed DB in unit tests.
`````

### [ACO-PASS2-1] Expand Pass 2 money/concurrency audit

- Skill: `.agents/skills/autonomous-code-optimizer/SKILL.md` Pass 2
- Gap: Pass 2 is four bullets; misses cancellation, pinning, façade boundaries.
- Why: The optimizer loop can "converge" while leaving trading-risk debt.

````markdown
## Pass 2 — Financial & concurrency

- [ ] No `Double` / `Float` in production money paths (`src/main`,
      `common/commonMain`); use `BigDecimal` + `toUsdScale()` / `toCryptoScale()`
- [ ] Tests use only `shouldBeEqualComparingTo` for `BigDecimal`
- [ ] Sell-first → settle (95% / 3× / 250ms) → 99% buy cap intact
- [ ] `withStableBackend` wraps full cycles/sync; no unpinned
      `executeOrder` / settle in the money path
- [ ] `collectLatest` loops rethrow `CancellationException` — never logged as a
      cycle error
- [ ] Hot SharedFlows keep documented buffer/overflow; SSE sends the latest
      snapshot before collecting
- [ ] `dryRun` ≠ `simulation` — scan tests/examples for casual `dryRun = false`
- [ ] RateLimiter Mutex + lockout backoff unchanged on private calls
````

### [ACO-FORBID-1] Forbidden "convergence" shortcuts

- Skill: `.agents/skills/autonomous-code-optimizer/SKILL.md` Pass 4
- Gap: No explicit ban on widening exclusions or flipping flags to reach green.
- Why: The optimizer can mask coverage and safety failures.

````markdown
### Do not count as a "clean cycle"

- Widening JaCoCo/Karma exclusions to pass gates without new tests
- Setting `dryRun = false`, or removing the mode plate / cache-bust, to "fix" UI
- Replacing fail-closed settle abort with timeout-and-continue
- Using `GlobalScope`, blocking IO on the Default dispatcher, or swallowing
  `CancellationException`
- Trusting cached `./gradlew` results after parallel agents in one clone
  (re-run with `--rerun-tasks`)
````

---

## P1 — high-leverage patterns

### [FJS-SSE-1] Correct SSE ownership (HTMX, not `EventSource`)

- Skill: `.agents/skills/frontend-js-development/SKILL.md`
- Gap: Skill claims `EventSource` on the status stream. **Verified against code:** `frontend-js/src` contains no `EventSource`; `DashboardShellComponent` wires `HtmxAttrs.SSE_CONNECT` → `Routes.API_STATUS_STREAM`.
- Why: Agents add duplicate client listeners and break the fragment lifecycle.

````markdown
## SSE (correct ownership)

**Server → browser:** the HTMX SSE extension connects to `/api/status/stream`
and triggers a dashboard fragment refresh on `sse:message`
(`DashboardShellComponent`: `hx-ext="sse"`, `sse-connect`, `hx-trigger="load, sse:message"`).

**`:frontend-js` does not use `EventSource`.** Client responsibilities after a swap:

- `htmx:afterSwap` → `updateAge()` + `reapplySort()`
- `setInterval(updateAge, 1000)` so STREAM → STALE flips without new SSE events
- Chart re-init via HTMX hooks / page detectors in `initOnLoad()` — not SSE parsing
````

### [KHV-SSE-1] Document the HTMX SSE shell pattern

- Skill: `.agents/skills/ktor-html-views/SKILL.md`
- Gap: Says the controller collects the flow; omits the shell wiring.
- Why: Agents invent a Kotlin/JS EventSource and duplicate the snapshot pipeline.

````markdown
## SSE delivery = HTMX + server push (not `:frontend-js` EventSource)

`DashboardShellComponent`:

- loads the `CdnUrls.HTMX_SSE` script
- wrapper sets `HtmxAttrs.HX_EXT` = `sse` and `SSE_CONNECT` = `Routes.API_STATUS_STREAM`
- inner fragment uses `hx-get="/fragments/dashboard"` with
  `hx-trigger="load, sse:message"`

Server: `DashboardController.handleSseStream` JSON-encodes snapshots. Client JS
only updates stream-chip timing; it never opens an EventSource.
````

### [COMMON-TR-1] Two `TradeRecord` types (wire vs JVM domain)

- Skill: `.agents/skills/common-kmp-module/SKILL.md`
- Gap: Skill lists `api/TradeRecord` but not the JVM domain type.
- Why: JSON DTO corruption, broken reconcile/dedupe, JS compile failures.

````markdown
### Two TradeRecord types (do not merge)

| Layer | Path | Fields |
| :--- | :--- | :--- |
| `:common` wire DTO | `api/TradeRecord.kt` | `timestamp`, `volume`, `usdAmount` … as **strings** for History JSON |
| JVM domain | `model/TradeRecord.kt` | `Instant`, `BigDecimal`, `TradeSource`, `cycleId`, `orderTxid` |

- Reconcile/dedupe extensions (`isMatchingApiTrade`, `isPairAliasDuplicateOf`)
  live on the **JVM model**, not in `:common`.
- Map explicitly at HTTP boundaries; never put `java.time.Instant` or JVM
  `BigDecimal` in `commonMain`.
````

### [COMMON-ASSET-1] `Asset` owns all Kraken alias rules

- Skill: `.agents/skills/common-kmp-module/SKILL.md`
- Gap: Pair/balance normalization rules are scattered; the exact-match invariant is unstated.
- Why: Wrong prices from substring collisions, failed reconcile, bad orders.

````markdown
### Kraken aliases live in `Asset` (`:common`)

- Ticker remap: `BTC→XBT`, `DOGE→XDG` via `toKrakenTicker()` / `tradingPair()`.
- Price lookup: the **exact set** `acceptedUsdQuotedPairs(symbol)` — never
  `pair.contains(symbol)` (prevents `XBTUSDT` → BTC mis-mapping).
- Balance keys: `possibleBalanceKeys()` + JVM `resolveBalance()` (`XXBT`, `ZUSD`, …).
- API → allocation symbol: `Asset.fromTradingPair(pair, allocations)` before
  persisting trades.
- New asset support: extend the `Asset` companion helpers first; do not hardcode
  pair strings in services.
````

### [FLOW-CANCEL-1] Cancellation is control flow, not a cycle error

- Skill: `.agents/skills/coroutines-flows-sse/SKILL.md`
- Gap: `collectLatest` restart is documented, but not the swallow-cancellation failure mode.
- Why: A config change or shutdown leaves a zombie or duplicated rebalance loop.

````markdown
### Cancellation is control flow (not a cycle error)

- In `PortfolioManagerImpl.runLoop`, rethrow `CancellationException` from the
  outer `collectLatest`, the inner cycle, and `delay()` — never log-and-continue.
- Catching cancellation inside `while (isRunning)` prevents `collectLatest` from
  cancelling the sleeping delay on a settings change.
- Same rule for SSE handlers and USD-settle polls: catch `Exception`, but always
  rethrow cancellation.
````

### [SYNC-WATERMARK-1] Never `max(watermark, latestTradeTime)`

- Skill: `.agents/skills/trade-history-sync/SKILL.md`
- Gap: Skill prefers `latestTradeTime` but does not forbid merging the two.
- Why: Shrinks the reconcile window and strands local estimates.

````markdown
### Effective sync watermark

- `effectiveLatest = latestTradeTime ?: watermarkInstant` — **only**
  null-coalesce, never `max()` the two.
- `latestTradeTime` excludes `dryRun = true` rows (`getLatestTradeTime` filter).
- `sync_watermark_epoch_sec` is written after every successful sync so
  dry-run-only accounts still advance.
- Anti-pattern: `max(latestTradeTime, watermark)` — shrinks overlap below the
  latest fill and skips unreconciled local estimates.
````

### [PORT-ZERO-1] Block zero/negative notional before AddOrder

- Skill: `.agents/skills/portfolio-rebalancing-math/SKILL.md`
- Gap: Dust skip is documented; `dustThresholdUSD = 0` and budget-trim-to-zero are not.
- Why: Zero-volume market orders reach the exchange and persist bogus trades.

````markdown
### Pre-flight order guards (`OrderExecutorImpl.executeSingleOrder`)

- After the dust check, abort when `usdAmount.signum() <= 0` or the computed
  `volume.signum() <= 0` — return `null`; do not call `executeOrder`.
- Applies when `dustThresholdUSD = 0`, or when a buy is trimmed to $0 by the
  99% cycle budget.
- Anti-pattern: relying on Kraken to reject zero volume — the app would still
  persist a `TradeRecord`.
````

### [EXPOSED-IO-1] Standard helpers: `safeTransactionIO` / `readTransactionIO`

- Skill: `.agents/skills/exposed-repository/SKILL.md`
- Gap: Skill shows manual `withContext(Dispatchers.IO) { safeTransaction }`; production uses `RepositoryUtils.kt` wrappers.
- Why: Inconsistent IO dispatch; easy to block the Default dispatcher.

````markdown
### Transaction helpers (`repository/impl/RepositoryUtils.kt`)

- **Writes:** `database.safeTransactionIO(log, message) { … }` — combines
  `withContext(Dispatchers.IO)` with `safeTransaction`.
- **Reads:** `database.readTransactionIO { … }` — plain `transaction(database)` on IO.
- `safeTransaction` wraps non-`IOException` failures as `IOException`; raw
  `IOException` is rethrown.
- Anti-pattern: `transaction { }` on `Dispatchers.Default` inside a suspend
  repository method.
````

### [KOIN-PM-1] `PortfolioManager` must inject `KrakenService` explicitly

- Skill: `.agents/skills/koin-di-and-config/SKILL.md`
- Gap: Skill shows the DynamicKraken binding but not the constructor trap.
- Why: Without injection, cycles lose `withStableBackend` pinning.

````markdown
### DI traps (AppModule)

- `PortfolioManagerImpl`: use explicit
  `single { PortfolioManagerImpl(..., krakenService = get()) }` — `singleOf`
  skips the nullable `krakenService` and disables cycle-level pinning.
- `KrakenServiceImpl`: explicit `single { KrakenServiceImpl(...) }` so the
  default `RateLimiter()` is used; tests inject a recording subclass.
- The `KrakenService` port is always `DynamicKrakenService`; code needing
  stability calls `withStableBackend`.
````

### [DRY-PIN-1] When the backend is (not) pinned

- Skill: `.agents/skills/dry-run-and-simulation/SKILL.md`
- Gap: Cycle/sync pinning is covered; request-time behavior is not.
- Why: Toggling simulation mid-request can mix live and emulator data in one response.

````markdown
### When the backend is (not) pinned

- **Pinned:** `performRebalanceCycle`, `syncTradesFromKraken`,
  `OrderExecutor.executeOrders` (nested pins reuse the outer pin).
- **Unpinned:** dashboard balance/price reads, health checks — each call
  re-resolves from config at invocation time.
- Anti-patterns: assuming a mid-cycle config flip affects an already-pinned
  cycle (it does not); assuming a multi-step unpinned handler sees one stable
  backend (it does not).
- Tests asserting mode routing should wrap the scenario in `withStableBackend`.
````

### [PORT-ALIAS-1] Exact pair-alias price resolution

- Skill: `.agents/skills/portfolio-rebalancing-math/SKILL.md`
- Gap: Skill says missing price aborts, but not how prices are matched.
- Why: Substring pair matching yields wrong valuations or false "price found".

````markdown
### Price lookup (`RebalancerEngine.resolvePriceFromTicker`)

- Try the exact ticker key `Asset.tradingPair(symbol)` first.
- Fallback: iterate `rawPrices` using `Asset.matchesUsdQuotedPair(key, symbol)`
  only — **never** `key.contains(symbol)`.
- Zero/missing non-USD price → `Result.Failure` with
  `ViewText.PRICE_NOT_FOUND_PREFIX` before any orders.
- Preserve this invariant when extracting helpers.
````

### [CR-FLOW-1] Flow / SSE diff checklist

- Skill: `.agents/skills/code-review/SKILL.md` §5
- Gap: Mentions `docs/FLOWS.md` but gives no actionable diff checks.
- Why: Config-change restarts and SSE races are common regressions.

````markdown
### Flow / SSE diff checks

- [ ] Config watch uses `collectLatest` — settings changes cancel the delay and restart
- [ ] `CancellationException` always rethrown in loop/SSE handlers
- [ ] Hot SharedFlow producers use `tryEmit` with documented overflow
      (config replay=1 DROP_OLDEST; snapshots buffer 16)
- [ ] SSE handler sends `getLatestSnapshot()` before collecting the flow
- [ ] Non-cancellation SSE errors stay isolated per client session
- [ ] Cold flows (paginated sync, balance poll) are not broadcast as hot flows
````

### [CR-UI-1] UI safety chrome checklist

- Skill: `.agents/skills/code-review/SKILL.md` §5
- Gap: Frontend bullets exist, but SSR/HTMX safety chrome is not reviewable.
- Why: Losing the mode plate or cache-bust is a high-impact operator error.

````markdown
### UI safety chrome (SSR + JS)

- [ ] Every page header uses `brandWithMode(settings)`; `Settings` threaded through
- [ ] Mode precedence `simulation` > `dryRun` > live — never inferred in client JS
- [ ] Stream chip uses `ViewText.STREAM` / `STREAM_STALE` only — never "LIVE"
- [ ] Dashboard fragment updates `#header-status` via `hx-swap-oob`
- [ ] Static assets cache-busted via `commonMetadataAndStyles()` / `rebalancerJsSrc()`
- [ ] History timeframe changes update all 6 summary cards
````

### [AR-FLOW-1] Flow ownership map

- Skill: `.agents/skills/architecture-review/SKILL.md` Step 2
- Gap: Step 2 lists packages but not flow ownership.
- Why: Agents propose a second SharedFlow or SPA polling without seeing duplication cost.

````markdown
### Flow ownership (record before recommending messaging/SPA rewrites)

| Flow | Type | Owner | Invariant |
| :--- | :--- | :--- | :--- |
| `watchConfigChanges()` | Hot SharedFlow | ConfigServiceImpl | Settings change restarts the loop |
| `snapshotFlow` | Hot SharedFlow | TradeHistorySnapshotStore | `tryEmit`; replay 1, buffer 16 |
| Paginated Kraken fetch | Cold Flow | TradeHistorySyncService | Suspending emit; 300s throttle |
| USD settle poll | Cold Flow | OrderExecutorImpl | Never broadcast to UI |

**Redesign smell:** adding an EventSource/WebSocket client when HTMX SSE +
fragment swap already delivers snapshots.
````

### [AR-SEC-1] No-auth blast-radius worksheet

- Skill: `.agents/skills/architecture-review/SKILL.md` Step 3 (security)
- Gap: Mentions no-auth but offers no structured exposure questions.
- Why: Proposals widen bind/CORS without sizing risk.

````markdown
### Local-trust dashboard — blast radius

For each recommendation affecting HTTP / CORS / bind / deploy:

1. Who can reach the process (localhost, LAN, tunnel)?
2. Does CORS still require `isLocalOrPrivateOrigin` — any new public origin?
3. Which mutating routes (`POST /settings`, rebalance triggers) stay unauthenticated?
4. What can an attacker on the same network do with SSE + settings?
5. Are secrets env-only and never logged (including HMAC)?

Default stance: no auth is acceptable **only** for a single operator on a
private network. If exposure grows, recommend reverse-proxy auth or
bind-to-localhost — not "add JWT later".
````

### [KRC-BD-1] BigDecimal hygiene

- Skill: `.agents/skills/kotlin-refactoring-and-cleanup/SKILL.md`
- Gap: Silent on `toUsdScale()` / `toCryptoScale()` vs raw `setScale`.
- Why: Inconsistent rounding breaks financial tests and live orders.

````markdown
## BigDecimal hygiene

- Production money code uses `BigDecimal` with `toUsdScale()`,
  `toCryptoScale()`, `toPercentScale()` from `BigDecimalExtensions.kt` plus
  `PrecisionConstants` — not ad-hoc scales or `doubleValue()`.
- Volume division uses an explicit scale with `RoundingMode.HALF_UP`.
- Repeated scales shared with JS belong in `PrecisionConstants` in `:common`.
````

### [KRC-SCAN-1] Expand the anti-pattern scanner

- Skill: `.agents/skills/kotlin-refactoring-and-cleanup/SKILL.md` Scanner
- Gap: Script only finds FQNs and absolute paths.
- Why: Cleanup passes miss `!!`, `GlobalScope`, and `Double` money.

`````markdown
### Additional scans (run during cleanup)

```bash
rg '\!\!' src/main/ common/src/commonMain/ --glob '*.kt'
rg 'GlobalScope' src/ common/ frontend-js/ --glob '*.kt'
rg '\.toDouble\(\)' src/main/ common/src/commonMain/ --glob '*.kt'
rg 'shouldBeEqualByComparingTo' src/test/ --glob '*.kt'
rg 'dryRun\s*=\s*false' src/test/ src/main/ --glob '*.kt'
```
`````

### [WK-MOCK-1] MockK + Exposed static-mock teardown

- Skill: `.agents/skills/write-kotest/SKILL.md`
- Gap: No guidance for `mockkStatic(TransactionApi)` used in repository tests.
- Why: Leaked static mocks cause order-dependent failures.

````markdown
### MockK + Exposed static mocks

- Use the constant
  `TestFixtures.ORG_JETBRAINS_EXPOSED_SQL_TRANSACTIONS_TRANSACTION_API_KT`.
- Always `unmockkStatic(...)` in `finally` or test teardown.
- Prefer real `:memory:` SQLite via `DatabaseConfig.init(":memory:")` unless the
  test targets failure mapping.
- For services:
  `coEvery { kraken.withStableBackend(any()) } coAnswers { firstArg<suspend (KrakenService) -> Any>().invoke(fakeKraken) }`
````

### [WK-CANCEL-1] Loop and config-change test assertions

- Skill: `.agents/skills/write-kotest/SKILL.md`
- Gap: `advanceUntilIdle` documented; restart-on-config-change behavior is not.
- Why: Loop restart bugs are core regressions.

````markdown
### Loop & config-change tests

- Emit settings while the loop sits in `delay(loopDelaySeconds)` — expect
  `collectLatest` to cancel and restart with the new settings.
- Assert `CancellationException` is not logged as a cycle failure.
- Use `runTest` + `advanceUntilIdle()` after emissions.
- For SSE: assert the initial snapshot send, then subsequent flow events.
````

### [GQG-EXCL-1] Single `coverageExcludes` source

- Skill: `.agents/skills/gradle-quality-gates/SKILL.md`
- Gap: Lists exclusions but not the "one constant, two tasks" rule.
- Why: Agents update the report filter only; verification drifts.

````markdown
### JaCoCo exclusion sync rule

Exclusions live in the shared `coverageExcludes` list in root
`build.gradle.kts`. **Both** `jacocoTestReport` and
`jacocoTestCoverageVerification` must use the same
`fileTree { exclude(coverageExcludes) }`.

When moving packages:

1. Add tests, or add to `coverageExcludes` with a comment (views / DSL /
   Ktor bootstrap only).
2. Update the README package tree.
3. Never exclude money-path packages (`OrderExecutor*`, `RebalancerEngine`,
   trade repositories).
````

### [GQG-PAR-1] Parallel Gradle / overlapping builds

- Skill: `.agents/skills/gradle-quality-gates/SKILL.md`
- Gap: Mentions `parallel=true` but not the multi-agent EOF / stale UP-TO-DATE trap.
- Why: False green from overlapping `./gradlew` runs in one clone.

`````markdown
### Multi-agent / CI verification

- One `./gradlew` per clone at a time — concurrent workers cause `EOFException`
  and flaky `UP-TO-DATE`.
- After parallel Task agents merge:

  ```bash
  ./gradlew build jacocoTestCoverageVerification --rerun-tasks
  ```

- Fast evaluation iteration is fine
  (`-x jacocoTestCoverageVerification`), but run full gates before shipping.
`````

### [KHV-CACHE-1] Mandatory static cache-bust

- Skill: `.agents/skills/ktor-html-views/SKILL.md`
- Gap: Cache-bust lives in a QA rule, not the view skill checklist.
- Why: CSS/JS edits look broken (native white controls) without `?v=`.

````markdown
### Static asset cache-bust (required on every layout change)

- CSS via `commonMetadataAndStyles()` → `/static/style.css?v=<hash>`
- JS via `rebalancerJsSrc()` → `/static/rebalancer.js?v=<hash>`
- Never hand-roll `/static/...` hrefs in new pages
- `DashboardViewTest` asserts `link href="/static/style.css?v="`
````

### [FJS-LIFE-1] DOM lifecycle contract

- Skill: `.agents/skills/frontend-js-development/SKILL.md`
- Gap: One-line DOM section; missing global registration and post-swap rebinding.
- Why: HTMX swaps orphan chart state and sort handlers.

````markdown
### Lifecycle (main.kt contract)

1. `main()` registers globals once (dashboard, settings, history).
2. A single `htmx:afterSwap` listener rebinds age display + table sort.
3. `initOnLoad()` gates page-specific init on `HtmlIds` presence.
4. On History chart rebuild: deep-clone Chart.js options, re-attach
   `onZoomComplete`, enable the scrubber after any zoom path.
5. Clear intervals/listeners when adding History-only timers.
````

### [ACO-PASS3-1] Pass 3 architecture ownership audit

- Skill: `.agents/skills/autonomous-code-optimizer/SKILL.md` Pass 3
- Gap: Four bullets; no façade, view, or `:common` purity scans.
- Why: The "architecture pass" degenerates into CSS tweaks.

````markdown
## Pass 3 — Architecture

- [ ] SRP boundaries match the code-review do-not table
- [ ] Repositories use `safeTransaction` / `safeTransactionIO`
- [ ] New UI lives in `view/component/*` + `view/css/*`; strings/IDs in `:common`
- [ ] HTMX SSE shell intact; no new `EventSource` in `frontend-js`
- [ ] Mode plate + `hx-swap-oob` header status preserved
- [ ] Cross-check domain skills (math, flows, exposed, `:common`)
````

### [KRAKEN-ADDORDER-1] AddOrder field-name trap

- Skill: `.agents/skills/kraken-api-integration/SKILL.md`
- Gap: Documents `cl_ord_id` but not Kraken's `type` vs `ordertype` fields.
- Why: Swapped params cause rejected orders or the wrong side.

````markdown
### AddOrder field names (Kraken REST)

- `KrakenServiceImpl.executeOrder` maps `type` ← **order side**
  (`buy` / `sell` via `OrderSide.apiValue`) and `ordertype` ← `market`.
- `cl_ord_id` seed uses lowercase `side.apiValue` in
  `OrderExecutorImpl.clientOrderId(cycleId, symbol, side)`.
- Volume: scale 8, `stripTrailingZeros()`, `toPlainString()` before POST.
- A non-null `dryRun` argument overrides config; `OrderExecutor` always passes
  `settings.dryRun` explicitly.
````

---

## P2 — valuable checklists

### [PORT-PROJ-1] `projectedCash` is gross sell notional

- Skill: `.agents/skills/portfolio-rebalancing-math/SKILL.md`

````markdown
- After each successful sell, `projectedCash += usdToSell` (order **notional**, pre-fee).
- Live settle replaces this with fill-confirmed proceeds: sum
  `(usdAmount − fee)` for matching sell `orderTxid`s, capped by balance peek /
  projected cash.
- Dry-run or no sells: skip settle; buys budget off projected cash only.
````

### [PORT-FIAT-1] Fiat-correction budget truncates with `RoundingMode.DOWN`

- Skill: `.agents/skills/portfolio-rebalancing-math/SKILL.md`

````markdown
- In `distributeFiatCorrection`:
  `remaining = deviationAbs.setScale(SCALE_USD, RoundingMode.DOWN)`; each
  `share = min(remaining, computedShare)`.
- Skip symbols whose proportional share rounds to `$0.00`.
- Fiat correction runs only when USD alone triggered **and** crypto order maps
  are empty.
````

### [DRY-SUCCESS-1] `dryRun` orders return `success = true`

- Skill: `.agents/skills/dry-run-and-simulation/SKILL.md`

````markdown
- Live backend dry-run: log `[DRY RUN]`, return
  `OrderResult(success = true, dryRun = true)` — no AddOrder POST.
- Emulator dry-run: `[EMULATOR DRY RUN]`, same success semantics, no balance
  mutation, usually no `orderTxid`.
- The activity log always prefixes `[DRY RUN]` regardless of backend.
- Cycle math and snapshots still run; only placement/settle differ.
````

### [KOIN-ENV-1] `${ENV:default}` substitution + JSON escaping

- Skill: `.agents/skills/koin-di-and-config/SKILL.md`

````markdown
- Pattern `"${KRAKEN_API_KEY:YOUR_KRAKEN_API_KEY}"` — regex `\$\{([^}]+)}`,
  key/default split on the first `:`.
- Resolution order: non-blank `System.getenv(key)` → placeholder default → `""`;
  then JSON-escape `\` and `"` before splicing into raw file text.
- Validation throws `InvalidConfigurationException` wrapping the
  `IllegalArgumentException` message for UI display.
````

### [SYNC-FP-1] In-sync API fill fingerprint

- Skill: `.agents/skills/trade-history-sync/SKILL.md`

````markdown
- Within one sync pass, dedupe API rows with `apiFillIdentityKey`:
  `timestamp|pair|side|volume|usdAmount|fee|orderTxid`.
- Include economics **and** timestamp — one AddOrder can produce multiple fill
  legs sharing an `orderTxid`.
- Skip rows whose key is already in `seenApiFillKeys` (newest-first pagination
  overlap).
````

### [SYNC-RECON-1] Auto snapshot reconstruction gate

- Skill: `.agents/skills/trade-history-sync/SKILL.md`

````markdown
- After sync, when `!simulation && totalTrades > 0 && snapshots.size <= 1`, call
  `TradeHistoryReconstructionService.reconstructHistoricalSnapshots()`
  (OHLC ~95 days; prune span 90 days).
- Simulation seeding is separate (~15 days of snapshots / ~15 fills) and only
  when the DB is empty with `simulation = true`.
- Reconcile updates preserve local `cycleId`, prefer API `orderTxid`, and retain
  `expectedPrice` for slippage recompute.
````

### [FLOW-SETTLE-1] Fill-confirm poll constants

- Skill: `.agents/skills/coroutines-flows-sse/SKILL.md`

````markdown
- `pollFillConfirmedUsd`: 3 attempts, 250ms doubling backoff (cap 32s), 95%
  early accept vs `projectedCash`, `startSec = now − 600s`.
- `sumMatchedSellProceeds`: up to 5 pages × 50 rows; match sell `orderTxid`;
  net `usdAmount − fee`; keep scanning after the first sighting (multi-leg fills).
- Cap fill-confirmed USD with `min(balancePeek, projectedCash)` when balance is visible.
````

### [EXPOSED-MEM-1] In-memory SQLite keepalive

- Skill: `.agents/skills/exposed-repository/SKILL.md`

````markdown
- `DatabaseConfig`: `:memory:` becomes
  `jdbc:sqlite:file:<uuid>?mode=memory&cache=shared&foreign_keys=true`, plus a
  shutdown-hook keepalive `Connection` per URL.
- Tests must use `:memory:` (or that shared URL) — never a file DB.
- Schema boot: `createStatements` + `addMissingColumnsStatements` +
  `checkMappingConsistence` in one transaction (Exposed 1.x — no deprecated
  `createMissingTablesAndColumns`).
````

### [EXPOSED-UPD-1] `updateTrade` PK-first with narrow fallback

- Skill: `.agents/skills/exposed-repository/SKILL.md`

````markdown
- Default: `TradeTable.update({ TradeTable.id eq oldTrade.id })`.
- Fallback **only** when `oldTrade.id == null`: match timestamp + pair +
  normalized side + volume.
- Persist sides via `OrderSide.normalize()`.
- `getLatestTradeTime()` filters `dryRun = false` — coordinates with the sync
  watermark.
````

### [KRAKEN-RETRY-1] Separate lockout vs network retry budgets

- Skill: `.agents/skills/kraken-api-integration/SKILL.md`

````markdown
- `retryWithFlow` tracks `attempt` (network / rate limit) and `lockoutAttempt`
  separately — lockout doubles 10s → 15m without consuming the 5 network attempts.
- `queryPublic` uses `retryWithFlow` but no RateLimiter; private calls always
  `acquireWithCost` first.
- `getTradeHistory` returns `emptyList()` when credentials are missing — do not
  treat that as "no trades on the exchange".
````

### [CR-ROUND-1] Flag raw scaling in money code

- Skill: `.agents/skills/code-review/SKILL.md` §2

````markdown
- Flag raw `setScale(2)` / `doubleValue()` in money paths; prefer
  `toUsdScale()` / `toCryptoScale()`.
- Tests must use `shouldBeEqualComparingTo`.
````

### [AR-EVAL-1] Evaluation suite as an architectural asset

- Skill: `.agents/skills/architecture-review/SKILL.md` Step 3

````markdown
Any proposal replacing manager / executor / analyzer must either port
`EvaluationScenariosTest` + `SimulationEvaluationScenariosTest`, or document
kill criteria plus a new proof harness. "Unit tests are sufficient" fails the
filter.
````

### [KRC-REPO-1] Use `safeTransactionIO` when refactoring repositories

- Skill: `.agents/skills/kotlin-refactoring-and-cleanup/SKILL.md`

````markdown
Repository I/O from suspend callers uses
`database.safeTransactionIO(log, "…") { }` from `RepositoryUtils.kt`; do not
call blocking `safeTransaction` from coroutine paths without
`withContext(Dispatchers.IO)`.
````

### [WK-SIM-1] Fake vs Simulated evaluation split

- Skill: `.agents/skills/write-kotest/SKILL.md`

````markdown
- `EvaluationScenariosTest` → `FakeKrakenService` (controlled behavior).
- `SimulationEvaluationScenariosTest` → production `SimulatedKrakenService`
  stack; use it when validating emulator integration, not unit order math.
````

### [GQG-EXCL-2] Exclusion widening is not a fix

- Skill: `.agents/skills/gradle-quality-gates/SKILL.md`

````markdown
Acceptable exclusions: Ktor bootstrap, HTML DSL / CSS, thin Kraken interface.
Never exclude trade persistence, executor, engine, or config validation — add
tests instead.
````

### [KHV-OOB-1] `hx-swap-oob` stream-status fragment

- Skill: `.agents/skills/ktor-html-views/SKILL.md`

````markdown
`DashboardFragmentComponent.renderStreamStatus` sets `hx-swap-oob="true"` on
`#header-status` only; the mode plate stays in the shell. `StatusCard.Live`
means a healthy **stream**, not live trading.
````

### [FJS-STALE-1] Shared stale threshold

- Skill: `.agents/skills/frontend-js-development/SKILL.md`

````markdown
Use `PrecisionConstants.STALE_THRESHOLD_SECONDS` from `:common` in both JS and
JVM — never duplicate magic seconds in frontend-only constants.
````

### [ACO-RERUN-1] Clean-cycle verification command

- Skill: `.agents/skills/autonomous-code-optimizer/SKILL.md` Pass 4

````markdown
Final pass: `./gradlew build jacocoTestCoverageVerification --rerun-tasks`,
plus `:frontend-js:jsBrowserTest` when JS changed.
````

---

## P3 — optional depth

### [PORT-SCALE-1] Analysis vs snapshot percent scales

- Skill: `.agents/skills/portfolio-rebalancing-math/SKILL.md`

````markdown
- Deviation math uses `SCALE_PERCENT = 4`; USD values `SCALE_USD = 2`.
- `createAssetSnapshot` rounds displayed percents to 2dp — do not reuse snapshot
  percents as trigger inputs.
- Accumulate raw per-asset USD and round the portfolio total once.
````

### [COMMON-ORDERSIDE-1] `apiValue` vs stored `name`

- Skill: `.agents/skills/common-kmp-module/SKILL.md`

````markdown
- REST / Kraken: lowercase `apiValue` (`buy` / `sell`); DB and UI: uppercase
  `name` via `OrderSide.normalize()`.
- `OrderType.MARKET` is the only wired order type in live and emulator paths.
````

### [FLOW-SSE-1] SSE connect race + producer isolation

- Skill: `.agents/skills/coroutines-flows-sse/SKILL.md`

````markdown
- `handleSseStream` sends the DB latest snapshot, then collects the hot flow
  (`replay = 1` covers the subscribe race; a duplicate first event is acceptable).
- `snapshotFlow`: `extraBufferCapacity = 16`, `DROP_OLDEST`, `tryEmit` — slow
  clients must not block rebalance producers.
- Per-session SSE errors are swallowed (non-cancellation); other subscribers continue.
````

### [DRY-EMU-1] Emulator constraints

- Skill: `.agents/skills/dry-run-and-simulation/SKILL.md`

````markdown
- `SimulatedKrakenService`: MARKET orders only; synchronized lazy balance/price
  init; random-walk prices per ticker fetch.
- Dry-run emulator orders omit `orderTxid`, so sell settle uses the balance-poll
  fallback by design.
````

---

## 2026-07-26 — autonomous-code-optimizer (architecture / design / standards) — APPLIED

All findings below were applied on 2026-07-26: stance, design principles,
pattern-driven Pass 3, and architecture anti-patterns landed in
`SKILL.md`; the pattern catalog, layer graph, refactor decision rules, DI
shape, and Kotlin craft standards moved to the new sibling
`autonomous-code-optimizer/architecture-patterns.md` (ACO-SIBLING-1).

Scope: content-mode skill-reviewer pass targeting
`.agents/skills/autonomous-code-optimizer/SKILL.md` only. Emphasis: teach
**architecture patterns, design standards, and coding craft** grounded in this
repo *and* industry practices that fit a money-moving Kotlin service — not
another copy of the code-review checklist.

Historical gap (now addressed): prior ACO checklist items (`ACO-PASS2-1`,
`ACO-PASS3-1`, `ACO-FORBID-1`, `ACO-RERUN-1`) were insufficient alone — Pass 3
**was** a thin pointer, so optimizers could “converge” on FQNs/Spotless while
leaving layering and design debt untouched. Pass 3 is now a pattern-driven
audit linked to `architecture-patterns.md`.

### Status legend

- **PENDING** — not yet pasted into the skill
- **APPLIED** — merged into skill / sibling ref

---

### [ACO-STANCE-1] P1 — Optimize inside the architecture; do not redesign

- Skill: `.agents/skills/autonomous-code-optimizer/SKILL.md` (intro / stance)
- Gap: Agents treat “exhaustive optimize” as license to invent hexagonal
  packages, event buses, or merge SRP types for “simplicity.”
- Why: Collides with [architecture-review](skills/architecture-review/SKILL.md)
  (recommend-only redesign) and can break money-path invariants.

````markdown
## Stance (non-negotiable)

This skill **improves code quality inside the current architecture**. It does
**not** redesign module boundaries, replace SSR/HTMX, swap SQLite, or invent
new messaging layers.

| Intent | Skill |
| :--- | :--- |
| Clean / converge / remove debt **in place** | **this skill** |
| “Should we redesign X?” | [architecture-review](../architecture-review/SKILL.md) |
| Scoped style/FQN/` :common` cleanup | [kotlin-refactoring-and-cleanup](../kotlin-refactoring-and-cleanup/SKILL.md) |
| Product/UI/docs improvements + PR | [continuous-improvement](../continuous-improvement/SKILL.md) |

**Allowed:** extract helpers, restore SRP, delete dead code, fix layering
violations, DRY within a layer, tighten types, improve tests.

**Forbidden without explicit user approval:** new top-level packages that
bypass `service` / `repository` / `view` / `controller`; replacing
`DynamicKrakenService` pinning; replacing HTMX SSE with a JS `EventSource` /
WebSocket client; collapsing façade collaborators into one god class “for
simplicity.”
````

---

### [ACO-PATTERNS-1] P1 — Named patterns already in the codebase (preserve)

- Skill: Pass 3 → new subsection or sibling `architecture-patterns.md`
- Gap: Pass 3 never names the patterns agents must recognize, so refactors
  erase them.
- Why: Mid-level engineers following only the thin checklist do not know
  *what good looks like* here.
- Grounding: `AppModule`, `KrakenService` port, `DynamicKrakenService`,
  `TradeHistoryServiceImpl`, `RebalancerEngine`, `view/component/*`, FLOWS.md.

````markdown
## Architecture patterns in this repo (preserve under optimization)

Treat these as **load-bearing design**, not accidental structure. Before
merging or deleting a type, name which pattern it implements.

### 1. Ports & adapters (hexagonal-lite)

- **Ports:** interfaces in `service/` and `repository/`
  (`KrakenService`, `PortfolioManager`, `OrderExecutor`, `TradeRepository`, …).
- **Adapters:** `service/impl/*`, `repository/impl/*`, Ktor
  `controller` / `view`.
- **Rule:** Domain orchestration depends on **ports**, not concrete adapters.
  Optimizing “away” an interface that has a Fake/Mock in tests is a
  regression unless the fake moves with it.

### 2. Strategy + coroutine-context pin (`DynamicKrakenService`)

- `simulation` selects **which** adapter (`KrakenServiceImpl` vs
  `SimulatedKrakenService`); `dryRun` is enforced **inside** the adapter’s
  `executeOrder`, not in the router.
- `withStableBackend` pins the adapter on `CoroutineContext` for the whole
  cycle/sync so a mid-cycle settings flip cannot split sell/buy across
  backends.
- **Rule:** Never “simplify” by resolving the backend on every call inside a
  money path. Nested pins must reuse the outer pin (already implemented —
  do not break).

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
- Logging is allowed for cycle diagnostics; still treat as calculation logic.
- `PortfolioAnalyzerImpl` owns I/O (balances, prices, ATH) and **delegates**
  math to the engine.
- **Rule:** New trigger/ATH/dust logic goes in the engine/calculations — not
  in the executor or a view. Price-safety abort (`Result.Failure`) stays in
  the calculator path before orders.

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
  “optimizing.”

### 7. Hot vs cold Flow ownership

- Hot `SharedFlow`: config watch, snapshot broadcast (UI).
- Cold `Flow`: paginated sync, USD settle poll (execution — never broadcast).
- **Rule:** Promoting a cold poll to a hot SharedFlow “for reuse” is an
  architecture bug. See `docs/FLOWS.md`.

### 8. Explicit success/failure types

- Domain analysis: `com.gemini.krakenbot.model.Result` (KMP; not
  `kotlin.Result`).
- Orders: `OrderResult` sealed interface.
- **Rule:** Prefer these over nullable-or-throw control flow on money/analysis
  paths. Do not replace with generic `Either` libraries.
````

Optional sibling: `.agents/skills/autonomous-code-optimizer/architecture-patterns.md`
if SKILL.md would exceed ~500 lines — keep SKILL.md as the loop + short
stance; put the pattern catalog in the sibling and link it from Pass 3.

---

### [ACO-LAYER-1] P1 — Dependency direction (allowed import graph)

- Skill: Pass 3
- Gap: No enforceable layering rules; agents pull Kraken/DB into views or math
  into controllers.
- Why: Layering is the #1 architecture standard that survives refactors.
- Grounding: controller deps today (Config/TradeHistory/View only); engine has
  no Kraken; AppModule splits `coreModule` / `webModule`.

````markdown
## Layer dependency rules (Pass 3 scan)

Allowed directions (→ = “may depend on”):

```text
controller  →  service ports, view, :common (Routes/DTOs), api mappers
view/*      →  :common view utils, wire DTOs/models for display, Settings
service/impl → service ports, repository ports, util, :common models/config
RebalancerEngine / PortfolioCalculations → :common config/models/util only
  (no repository, no KrakenService, no Ktor, no Koin)
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
  display formatting (display may use `PortfolioCalculations` helpers for
  percent bars — must not place orders or change targets)

Quick ripgrep (treat hits as defects unless justified):

```bash
rg 'KrakenService|OrderExecutor|TradeRepository' src/main/kotlin/com/gemini/krakenbot/view --glob '*.kt'
rg 'repository\.|KrakenService' src/main/kotlin/com/gemini/krakenbot/service/impl/RebalancerEngine.kt \
  src/main/kotlin/com/gemini/krakenbot/service/impl/PortfolioCalculations.kt
rg 'KrakenServiceImpl|SimulatedKrakenService' src/main/kotlin/com/gemini/krakenbot/controller --glob '*.kt'
```
````

---

### [ACO-PRINCIPLES-1] P0 — Design principles for money-moving code

- Skill: new section before Pass 2 / 3 (or Pass 2 intro)
- Gap: Checklists list mechanics; agents lack *principles* that decide hard
  trade-offs during cleanup.
- Why: Without principles, “make it cleaner” deletes fail-closed paths and
  idempotency.
- Mix: industry (fail-closed, idempotency, least privilege) + repo evidence
  (settle abort, `cl_ord_id`, CORS local-trust, dryRun pin on executeOrder).

````markdown
## Design principles (apply on every optimization decision)

1. **Fail closed on money paths** — Missing/zero price aborts the cycle;
   unsettleable USD after sells aborts buys; never “timeout and continue”
   into live buys. Prefer no trade over a wrong trade.
2. **Idempotency & stable identity** — Live AddOrder uses deterministic
   `cl_ord_id` from `cycleId|symbol|side` when `cycleId` is non-blank;
   **`userref` is not uniqueness**. Do not remove client order ids while
   deduplicating code.
3. **Mode orthogonality** — `simulation` (which backend) ⊥ `dryRun` (whether
   to place). Do not collapse into one flag for “simplicity.”
4. **Pin for the unit of work** — One rebalance/sync = one pinned backend +
   one `dryRun` snapshot passed into `executeOrder`. Mid-cycle config flips
   must not fork the unit of work.
5. **Cancellation is control flow** — `CancellationException` always rethrown
   in loops/SSE; never logged as a business error (`collectLatest` restarts
   depend on this).
6. **Least privilege at the edge** — No-auth dashboard ⇒ keep CORS
   `isLocalOrPrivateOrigin`; never widen to `*` while cleaning config.
7. **Pure core, impure shell** — Push decisions that need tests without I/O
   into `RebalancerEngine` / `PortfolioCalculations`; keep I/O at analyzer,
   executor, repositories.
8. **Prefer delete + extract over abstract** — During optimization, remove
   dead code and extract duplication *within a layer*. Do not introduce
   frameworks, new DI scopes, or generic “BaseService” hierarchies.
9. **Observability without secrecy** — Keep `cycleId` in MDC for cycle logs;
   never log HMAC, API secrets, or resolved credentials.
10. **Coverage is evidence, not a goal** — New tests for behavior you change;
    never widen JaCoCo/Karma exclusions to declare convergence.
````

---

### [ACO-EXTRACT-1] P1 — When to extract vs leave alone (optimizer decision rules)

- Skill: Pass 3 or new “Refactor decision rules”
- Gap: Agents either over-abstract or leave god-methods because the skill
  only says “SRP.”
- Why: Extraction heuristics are a coding-standard agents lack.
- Grounding: history package split; AppModule explicit ctor comments (#89);
  OverviewGrid display helpers vs engine math.

````markdown
## Refactor decision rules (Pass 3)

### Extract when

- A function mixes **I/O + pure math** → split so math is unit-testable
  without fakes (engine/calculations pattern).
- A type has **two reasons to change** (e.g. sync pagination vs snapshot
  broadcast) → separate collaborators behind a façade.
- The same **BigDecimal scale/rounding** appears 3+ times →
  `toUsdScale()` / `PrecisionConstants` / shared helper.
- A view block is copied across pages → `view/component/*` + `:common`
  strings/IDs.

### Do **not** extract when

- A one-off private helper used once — leave it local.
- “Future flexibility” ports with a single impl and no test fake need —
  YAGNI.
- Cross-layer “utils” that would let views call Kraken or engines touch DB.
- New sealed hierarchies for problems already solved by `Result` /
  `OrderResult`.

### Size / smell triggers (investigate, don’t blindly split)

- `*Impl` file continuously growing with unrelated sections (sync + HTTP +
  math).
- Constructor arity climbing because the type absorbed collaborators (prefer
  façade + smaller types, like history).
- Comments explaining *what the next 40 lines do* → extract a named function
  instead of a comment (see complex-code-comments).
````

---

### [ACO-DI-1] P2 — Preserve DI shape while moving code

- Skill: Pass 3 checklist item + pointer to koin-di-and-config
- Gap: Optimizers switch everything to `singleOf` and break nullable
  `krakenService` injection / RateLimiter defaults.
- Why: DI wiring is part of the architecture.
- Grounding: `AppModule.kt` comments on explicit ctors.

````markdown
### DI / module shape (do not “simplify” away)

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
````

---

### [ACO-KOTLIN-1] P2 — Coding standards beyond Spotless/FQN

- Skill: Pass 1 expansion or “Kotlin craft” subsection
- Gap: Pass 1 is FQN/paths/secrets only; craft standards live nowhere in the
  optimizer loop.
- Why: Exhaustive cleanup should raise the floor of idiomatic Kotlin *as this
  repo writes it*.
- Grounding: `Result` fold/map; sealed `OrderResult`; `safeTransactionIO`;
  rethrow cancellation; BigDecimal extensions.

````markdown
## Kotlin craft standards (Pass 1 + ongoing)

- **Errors:** use domain `Result` / `OrderResult` on analysis/order paths;
  do not swallow exceptions with empty `catch`. Always rethrow
  `CancellationException` before other catches.
- **Nulls:** prefer `?.` / `?:` / early `return`; avoid `!!` in `src/main`
  (scan with `rg '\!\!'`).
- **Concurrency:** no `GlobalScope`; blocking DB/network under
  `Dispatchers.IO` via `safeTransactionIO` / `withContext`; structured
  concurrency for loops.
- **Money:** `BigDecimal` + `toUsdScale()` / `toCryptoScale()` /
  `PrecisionConstants`; no `Double`/`Float`/`toDouble()` in production money
  paths; tests: `shouldBeEqualComparingTo` only.
- **Transactions:** repository writes through `safeTransaction` /
  `safeTransactionIO` — no bare `transaction { }` that bypasses error
  wrapping.
- **Strings/IDs:** UI and route literals in `:common` (`ViewText`, `HtmlIds`,
  `CssClass`, `Routes`); no raw duplicated labels in components.
- **Imports:** no FQNs unless collision; no absolute user paths / machine
  hostnames.
- **Comments:** only non-obvious intent/invariants (pinning, fail-closed,
  pair-alias exact match). Delete stale comments; do not strip the *why*
  on money-path traps.
- **API surface:** keep façade/port methods intentional; don’t expose
  collaborator types to controllers “temporarily.”
````

---

### [ACO-PASS3-REWRITE-1] P1 — Replace thin Pass 3 with a pattern-driven audit

- Skill: replace current Pass 3 bullets
- Gap: Current Pass 3 is seven checklist lines with no *how* or *why*.
- Why: This is the primary vehicle for architecture content in the loop.

````markdown
## Pass 3 — Architecture & design

Read [architecture patterns](architecture-patterns.md) (or the inlined
patterns section). Then audit:

1. **Pattern integrity** — Ports/adapters, façade, strategy+pin, pure engine,
   orchestrator/brain/brawn, SSR composition, hot/cold flows still intact.
2. **Layering** — Run the dependency-direction rg scans; fix violations by
   moving code to the correct layer (not by widening imports).
3. **SRP do-nots** — Match the code-review table; fix by moving methods, not
   by renaming only.
4. **Fail-closed & modes** — Settle abort, price abort, `dryRun`≠`simulation`,
   `cl_ord_id` path unchanged by cleanup.
5. **Persistence** — `safeTransaction` / `safeTransactionIO`; no controller
   SQL.
6. **UI shell** — Components under `view/component/*`; HTMX SSE shell; no new
   `EventSource`; mode plate + OOB header status; Chart.js deep-clone +
   callback re-attach.
7. **DI shape** — `coreModule`/`webModule`; explicit ctors where required.
8. **Cross-check** domain skills only for mechanics you touch (math, flows,
   exposed, `:common`, kraken) — do not paste their essays into diffs.

**Exit criterion:** zero layering violations, zero SRP do-not breaches, no
pattern erased “for simplicity.”
````

---

### [ACO-ANTI-1] P1 — Architecture anti-patterns (optimizer edition)

- Skill: extend “Do not count as a clean cycle” + Pass 3
- Gap: Forbidden list is safety/coverage oriented; missing design anti-patterns.
- Why: Agents need named bad moves.

````markdown
### Architecture anti-patterns (never “done”)

- **God-class merge** — folding Sync + SnapshotStore + Query into one type
- **Pin bypass** — unpinned `executeOrder` / settle inside a cycle
- **Flag collapse** — single `isSim` replacing `simulation` + `dryRun`
- **Layer leak** — Kraken or SQL from `view/**` or math from `controller`
- **Second channel** — JS `EventSource`/WebSocket beside HTMX SSE
- **Pure-core poison** — injecting `Database` / `HttpClient` into
  `RebalancerEngine`
- **Abstract factory theater** — new generic base types with one impl
- **Exclusion green** — JaCoCo/Karma exclusion growth without tests
- **Cancellation swallow** — `catch (Exception)` without rethrowing
  `CancellationException`
````

---

### [ACO-SIBLING-1] P3 — Progressive disclosure via `architecture-patterns.md`

- Skill: package layout under `autonomous-code-optimizer/`
- Gap: Full pattern catalog + principles + layer graph will bloat SKILL.md.
- Why: skill-reviewer progressive-disclosure rule (~500 lines).

Recommendation: keep SKILL.md = stance + 4-pass loop + short checklists +
links; move long pattern catalog + principles examples to
`architecture-patterns.md`. Pass 3 starts with “read sibling.”

---

### Suggested apply order (this section)

1. `ACO-STANCE-1` + `ACO-PRINCIPLES-1` (orientation + P0 safety design)
2. `ACO-PATTERNS-1` (+ `ACO-SIBLING-1` if length warrants)
3. `ACO-LAYER-1` + `ACO-PASS3-REWRITE-1` + `ACO-ANTI-1`
4. `ACO-EXTRACT-1` + `ACO-DI-1` + `ACO-KOTLIN-1`
5. Reconcile older checklist drafts (`ACO-PASS2-1`, `ACO-FORBID-1`) so they
   don’t duplicate — principles reference checklists, checklists don’t restate
   essays.
