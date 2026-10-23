# Account history restoration — implementation handoff

Prepared 2026-10-21. This is the replacement plan for historical account reporting.

## 1. Objective and authority

Deliver useful Kraken account history: imported trades and transactions, historical asset balances, and daily USD account value for at least the latest 365 completed UTC days where exchange evidence supports it. The application must explain any specific missing evidence and automatically keep history current. A chart starting when this installation started is not completion.

The user explicitly requested importing Kraken history to see “prior balance history and stuff,” distinguished that from supporting old application databases, and wanted the previous Dashboard, trade/transaction history, and value over time. Keep quality-score allocation and fixed configured cash targets. Remove Buy & Hold comparison from scope.

This plan replaces the restrictions in the root `implementation-handoff.md` that prohibit retrospective account valuation and make recorded managed-asset scopes the primary History experience. Do not use those restrictions to reduce this assignment to captions or recent observations. Update conflicting product documentation during implementation. Do not alter agent skills automatically; report any conflicting skill instruction and apply the user's newer functional requirement.

The requested outcome is established. The detailed technical approach below is the recommended execution contract; its evidence assumptions must pass milestone A. Do not silently change account scope, pricing policy, or completion criteria to obtain green results.

## 2. Evidence and starting state

Verified in this conversation:

- Production exchange history displayed trades and ledgers from 2019 through October 2026, with imports complete.
- Supplied archive: `/Users/charlesv/Downloads/rebalancer-data-v1.zip`.
- Its History database schema is v4. Database initialized October 17, 2026 at 05:32:10 UTC; first observation is 05:32:13 UTC.
- It has 11,604 direct observations in one managed-asset scope. Daily values, daily asset values, and daily prices tables are empty.
- The long-running backfill imported exchange records; it did not produce historical account-value charts. The exact cause of its three-hour duration has not been established from timing logs.
- Local checkout was `main` at `f4dadbbf`; locally known `origin/main` was `f2ebc7e7`, including #424 and subsequent database fixes. Refresh remote state before work; do not implement against the stale local checkout.
- Preserve existing untracked `implementation-handoff.md`, `dashboard-current.png`, and `history-current.png`.

Use an isolated branch/worktree from current remote main. Inspect applicable repository guidance. Do not run a copied production configuration with trading enabled. Use the database copy offline, with no authenticated exchange gateway or order executor in the diagnostic runner. Do not modify production, reset its database, rescan it, deploy, or merge. Publishing a PR follows the user's separate authorization and repository release workflow.

## 3. Fixed product behavior

### History layout

Keep Dashboard / History / Settings navigation. History has four tabs: **Account value**, **Trades**, **Transactions**, **Data status**. Existing links using `tab=portfolio` redirect/resolve to Account value; `tab=data` remains valid. Preserve existing trade/transaction filters, paging, CSV export, saved views, and chart interactions.

Default Account value range: 30 days. Provide 24h, 7d, 30d, 90d, 1y, All, and custom dates. Display UTC explicitly for daily marks. All shows the full available history and separately reports the value coverage; it must not imply all transaction years have been priced.

Main controls: date range, optional asset filter, Reset zoom, export. Remove recorded asset-set selection from the normal workflow. Move raw account digests, source identifiers, cursors, observation counts, and recording scope details into a collapsed technical-details section. Display BTC, never XXBT, in ordinary UI; retain raw identifiers in evidence storage.

Main chart: **Account value (USD)**. Under it: value at latest available mark, change in account value over the selected range, and a sentence that deposits and withdrawals affect that change. Do not label this profit, investment return, or strategy performance. Do not calculate a percentage from a missing/zero baseline. Display the actual baseline date if the selected start lacks a valid value.

Below chart: **Holdings at [date]**, selected by clicking a daily point or choosing a date. Columns: asset, quantity, historical USD price, USD value, availability. Include assets held then even if no longer configured or held today. Provide CSV for daily account totals and selected-date holdings, including missing-data status.

Use daily historical values for 7d and longer ranges. For 24h, use full-account direct observations captured during account cycles. Never splice existing managed-asset-only observations into account totals or substitute a daily close when full-account intraday samples are unavailable; show an explicit empty state until a valid full-account sample exists. Preserve legacy managed-asset charts in technical/recorded-history details where useful, without making them the default.

Drag-to-zoom, tooltips, reset, and responsive sizing must work. Refresh must preserve selected tab, dates, asset filter, zoom and table position. Relative windows advance only while following latest; zooming pauses that behavior and exposes **Follow latest**. Custom historical windows stay fixed.

### Account scope

Use the authenticated Kraken account's spot balances plus identifiable staking/Earn balance buckets represented by its ledger history; include USD and non-configured assets. Exclude separate derivatives/margin-position valuations from this release. Milestone A must detect whether the supplied account actually needs unsupported wallet/product coverage. If it does, do not call a partial total “Account value”; report the specific scope blocker to the user before finalizing that design.

**User clarification (2026-10-21): exclude all stock share balances identified by Kraken as `assetClass=equity` from account-value totals, holdings, and rebalancer inputs. STRC is the currently held example; it is a real stock, not a tokenized portfolio asset. Include all USD cash ledger balance changes, including stock-trade settlement entries; USD dividend credits are ordinary cash deposits. Do not price or display equity share units as portfolio holdings.** The supplied archive confirms STRC dividend entries are `ZUSD`; preserve raw equity rows for transaction evidence without including equity units in derived value.

Track raw asset/bucket identities for accounting. Canonical labels are display mappings, not permission to collapse distinct balances. Internal transfers between included buckets must not double count value or appear as external funding. A token migration or redenomination requires documented identity/conversion evidence; do not strip suffixes or equate similarly named assets by guesswork.

### Historical prices and balances

Daily mark means the state immediately before the next UTC midnight, using that day's completed daily candle. Store the boundary instant and explicit day label. An event exactly at midnight belongs to the next day. Use decimal arithmetic, preserving source precision; round only for display.

Retain direct observations unchanged. Historical daily values are derived estimates using exchange balances and market closes, labeled **Daily value · historical closing prices**. They are not exchange-certified statements or tax valuations.

## 4. Data status — exact required redesign

The current data status is too technical and conflates import, valuation, and ongoing refresh. Replace it with one concise summary and three stage rows. No wall of diagnostic cards.

Top summary examples:

- **Preparing account history — importing older transactions**
- **Preparing account history — calculating daily values**
- **History ready through October 20 · checking for new activity automatically**
- **History partially available · 12 dates need attention**
- **History paused · Kraken rate limit; retrying at 20:14:30**

Below summary show **Requested value period**, **Account values available**, and **Last successful update**. Readiness applies to the requested/frozen historical target, not every possible date since account inception. Ready requires valid values for all eligible dates in that target; true pre-account dates are explicitly not applicable.

Stage rows, in this order:

| Stage | Visible progress | Completion meaning |
| --- | --- | --- |
| 1. Import exchange records | Trades: imported count and covered dates. Transactions: imported count and covered dates. While running, current scan window and last progress time. | All required pages for the frozen target and required opening-balance evidence are committed; no interior coverage gaps. |
| 2. Establish daily balances | Days processed / target days; valid days; days missing balance evidence. | Every target day evaluated, with valid or explicit blocked status. |
| 3. Obtain prices and calculate values | Required asset-days priced / required asset-days; complete daily totals / target days; blocked days. | Every target day evaluated; readiness separately requires complete totals. |

For an import whose API does not provide a stable total, show **[N] records imported · total not provided by Kraken**, not a percentage. Counts alone are not completion evidence. A page's raw result count is not a global total. Freeze the target period for each job; never let new live trades continually enlarge the initial job denominator.

Use percentage bars only for known, stable denominators. If required price tasks are still being discovered, say **Determining required prices**, then freeze that generation's denominator. Do not invent an ETA. Show elapsed time, last progress time, and scheduled retry time when applicable. A cursor belongs only under **Technical details**.

Historical job state and live-refresh state are separate. Once historical import is complete, a recent trade check must not turn its row back to Running. Show a small separate line **Checking for new activity…** and retain **Historical import complete**. Newly discovered older records trigger a clearly labeled **Updating affected historical dates**, not a silent full restart.

Technical details contain feed/job IDs, raw cursors, verified coverage intervals, retries and sanitized errors. A **Why is this incomplete?** disclosure lists specific assets/dates and reasons with next action. Unsupported/missing evidence must settle into Needs attention, not an endless spinner.

Normal operation is automatic. Provide **Retry failed work** for failed/blocked fetch work when retry can help; it resumes checkpoints and does not delete data. Hide full rescan under technical details, explain its cost, and do not invoke it automatically on page load. Corrected parsing/policy should invalidate affected derived rows without reimporting already intact raw records.

Progress reflects committed work. After restart it resumes from durable checkpoints. Example fixtures must cover importing, computing, waiting for rate limit, partially available, complete plus refreshing, failure, and unknown import total.

## 5. Accounting algorithm and evidence gates

### Milestone A — prove feasibility on the supplied database before building UI

Create a local diagnostic command/test harness that reads a disposable copy. Produce a private report, not a committed dump of the account. Inventory ledger time bounds, covered intervals, invalid fields, asset/bucket identities, event types, checkpoint availability, restricted query dates and ambiguous timestamps. Determine all assets held during the latest 365 completed days, including sold-out assets and Earn variants.

For each balance stream, validate checkpoint transitions against source event amount/fee semantics. Confirm those semantics from current official Kraken documentation and actual rows; do not assume trade cost is a balance delta. Test the relationship between consecutive ledger post-balances and events. Establish deterministic same-timestamp ordering from balance continuity where possible; lexical transaction IDs alone are not chronological proof. Ambiguity makes the affected boundary unavailable.

Select at least five dates: oldest requested day, middle day, deposit/withdrawal day, multi-trade day, and recent overlap day. Derive quantities independently and compare to ledger checkpoints; compare like-for-like assets with recent direct observations only at compatible timestamps and scope. A managed-only observation is not an independent full-account reconciliation.

Inventory required price pairs/dates and probe official public price availability. Public market requests need no production credentials. Record asset-day holes, not just successful BTC/ETH examples. This milestone passes only if the proposed algorithm has demonstrable balance semantics and a concrete price source for the target holdings, or narrowly documented external data gaps. Do not proceed to claim a complete year if this audit disproves feasibility. Report the smallest blocker with evidence; continue independent fixes, but do not hide the blocker behind UI polish.

### Balance derivation

1. Process verified account records in bounded pages; deduplicate by account plus native ledger ID. Use ledger records as the balance authority; do not apply matching trades a second time.
2. Establish each raw balance stream from a valid checkpoint. Compute pre-event balances from documented net delta when needed. Carry a quantity across time only when verified ledger coverage establishes no missing intervening changes.
3. Support opening balances from checkpoints before the requested range, or reverse from a later checkpoint across a completely covered interval. Never assume the first row in a restricted API window implies zero before it. Zero requires affirmative boundary/coverage evidence.
4. Reconcile derived quantities at every subsequent usable checkpoint. An unexplained mismatch invalidates the affected interval until a trustworthy anchor reestablishes the balance; it must not poison unrelated dates forever or silently reset a continuous line.
5. Determine the asset universe from historical account evidence, not Settings allocations. Unknown balance is not zero. Invalid timestamp records must be surfaced and scoped conservatively; do not silently discard them and claim full coverage.
6. Persist daily quantities and evidence references. Quantity history remains usable even when prices are unavailable.

### Price policy

Use Kraken completed 1440-minute OHLC candles for direct asset/USD markets as the first source. Kraken documents a maximum of 720 recent candles, regardless of `since`; do not loop `since` expecting older pages. Exclude the current uncommitted candle.

USD is exactly USD 1. Other fiat and stablecoins require historical conversion. If direct USD is unavailable, allow a two-leg conversion through BTC, then EUR, in that deterministic order, using completed candles for the same UTC day, and retain both legs. These are daily close estimates; do not imply simultaneous tick execution prices. No current ticker fallback, future candle, arbitrary stale-price carry, stablecoin-at-par shortcut, or zero price for missing data.

Only fetch prices for days with known nonzero holdings. Known zero quantities need no price. Persist valid prices to avoid repeat downloads. Missing/delisted pairs remain explicit. If the last-year audit finds price holes that require another source, stop that pricing decision and present exact assets/dates; do not silently add an unrelated vendor or fabricate values. Beyond the primary year, use already available verified candles; do not make all-time price completeness a prerequisite for the one-year release.

A daily total exists only when all included holdings have known quantities and every nonzero quantity has a valid price. Otherwise total is null with reasons. Display valid dates and explicit gaps; never plot a known subtotal as the total. Expose per-asset usable balances even on incomplete days. Do not introduce B&H, tax lots, TWR, IRR, or inception-performance inference.

Official references to verify during implementation:

- [Kraken ledger information](https://docs.kraken.com/api-reference/account-data/get-ledgers-info)
- [Kraken OHLC data](https://docs.kraken.com/api-reference/market-data/get-ohlc-data)
- [Kraken trading-history support article](https://support.kraken.com/in/articles/360000912103-how-to-retrieve-your-account-s-trading-history)

## 6. Components, interfaces and implementation order

Paths below are repository-relative and verified against locally known origin/main. New filenames are explicitly marked. Adapt only for actual upstream renames; preserve these ownership boundaries.

### A. Evidence audit and contract tests

Existing: `backend/src/main/kotlin/com/gemini/krakenbot/model/ExchangeHistoryPage.kt`, `service/impl/KrakenParsers.kt`, `service/history/HistoryModels.kt`, `HistoryStore.kt`, `HistorySyncCoverage.kt`.

New under backend History tests: an offline account-history audit harness and synthetic fixtures derived from demonstrated defect classes. No private production rows in Git. Output a private feasibility report with pass/fail per evidence gate. Verify fee semantics, balance buckets, same-time events and opening balances before implementing the calculator.

### B. Durable derived history

Existing under `backend/src/main/kotlin/com/gemini/krakenbot/service/history/`: `HistoryDatabase.kt`, `HistoryModels.kt`, `HistoryStore.kt`, `SqliteHistoryStore.kt`, `HistoryStoreCodec.kt`.

Reuse `HistoryDailyValue`, `HistoryDailyAssetValue`, `HistoryDailyPrice` and existing daily tables where their contracts fit. Do not add a parallel database. Add explicit account scope/version, calculation policy version, source revision, build generation and provenance references as needed. Add durable valuation job/progress state separate from exchange jobs.

Logical interfaces: query bounded balance inputs including opening anchors; query/save daily balances; query/save prices; atomically publish a completed generation of daily values plus its revision; query progress and blocked reasons. Queries must paginate beyond the existing 100,000-input guard rather than silently truncate. Keep financial values as decimal strings/BigDecimal.

Migrate existing v4 stores additively and transactionally, choosing the next unused version after checking latest main. Preserve observations, imported records, account identity and sync cursors. Existing empty daily tables are valid. Do not require fresh database or reimport. Test migration on a disposable copy of the supplied database and synthetic older schemas.

Rollback: stop the app, restore the pre-upgrade consistent backup, and run the old binary. Do not promise old binaries can read new schema. Never copy a live SQLite main file without its WAL/consistent-backup procedure.

### C. Calculation and pricing services

New cohesive services in the same History package: `HistoryDailyBalanceBuilder.kt`, `HistoryDailyPriceResolver.kt`, `HistoryDailyValueBuilder.kt`. Pure balance/value calculation must be testable without network or persistence. Price resolver uses existing Kraken public gateway/rate limiter; no new signing path.

Add only the public OHLC gateway capability actually missing after inspecting current source. Integrate DI in `backend/src/main/kotlin/com/gemini/krakenbot/config/AppModule.kt`. New workers are lifecycle-owned, cancellable, and isolated from execution. Never run replay in the portfolio cycle or hold a DB transaction while fetching prices.

### D. Background scheduling and invalidation

Existing: `HistorySynchronizer.kt`, `HistorySyncCoordinator.kt`, `HistoryService.kt`, `HistoryObservationRecorder.kt`. New: `HistoryValuationCoordinator.kt` if required to keep exchange paging separate from derived work.

On startup, reuse existing committed import coverage. Schedule latest 365 completed days first, then additional available dates. Coalesce wakeups after committed ledger pages; compute only intervals with adequate coverage. Freeze each job target. One valuation worker per account; bounded network concurrency using existing public/private limits. Recent sync retains priority over old import, and trading must retain its existing rate-limit protections.

A late/corrected ledger invalidates quantities from its event time until the next validated anchor (or onward if no anchor), and dependent prices/totals. Policy/mapping changes invalidate affected derived generations. Raw exchange records remain. Avoid invalidating a finished historical build just because a new local observation increments a global revision: use relevant ledger/price revisions.

Jobs survive restart and are idempotent. Publish new results atomically per bounded generation; reject publication if its relevant source revision changed. Do not mix totals and holdings from different generations. Completed dates stay queryable during recomputation with an updating label. Permanent gaps become Needs attention; network failures use bounded retries/backoff and a scheduled retry display.

### E. API and reporting

Existing: `HistoryApiModels.kt`, `HistoryApiPresenter.kt`, `HistoryApiSeriesBuilder.kt`, `HistoryCsvExporter.kt`, `HistoryRange.kt`, `service/reporting/HistoryReportingService.kt`, and existing Dashboard History route owners.

Extend existing chart/status routes rather than create a second reporting API. Response contract includes account scope, selected interval, available value interval, resolution, valuation basis, generation/revision, points with nullable totals/status, and structured reasons. Status includes frozen target, each stage's durable state, processed/total where known, ready/blocked days, last progress, last success and next retry. Holdings-at-date returns the same published generation as its total.

Server page, chart API, summary and export share range and valuation rules. Use half-open ranges consistently. Date-only inclusive end selection maps to next midnight exclusive. Never silently limit longer charts to the latest page. Preserve null gaps during downsampling; daily one-year data should not require downsampling.

### F. UI implementation

Existing: `backend/.../view/component/HistoryPageComponent.kt`, `view/css/HistoryStyles.kt`; `frontend-js/src/jsMain/kotlin/History.kt`, `HistoryViews.kt`, `HistoryZoom.kt`; shared catalogs in `common/src/commonMain/resources/codegen/`.

Implement sections 3 and 4 exactly. Use existing typography/components, restrained spacing and one main chart. Do not redesign Dashboard or Settings. Poll the lightweight existing revision/status mechanism every 5 seconds while visible (or reuse equivalent existing push); refresh data only when relevant revision changes. No overlapping requests, cancel/discard stale range responses, back off after failure, suspend hidden-page polling, refresh on return. Update progress without rebuilding chart or resetting user state.

Build a network-disabled browser fixture using production-shaped synthetic history spanning more than a year and several asset-set changes. Verify data-status states and user interactions before final screenshots.

### G. Documentation and release evidence

Update README, USER_GUIDE, FLOWS, EVALUATION, ALGORITHM reporting section, CHANGELOG, and the old handoff's obsolete status by adding a supersession notice rather than deleting historical context. Explain daily close estimates, account scope, progress meanings, gaps, automatic updates, and migration/rollback. Do not claim complete historical value simply because exchange import is complete.

## 7. Required verification and release gate

Each criterion gets PASS / FAIL / NOT VERIFIED plus concrete evidence. Green build alone is insufficient.

1. **One-year outcome:** selecting 1y produces daily account history extending before October 17 from the copied account evidence, or documents exact external blockers. Fewer dates because the implementation still reads only observations is FAIL. External blockers make the complete-year outcome BLOCKED, not PASS.
2. **Independent balances:** five milestone-A dates reconcile with source checkpoints. Include sold-out assets, fees, deposit/withdrawal, internal transfer, Earn bucket and same-timestamp cases where present.
3. **Price correctness:** no current/future/uncommitted price substitution; USD only at par; explicit missing direct/conversion prices; zero holdings do not require prices.
4. **Coverage correctness:** missing opening anchor, restricted API window, interior gap, ambiguous ordering and malformed ledger cannot yield fabricated zero/complete totals. Later valid intervals remain recoverable.
5. **Migration:** copied v4 database upgrades without loss; no fresh DB or full exchange reimport; old observation counts and exchange IDs preserved; restart succeeds.
6. **Progress:** complete historical import stays complete during recent refresh; unknown totals show no percentage; progress survives restart; retry resumes; blocked work stops spinning; completed/blocked counts match rows.
7. **Late data:** insert a new/late ledger through the fixture sync boundary; affected daily totals, holdings and chart update automatically within 10 seconds of publication, with no manual refresh and no duplicate rows.
8. **Chart UX:** 24h/7d/30d/90d/1y/All/custom, drag zoom, reset, asset filter, saved views, table pagination and CSV agree. New data preserves zoom and selected historical window. Configured asset changes do not erase account history.
9. **Scope isolation:** two accounts and simulation cannot share records/jobs/prices incorrectly or display each other's totals. Scope change cancels pending work and rejects stale responses.
10. **Failure recovery:** rate-limit wait, restart mid-page/mid-generation, corrected source data, SQLite contention and network outage preserve committed results and give actionable status. No network work in DB transactions.
11. **Responsiveness:** warm History page/chart/status reads on the supplied-sized copied DB complete within 1 second p95 over 20 sequential requests on the test machine while backfill runs. Report hardware and actual times. No HTTP request waits for the backfill to finish; no trading-cycle dependency on history computations.
12. **Visual evidence:** read laptop and phone screenshots for ready, importing and blocked states. No raw scope strings or cursors in default view; no horizontal clipping; clear distinction between record import and chart readiness.
13. **Quality:** focused tests for each defect class, then repository pre-commit checks and `./gradlew build jacocoTestCoverageVerification spotlessCheck --rerun-tasks`. Keep existing thresholds. Use the applicable independent-review workflow before authorized PR publication. Report hosted CI separately from local checks.

Use synthetic committed fixtures for repeatable tests, and a private production-copy report for real-data evidence. Never commit the ZIP, production balances, ledger IDs, raw JSON, credentials or private screenshots to a public repository. Public price fixtures may be cached with provenance.

## 8. Completion report and escalation rules

Deliver code, migrations, updated documentation, tests, screenshots, and a concise verification matrix. Report the exact available account-value dates, complete/blocked day counts, remaining asset-price gaps, whether authenticated runtime sync was actually tested, and whether production was touched (it should not be).

Do not spend another large cycle implementing broad retrospective performance analytics. The bounded feature is historical quantities plus daily market value. Prefer a verifiable working vertical slice before visual polish; do not declare the assignment complete at that slice.

Escalate only concrete blockers: missing exchange evidence, unsupported wallet semantics, absent historical prices needing a new source, or a requirement conflict. State exact missing inputs and the smallest decision needed. Do not ask the user to choose architecture, chart libraries, database reset, or internal class names. Do not request legacy application databases as a prerequisite; the provided exchange-history store is the primary input.

## 9. Kickoff prompt

Implement `docs/plans/account-history-restoration-plan.md` as the replacement contract for History. The user's goal is imported historical balances and useful account-value charts covering at least a year, not only values recorded since installation. The existing import took hours but daily-value tables remain empty; reuse its records rather than restarting it. The Data status page must clearly distinguish importing records, establishing balances, calculating values, and routine refresh, with honest durable progress.

Start from current remote main in an isolated worktree; preserve existing work. First complete milestone A against an offline disposable copy of `/Users/charlesv/Downloads/rebalancer-data-v1.zip`. Prove balance semantics and price coverage before UI polish. Then implement the full plan and validate it in a network-disabled fixture app and against the copied database. Read the plan's account scope, pricing policy, migration requirements, and acceptance gates carefully. Do not resurrect B&H, change trading math, fabricate missing values, modify production, or claim completion from green tests alone. If external evidence blocks a complete year, report exact assets/dates and continue only independent authorized work. Deliver the verification matrix and a reviewable implementation; do not merge or deploy.
