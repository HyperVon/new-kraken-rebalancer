# Issue 146 implementation plan: Rebalancer vs Buy & Hold

## Document status

- Issue: [#146 — Add Rebalancer vs Buy & Hold comparison to History](https://github.com/HyperVon/new-kraken-rebalancer/issues/146)
- Prepared against `main` commit `ace7ef817cf021266a9aed3ae88a1e94cb1b3069`
- Repository state when prepared: `git pull --ff-only` reported `Already up to date.`
- Issue state when prepared: open, labelled `enhancement`, no comments
- Purpose: implementation handoff for one agent or a coordinated squad
- Scope of this document: design and implementation guidance only; the feature is not implemented here

## Outcome to deliver

Add a first-class **Rebalancer vs Buy & Hold** chart near the top of History.
For every selected History range, the server must anchor both series to the
first stored portfolio snapshot in that range and calculate the counterfactual
with JVM `BigDecimal` arithmetic. The UI must show:

- legend labels exactly **Rebalancer** and **Buy & Hold**;
- a line for the stored portfolio value and a line for the counterfactual;
- the latest difference in USD and percent;
- both values and their USD difference in each tooltip;
- the existing range, zoom, reset, saved-view, and scrubber behavior;
- an explicit unavailable state when the period cannot be compared safely.

The implementation must fail closed. A chart that omits an unexplained deposit,
withdrawal, transfer, asset-universe change, or required price is worse than no
chart.

## Recommended architecture

Calculate the comparison on the JVM and expose a dedicated ranged endpoint.
Do not calculate monetary results in Kotlin/JS. The browser may parse returned
decimal strings to `Double` only for Chart.js coordinates and formatting after
the authoritative values have already been calculated by the server.

```text
History range selection
  -> GET /api/history/comparison?range=...
  -> TradeHistoryQueryService
       -> range snapshots (maximum 300) -----+
       -> successful realized trades --------+-> RebalancerComparisonCalculator
                                                    -> trustworthy period
                                                       -> BigDecimal comparison DTO
                                                       -> Kotlin/JS chart + latest delta
                                                    -> unsupported period
                                                       -> unavailable reason DTO
                                                       -> visible unavailable state
```

This design has several important properties:

1. Money math is testable without a browser and follows project precision
   conventions.
2. Unsupported periods have a typed API contract rather than being inferred
   from missing chart points.
3. No database migration is required. Existing snapshots already store each
   asset's balance and price.
4. Range selection naturally rebuilds the baseline because the endpoint uses
   the same `range` query parameter as the other History endpoints.
5. Existing Chart.js zoom and saved-view infrastructure can include the new
   canvas by adding one shared ID.

## Product and calculation rules

### Authoritative baseline

1. Sort selected snapshots by timestamp ascending.
2. The first snapshot is the baseline. The baseline is the first **available
   stored snapshot**, not an interpolated value at the exact range boundary.
3. Copy its complete asset balances. These quantities are the Buy & Hold
   portfolio for the entire selected period.
4. The first Rebalancer and Buy & Hold values must be identical.
5. Changing `24h`, `7d`, `30d`, `90d`, or `All` must request and calculate a new
   comparison. Never retain the prior range's balances.

For a snapshot at time `t`:

```text
BuyAndHoldValue(t) = sum(startBalance(asset) * snapshotPrice(asset, t))
DifferenceUSD(t)   = RebalancerValue(t) - BuyAndHoldValue(t)
DifferencePct(t)   = DifferenceUSD(t) / BuyAndHoldValue(t) * 100
```

Use `RoundingMode.HALF_UP`, USD scale `PrecisionConstants.SCALE_USD`, and
percentage scale `PrecisionConstants.SCALE_PERCENT`. Sum unrounded asset
products and round the final USD value. USD always has price `1`.

At the first point, independently calculate the Buy & Hold value and verify it
agrees with `snapshot.totalValueUSD` within one cent. Emit the stored total for
both first-point series so the shared visual baseline is exact. If the
independent calculation differs by more than one cent, mark the period
unavailable rather than hiding an inconsistent snapshot.

The latest percentage uses Buy & Hold as the denominator. This answers the
operator question, "How much more or less is the rebalanced portfolio worth
than holding?" If the denominator is zero or negative, the period is
unavailable.

### Meaning of the two lines

- **Rebalancer** is `PortfolioSnapshot.totalValueUSD`. It therefore reflects
  actual balances, actual execution results, and fees already paid.
- **Buy & Hold** freezes the baseline quantities. It performs no trades and
  incurs no trading fees.
- Dry-run trade rows do not affect either line. The existing **Show Dry Run
  Trades** checkbox must continue to affect only the trade table and cumulative
  net cash flow chart.

### Availability policy

Use an all-or-nothing availability policy for version one. Do not draw a
partially valid line and do not silently skip bad points. Return an unavailable
response with no chart points and one stable reason code.

Recommended reason codes:

| Code | Trigger | Operator text |
| :--- | :--- | :--- |
| `INSUFFICIENT_SNAPSHOTS` | Fewer than two selected snapshots | Not enough history exists in this range to compare strategies. |
| `NON_POSITIVE_BASELINE` | Starting portfolio or a Buy & Hold denominator is not positive | The comparison needs a positive starting portfolio value. |
| `BASELINE_MISMATCH` | Calculated starting holdings differ from the stored starting total by more than $0.01 | Starting holdings do not reconcile with the recorded portfolio value. |
| `MISSING_PRICE` | A non-zero baseline holding has no asset row, or a non-USD price is zero or negative | A required historical asset price is missing. |
| `ASSET_UNIVERSE_CHANGED` | Snapshot asset keys differ from the baseline keys | The configured asset set changed during this range. |
| `UNSUPPORTED_TRADE` | A realized trade has an unknown side, symbol, or non-USD quote that cannot be reconciled | A recorded trade cannot be reconciled safely. |
| `UNEXPLAINED_BALANCE_CHANGE` | Observed balances differ from balances implied by recorded realized trades | A deposit, withdrawal, transfer, or incomplete trade history may exist. |

Include the first affected timestamp in the domain result and API response when
one is available. Do not expose raw account details in the operator message.

An available period should use a confidence label such as `RECONCILED`. Its UI
caption should explain that availability is based on stored snapshots and
recorded trades. A transfer that enters and leaves entirely between snapshots
can be mathematically invisible, so do not label the result "audited" or
"guaranteed."

### Balance reconciliation for cash-flow detection

The comparison must distinguish rebalancing trades from external balance
changes. For every adjacent snapshot pair `(previous, current]`:

1. Start from `previous.assets[*].balance`.
2. Apply successful, non-dry-run trades whose timestamps are strictly after
   `previous.timestamp` and at or before `current.timestamp`.
3. For a buy:
   - add `trade.volume` to the purchased asset;
   - subtract `trade.usdAmount + trade.fee` from USD.
4. For a sell:
   - subtract `trade.volume` from the sold asset;
   - add `trade.usdAmount - trade.fee` to USD.
5. Compare the expected balances with `current.assets[*].balance`.
6. Compare crypto after rounding to `SCALE_CRYPTO` and USD after rounding to
   `SCALE_USD`. A larger mismatch makes the whole response unavailable with
   `UNEXPLAINED_BALANCE_CHANGE`.

Only `success && !dryRun` rows affect expected balances. Failed trades, dry-run
estimates, and unresolved submissions do not. If duplicated or incomplete API
fills make balances fail to reconcile, fail closed; do not add a second set of
loose heuristics to this feature.

The repository down-samples snapshots to at most 300 points while preserving
both endpoints. Fetch all trades between the selected baseline and final
snapshot. Aggregating all intervening trades still allows balance reconciliation
between down-sampled snapshots.

## Domain and wire contracts

### JVM domain model

Add `src/main/kotlin/com/gemini/krakenbot/model/RebalancerComparison.kt` with
names equivalent to the following. Exact nesting is optional, but preserve the
semantics.

```kotlin
enum class ComparisonAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

enum class ComparisonConfidence {
    RECONCILED,
}

enum class ComparisonUnavailableReason {
    INSUFFICIENT_SNAPSHOTS,
    NON_POSITIVE_BASELINE,
    BASELINE_MISMATCH,
    MISSING_PRICE,
    ASSET_UNIVERSE_CHANGED,
    UNSUPPORTED_TRADE,
    UNEXPLAINED_BALANCE_CHANGE,
}

data class RebalancerComparisonPoint(
    val timestamp: Instant,
    val rebalancerValueUSD: BigDecimal,
    val buyAndHoldValueUSD: BigDecimal,
    val differenceUSD: BigDecimal,
    val differencePercent: BigDecimal,
)

data class RebalancerComparison(
    val availability: ComparisonAvailability,
    val confidence: ComparisonConfidence?,
    val baselineTimestamp: Instant?,
    val points: List<RebalancerComparisonPoint>,
    val latestDifferenceUSD: BigDecimal?,
    val latestDifferencePercent: BigDecimal?,
    val unavailableReason: ComparisonUnavailableReason?,
    val unavailableAt: Instant?,
)
```

Enforce invariants in factory helpers or `init` checks:

- available responses have at least two points, a baseline, confidence, and
  latest values, with no unavailable reason;
- unavailable responses have no points or latest values and have one reason;
- point zero has equal Rebalancer and Buy & Hold values and zero differences;
- timestamps are ascending.

### Pure calculator

Add
`src/main/kotlin/com/gemini/krakenbot/service/impl/history/RebalancerComparisonCalculator.kt`.
Keep it free of database, Ktor, and JavaScript concerns:

```kotlin
fun calculate(
    snapshots: List<PortfolioSnapshot>,
    trades: List<TradeRecord>,
): RebalancerComparison
```

Suggested internal helpers:

- `validateSnapshotOrderAndCount`
- `validateAssetUniverse`
- `validatePrices`
- `validateTrackedBalanceChanges`
- `applyRealizedTrade`
- `calculateBuyAndHoldValue`
- `calculateDifferencePercent`
- `unavailable(reason, timestamp)`

Normalize symbols through the existing `Asset` behavior or uppercase once at
the boundary. Do not create parallel Kraken-pair alias rules. If a recorded
trade cannot be mapped unambiguously to one baseline asset and USD, return
`UNSUPPORTED_TRADE`.

### Query and service layer

Add this method to `TradeHistoryService` and delegate it through
`TradeHistoryServiceImpl`:

```kotlin
suspend fun getRebalancerComparison(
    from: Instant,
    to: Instant,
): RebalancerComparison
```

Implement it in `TradeHistoryQueryService`:

1. Query `repository.getSnapshotsInRange(from, to)`.
2. Return `INSUFFICIENT_SNAPSHOTS` before querying trades if fewer than two
   snapshots exist.
3. Query trades from the first selected snapshot timestamp through the last
   selected snapshot timestamp.
4. Pass both lists to the pure calculator.

No new repository method, table, Koin binding, or database migration should be
necessary. If the calculator is a stateless `object`, `AppModule` does not need
to change. If it is injected as a class, update both `AppModule` and the
secondary `TradeHistoryServiceImpl` constructor used by tests.

### Common API DTO

Add
`common/src/commonMain/kotlin/com/gemini/krakenbot/api/RebalancerComparison.kt`.
Keep `commonMain` pure KMP: no `java.time.Instant` and no
`java.math.BigDecimal`. Follow the existing History API convention and send
timestamps and decimal values as strings.

Recommended shape:

```json
{
  "availability": "AVAILABLE",
  "confidence": "RECONCILED",
  "baselineTimestamp": "2026-07-01T00:00:00Z",
  "points": [
    {
      "timestamp": "2026-07-01T00:00:00Z",
      "rebalancerValueUSD": "100000.00",
      "buyAndHoldValueUSD": "100000.00",
      "differenceUSD": "0.00",
      "differencePercent": "0.0000"
    }
  ],
  "latestDifferenceUSD": "1842.73",
  "latestDifferencePercent": "1.6800",
  "unavailableReason": null,
  "unavailableAt": null
}
```

An unavailable response should still return HTTP 200 because it is a valid
answer for the selected range:

```json
{
  "availability": "UNAVAILABLE",
  "confidence": null,
  "baselineTimestamp": "2026-07-01T00:00:00Z",
  "points": [],
  "latestDifferenceUSD": null,
  "latestDifferencePercent": null,
  "unavailableReason": "MISSING_PRICE",
  "unavailableAt": "2026-07-12T00:00:00Z"
}
```

Add a domain-to-DTO mapper to `HistoryApiMapper.kt`. Use `toPlainString()` for
every decimal and ISO strings for every timestamp. Extend
`SerializationParityTest` so a mapper regression cannot turn decimals into
binary floating-point JSON numbers.

### Route

Add a constant to `Routes`:

```kotlin
const val API_HISTORY_COMPARISON = "/api/history/comparison"
```

Register it beside the other History routes in `DashboardController` and reuse
`parseTimeRange(call)`:

```kotlin
get(Routes.API_HISTORY_COMPARISON) {
    val (from, to) = parseTimeRange(call)
    respondJson(tradeHistoryService.getRebalancerComparison(from, to).toApiDto())
}
```

Do not change the semantics of `/api/history/snapshots`, `/trades`, or `/stats`.

## History UI implementation

### Shared UI constants

Add shared constants rather than raw strings.

`HtmlIds` should gain at least:

- `REBALANCER_COMPARISON_CHART`
- `COMPARISON_LATEST_DIFFERENCE`
- `COMPARISON_AVAILABILITY_MESSAGE`
- optionally a wrapper ID if hiding chart content is simpler

`ViewText` should gain:

- `HISTORY_REBALANCER_VS_BUY_AND_HOLD = "Rebalancer vs Buy & Hold"`
- `REBALANCER = "Rebalancer"`
- `BUY_AND_HOLD = "Buy & Hold"`
- a concise chart caption explaining the frozen starting quantities;
- the unavailable messages listed above;

`CssClass.History` should gain semantic classes for the comparison header,
latest-difference chip, positive/negative/neutral states, and unavailable box.
Reuse `CssTheme` colors and glass tokens; do not embed one-off `rgba` literals.

### Server-rendered markup

Update `HistoryPageComponent` to render the new card immediately after the six
summary cards and before **Portfolio Value Over Time**, matching the mockup's
information hierarchy.

The comparison needs more header content than the current generic
`HistoryChartSection`. Prefer a focused `renderComparisonChartSection()` over
making every existing chart definition understand latest-delta and unavailable
states. The card should contain:

1. the exact title;
2. the latest-difference element, initially an em dash;
3. existing Zoom minus, Zoom plus, and Reset buttons targeting the new canvas;
4. the canvas;
5. an unavailable message container that Kotlin/JS can reveal instead of the
   canvas;
6. the trust caption;
7. an ordinary History scrubber targeting the new canvas.

Keep a single compact header row at laptop widths. The delta chip and zoom tools
may wrap at narrow widths, but the title must remain readable. Preserve
`brandWithMode(settings)`, cache-busted assets, and all existing History markup.

### Kotlin/JS loading and parsing

In `HistoryLoading.kt`, fetch `Routes.API_HISTORY_COMPARISON` in the same
generation-protected `Promise.all` call as snapshots, trades, and stats. Parse
and render it only if `requestGeneration` is still current. Extend every test
fetch fixture that currently resolves three ranged URLs to resolve the fourth;
otherwise tests will hang.

In `HistoryJsonParsing.kt`, add defensive parsing for the response and points.
Preserve decimal strings. An absent or unknown availability/reason must become
an unavailable result, not an available empty chart.

Add a helper such as `rebalancerComparisonToDynamic` for browser tests instead
of duplicating dynamic JSON blobs.

### Chart behavior

In `History.kt`:

1. Add the comparison canvas to `historyChartIds`. This gives saved views,
   visibility capture, reset, teardown, and zoom/scrubber behavior the new chart
   ID.
2. Implement `buildRebalancerComparisonChart(comparison)`.
3. For unavailable results, destroy any prior chart, disable/reset its scrubber,
   reset the latest chip to an em dash, hide the canvas area, and show the mapped
   operator message.
4. For available results, hide the message and create exactly two datasets.
5. Use labels exactly `ViewText.REBALANCER` and `ViewText.BUY_AND_HOLD`.
6. Use the existing primary blue style for Rebalancer and an amber dashed line
   for Buy & Hold. Keep visible hover targets.
7. Use the shared point-density helpers.
8. Format the Y axis as USD with `formatUSD`.
9. Configure the tooltip so the two normal dataset labels show both values and
   an additional callback line shows `Difference: +$...` or `Difference: -$...`
   from the server-provided point. Do not subtract JavaScript values in the
   callback.
10. Populate the latest chip from `latestDifferenceUSD` and
    `latestDifferencePercent`. Positive is green, negative is red, and zero is
    neutral. Put the sign before the dollar sign.
11. Build from scratch on every range change so the first point and latest chip
    cannot leak from the previous range.

`getClonedChartOptions()` already reattaches zoom and legend callbacks after
the JSON deep clone. Use it unchanged unless a test proves a comparison-specific
callback is lost. Never mutate the shared `chartDefaults` object.

### Saved views

Adding the canvas ID to `historyChartIds` is required. Existing stored views may
not contain the new ID. The current fallback behavior should leave unlisted
datasets visible; preserve that compatibility. Add a regression test proving an
old saved view still loads and the new comparison datasets default to visible.

No built-in view must hide the comparison by default. The **Day · Total only**
preset only hides per-asset datasets in the existing Portfolio Value chart and
should not hide Rebalancer or Buy & Hold.

## File-by-file change map

| File | Required change |
| :--- | :--- |
| `src/main/kotlin/com/gemini/krakenbot/model/RebalancerComparison.kt` | New BigDecimal domain result, points, availability, confidence, and reasons. |
| `src/main/kotlin/com/gemini/krakenbot/service/impl/history/RebalancerComparisonCalculator.kt` | New pure calculation and trust validation. |
| `src/main/kotlin/com/gemini/krakenbot/service/TradeHistoryService.kt` | Add ranged comparison query. |
| `src/main/kotlin/com/gemini/krakenbot/service/impl/history/TradeHistoryQueryService.kt` | Load range data and invoke calculator. |
| `src/main/kotlin/com/gemini/krakenbot/service/impl/history/TradeHistoryServiceImpl.kt` | Delegate the new interface method. |
| `common/src/commonMain/kotlin/com/gemini/krakenbot/api/RebalancerComparison.kt` | New pure-KMP wire DTO with string decimals/timestamps. |
| `src/main/kotlin/com/gemini/krakenbot/api/HistoryApiMapper.kt` | Map domain result to wire DTO. |
| `common/src/commonMain/kotlin/com/gemini/krakenbot/view/util/Routes.kt` | Add comparison API route. |
| `common/src/commonMain/kotlin/com/gemini/krakenbot/view/util/HtmlAttrs.kt` | Add chart, latest-difference, and availability IDs. |
| `common/src/commonMain/kotlin/com/gemini/krakenbot/view/util/ViewText.kt` | Add exact labels, caption, and unavailable messages. |
| `common/src/commonMain/kotlin/com/gemini/krakenbot/view/util/CssClasses.kt` | Add semantic comparison styles. |
| `common/src/commonMain/kotlin/com/gemini/krakenbot/view/util/ChartProps.kt` | Add callback/property constants only if the new chart would otherwise use raw repeated keys. |
| `src/main/kotlin/com/gemini/krakenbot/controller/DashboardController.kt` | Register and serialize the ranged endpoint. |
| `src/main/kotlin/com/gemini/krakenbot/view/component/HistoryPageComponent.kt` | Render the comparison card before existing charts. |
| `src/main/kotlin/com/gemini/krakenbot/view/css/NavigationStyles.kt` | Style delta chip, unavailable state, and compact header. |
| `src/main/kotlin/com/gemini/krakenbot/view/css/MediaQueries.kt` | Add only the responsive adjustments proven necessary at 1280–1440 px and mobile widths. |
| `frontend-js/src/jsMain/kotlin/HistoryLoading.kt` | Fetch and rebuild the comparison with every range. |
| `frontend-js/src/jsMain/kotlin/HistoryJsonParsing.kt` | Parse the typed wire response. |
| `frontend-js/src/jsMain/kotlin/History.kt` | Render chart, tooltip, delta, unavailable state, and integrate zoom/scrubber. |
| `frontend-js/src/jsMain/kotlin/HistoryViewPrefs.kt` | Usually no logic change beyond tests; preserve missing-ID compatibility. |
| JVM and JS tests listed below | Add complete regression coverage. |
| `README.md` | Add the chart and endpoint to the History/API documentation. |
| `docs/USER_GUIDE.md` | Explain baseline, difference, availability, and screenshot. |
| `.agents/skills/docs-screenshot-refresh/scripts/targets.json` | Wait for five charts and capture the new section. |
| `docs/images/*.png` | Regenerate affected History screenshots from isolated simulation. |
| `CHANGELOG.md` | Add a dated patch release entry; never use an Unreleased section. |

## Test plan

### JVM calculator tests

Create
`src/test/kotlin/com/gemini/krakenbot/service/impl/history/RebalancerComparisonCalculatorTest.kt`
as a Kotest `StringSpec` with `IsolationMode.InstancePerTest`. Use
`shouldBeEqualComparingTo` for every `BigDecimal` assertion.

Minimum cases:

1. **Shared baseline**: first point has equal `$10,000.00` values, `$0.00`
   difference, and `0.0000%`.
2. **Outperformance**: after a recorded rebalance, Rebalancer ends above Buy &
   Hold; assert positive USD and percent.
3. **Underperformance**: Rebalancer ends below Buy & Hold; assert negative USD
   and percent.
4. **Fees included**: a realized trade reconciles only when its fee changes USD
   by the documented amount; Buy & Hold does not subtract that fee.
5. **Range rebasing**: calculating a suffix of the same snapshots uses the
   suffix's first balances and begins again at zero difference.
6. **Missing price**: a non-zero starting holding with a zero/missing later
   price returns `MISSING_PRICE` with no points.
7. **Asset added**: a later snapshot adds an asset key and returns
   `ASSET_UNIVERSE_CHANGED`.
8. **Asset removed**: a later snapshot drops an asset key and returns the same
   reason.
9. **Deposit**: observed BTC or USD increases without a realized trade and
   returns `UNEXPLAINED_BALANCE_CHANGE`.
10. **Withdrawal**: observed balance decreases without a realized trade and
    returns the same reason.
11. **Tracked buy**: asset volume and USD/fee deltas match and remain available.
12. **Tracked sell**: asset volume and USD/fee deltas match and remain available.
13. **Dry-run ignored**: a dry-run row does not reconcile a real balance change.
14. **Failed trade ignored**: a failed row does not reconcile a real balance
    change.
15. **Unsupported side/symbol**: return `UNSUPPORTED_TRADE`.
16. **Insufficient history**: zero and one snapshot both return
    `INSUFFICIENT_SNAPSHOTS`.
17. **Non-positive denominator**: return `NON_POSITIVE_BASELINE` without divide
    by zero.
18. **Baseline mismatch**: a stored total inconsistent by more than one cent
    returns `BASELINE_MISMATCH`.
19. **Rounding tolerance**: differences below the persisted crypto/USD scales do
    not create a false cash-flow failure; differences at the next representable
    unit do.
20. **Down-sampled interval shape**: several realized trades between two
    snapshots reconcile in timestamp order.

Use compact local fixture builders for snapshots and trades. Do not use a
database for the pure calculator.

### JVM service, mapper, controller, and view tests

- Extend `TradeHistoryRangeAndEdgeCasesTest` or add a focused query-service test
  to prove it fetches snapshots first, uses the selected first/last timestamps
  for trades, and delegates the calculator result.
- Extend `TradeHistoryServiceTest` coverage for the new façade delegation if
  the existing base mocks require it.
- Extend `SerializationParityTest` to assert every comparison decimal is a JSON
  string, timestamps are ISO strings, and nullable unavailable fields serialize
  consistently.
- Extend `DashboardHistoryApiTest`:
  - ranged comparison route returns HTTP 200 and mapped JSON;
  - invalid/missing `range` uses the existing defaulting behavior;
  - unavailable is HTTP 200 with reason code and empty points.
- Extend `HistoryPageComponentTest` to assert the canvas, latest-difference ID,
  unavailable container, exact title, caption, three zoom controls, and one
  scrubber are rendered.
- Update any relaxed `TradeHistoryService` mocks only where needed. Do not make
  production behavior depend on relaxed mock defaults.

### Kotlin/JS tests

Update `TestDomBuilders.chartsDom()`, `historyDom()`, and scrubber helpers to
include the comparison DOM contract.

Add or extend tests in `HistoryJsonParsingTest`,
`HistoryJsonParsingEdgeTest`, `HistoryTest`, `HistoryZoomTest`, and
`HistoryViewPrefsTest` for:

1. available and unavailable response parsing;
2. missing/unknown fields failing closed;
3. exactly two chart datasets with exact labels;
4. both series sharing the first Y value;
5. tooltip labels for Rebalancer, Buy & Hold, and Difference;
6. positive, negative, and zero latest-difference display/classes;
7. percentage text coming from the response, not client subtraction;
8. unavailable response destroying an existing chart, disabling the scrubber,
   hiding stale delta text, and showing the correct reason;
9. switching from unavailable to available restoring the chart;
10. changing range issuing the fourth endpoint request and rebuilding the
    baseline;
11. stale response generation being ignored for the fourth endpoint too;
12. comparison chart Zoom minus/plus/Reset behavior;
13. wheel/drag zoom callback enabling its scrubber;
14. scrubber pan using `chart.zoomScale('x', {min, max}, 'none')` and fallback;
15. empty teardown now covering five charts;
16. old saved views without the comparison canvas defaulting it to visible;
17. visibility capture/apply including the new canvas.

The current asynchronous History tests manually resolve all endpoint promises.
Search for every use of `API_HISTORY_SNAPSHOTS`, `Promise.all`, or
`bodyResolvers` and add the comparison response. Missing one will likely appear
as a hanging Karma test rather than a clear assertion failure.

### Manual acceptance tests

Run in a fresh isolated simulation directory, never against the operator's real
configuration or database.

1. Open History at `30d` and confirm the comparison is the first chart.
2. Confirm the legend reads exactly **Rebalancer** and **Buy & Hold**.
3. Hover first point: values match and Difference is `$0.00`.
4. Hover a later point: both values and signed Difference appear.
5. Confirm latest USD and percentage difference match the final point.
6. Change through `24h`, `7d`, `30d`, `90d`, and `All`; every available range
   starts at zero difference.
7. Exercise Zoom minus, Zoom plus, Reset, wheel zoom, drag zoom, and scrubber pan
   on the new chart.
8. Save a custom view, reload, and confirm comparison visibility persists.
9. Verify the layout at 1440×900 and approximately 1280 px wide; header controls
   must not overlap or become native white buttons.
10. Use a test fixture or isolated database mutation to create a missing price
    and an unexplained balance change; confirm no lines or stale delta remain and
    the reason is visible.
11. Confirm the **Show Dry Run Trades** checkbox does not change this chart.
12. Confirm the mode plate still says **SIMULATION** and the Dashboard stream
    chip remains **STREAM**/**STALE**, not a trading-mode label.

## Documentation and screenshots

Update documentation after behavior is final, not before.

### README

- Add the comparison chart to the History feature list.
- State that it rebases from the first stored snapshot in the selected range.
- State that unsupported periods are shown as unavailable.
- Add `GET /api/history/comparison` to the endpoint table.
- Update screenshot captions if `history.png` now primarily shows the new
  comparison.

### User guide

Add a subsection before the existing portfolio-value chart explanation:

- what Rebalancer and Buy & Hold mean;
- the frozen starting-quantity formula;
- why both start together;
- how to read positive/negative USD and percentage differences;
- why missing prices, asset changes, or unexplained balance changes make a
  range unavailable;
- the reconciliation-confidence limitation.

Keep it operator-facing. Do not duplicate calculator internals or imply that
the comparison proves future strategy performance.

### Screenshot targets

There will be five History charts after this feature. Update every affected
`await_charts` value from `4` to `5`.

Recommended capture layout:

1. Keep `history.png` as the top-of-page overview; it should now show the range,
   summary cards, and comparison chart.
2. Add `history-portfolio-charts.png`, anchored at **Portfolio Value Over Time**,
   so Portfolio Value and Asset Holdings remain visually documented after the
   new first chart pushes them below the fold.
3. Keep `history-charts.png` anchored at Allocation Deviation and
   `history-bottom.png` at Trade History.
4. Add the new image to the screenshot inventory in the screenshot skill,
   README if appropriate, and `docs/USER_GUIDE.md`.

Generate screenshots using the repository's
`docs-screenshot-refresh` workflow: isolated temporary run directory, fresh
database, `simulation: true`, canonical 1440×900 at 2× scale. Read every PNG and
verify populated continuous charts, no clipping, no credentials, and a visible
**SIMULATION** plate.

### Changelog

Add or extend a dated SemVer entry at the top of `CHANGELOG.md`. Use `### Added`
for the new comparison and `### Changed` only if separately describing the
refreshed documentation/screenshots. Never add an Unreleased heading.

## Implementation order for a single agent

Follow this order to keep each stage testable:

1. Add the JVM domain model and pure calculator.
2. Write calculator tests until baseline, performance, and fail-closed cases
   pass.
3. Add the service query and façade method.
4. Add common DTO, mapper, route, serialization tests, and controller tests.
5. Add shared IDs, labels, and CSS classes.
6. Render the comparison card and update its JVM view test.
7. Add JS parsing and parsing tests.
8. Add the fourth History fetch and immediately fix all async fixtures.
9. Build the chart, latest delta, unavailable state, and JS tests.
10. Integrate saved views, zoom, and scrubber tests.
11. Apply formatting and run focused JVM/JS tests.
12. Boot isolated simulation and complete manual UI checks.
13. Refresh and inspect screenshots.
14. Update README, User Guide, screenshot inventory, and changelog.
15. Run the full quality gates once, without concurrent Gradle processes.

## Suggested squad split

Do not split before the domain/wire contracts and unavailable policy are agreed.
After that checkpoint, the following tracks are mostly disjoint:

| Track | Ownership | Deliverable |
| :--- | :--- | :--- |
| A — calculation/API | JVM model, calculator, query/service, DTO mapper, route, JVM tests | Green authoritative comparison endpoint. |
| B — History presentation | shared view constants, `HistoryPageComponent`, CSS, view tests | Complete static card and responsive states. |
| C — browser behavior | History parsing/loading/chart/view prefs and JS tests | Interactive chart, delta, fail-closed state, zoom/scrubber. |
| D — docs/visuals | Start after A–C integrate; README, User Guide, targets, screenshots, changelog | Accurate user docs and verified images. |

Coordination rules:

- Assign one owner to shared common files such as `HtmlAttrs.kt`, `ViewText.kt`,
  and `Routes.kt`; parallel edits there will conflict.
- Track C consumes the DTO and markup contracts from A and B. Stub against the
  agreed shape, then rebase once those tracks land.
- Do not run concurrent Gradle builds in the same clone. Let tracks run focused
  tests sequentially, or use separate worktrees; run one final `--rerun-tasks`
  build after integration.
- The integrating agent owns acceptance-criteria mapping and manual UI checks.

## Verification commands

Focused iteration:

```bash
./gradlew test --tests "*RebalancerComparisonCalculatorTest" \
  --tests "*DashboardHistoryApiTest" \
  --tests "*HistoryPageComponentTest" \
  --tests "*SerializationParityTest" \
  -x jacocoTestCoverageVerification
./gradlew :frontend-js:jsBrowserTest
./gradlew spotlessApply
```

Final automated gates:

```bash
./gradlew build jacocoTestCoverageVerification --rerun-tasks
./gradlew spotlessCheck
npx markdownlint-cli .agents/AGENTS.md .agents/OPERATING.md CLAUDE.md \
  .github/copilot-instructions.md CHANGELOG.md CONTRIBUTING.md README.md \
  SECURITY.md docs/*.md .agents/skills/**/SKILL.md \
  .agents/skills/**/*.md
```

The Gradle build covers the JVM JaCoCo thresholds (95% instruction, line, and
method; 90% branch) and Kotlin/JS Karma thresholds (90% statements, functions,
and lines; 75% branches). CodeQL is currently disabled for Kotlin 2.4.x; do not
claim it as a verification result.

## Acceptance-criteria traceability

| Issue criterion | Implementation evidence |
| :--- | :--- |
| History displays the chart | `HistoryPageComponentTest`, manual top-of-page check, refreshed `history.png`. |
| Exact legend labels | Shared `ViewText` constants plus JS dataset-label assertion. |
| Range change rebuilds both series | Fourth ranged fetch, generation guard, range-rebasing calculator and JS tests. |
| Tooltip shows both values and difference | Chart callback test and manual hover check. |
| Latest currency and percentage difference | Server fields, positive/negative/zero JS tests, final-point consistency assertion. |
| Unsupported or incomplete periods identified | Typed reason codes, fail-closed calculator tests, unavailable UI-state tests. |
| JVM/Kotlin-JS coverage | Calculator/service/controller/view tests plus parsing/chart/zoom/prefs Karma tests. |
| User docs and screenshots updated | README, User Guide, five-chart targets, regenerated and inspected PNGs, changelog. |

## Pitfalls to avoid

- Do not compute Buy & Hold or percentage results with JavaScript `Double`.
- Do not use current configured allocations as the baseline; use the first
  selected snapshot's actual balances.
- Do not carry a 30-day baseline into another range.
- Do not treat buys/sells as external cash flows; reconcile their asset, USD,
  and fee effects first.
- Do not let dry-run or failed rows reconcile real balances.
- Do not silently use zero for a missing required price.
- Do not silently ignore newly added or removed assets.
- Do not show partial points before an invalid point.
- Do not return HTTP errors for a valid unavailable comparison response.
- Do not couple the comparison to the **Show Dry Run Trades** checkbox.
- Do not mutate shared Chart.js options; deep-clone and reattach functions.
- Do not forget to add the chart to `historyChartIds`; zoom, scrubber, teardown,
  and saved-view behavior depend on that list.
- Do not forget the fourth promise in stale-response and manual resolver tests.
- Do not run screenshot capture against the user's real config or database.
- Do not accept screenshots without opening and inspecting the generated PNGs.
- Do not open a PR until automated gates and manual UI/screenshot checks are
  complete.

## Definition of done

The feature is complete only when all issue acceptance criteria are demonstrably
met, every unavailable condition fails closed, both series rebase for every
range, the new chart behaves like the existing History charts under zoom and
saved views, all automated gates pass, isolated simulation QA passes at laptop
width, current screenshots are visually inspected, and user-facing documentation
accurately describes both the calculation and its trust limits.
