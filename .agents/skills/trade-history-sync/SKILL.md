---
name: trade-history-sync
description: >-
  Trade history sync — TradeHistorySyncService (via TradeHistoryService façade),
  TradeDeduplicator (pair aliases, local-estimate vs API, fee diffs; 5min scan /
  10s reconcile / 300s sync overlap), sync metadata offsets, dry-run vs live
  reconcile, and simulation seed ~15 days. Use when changing history sync,
  dedupe, or snapshot seeding.
---

# Trade History Sync

Primary types: `TradeHistoryService` façade → `TradeHistorySyncService` /
`TradeHistorySnapshotStore` / `TradeHistoryQueryService` /
`TradeHistoryReconstructionService` under `service/impl/history/`; also
`TradeDeduplicator`, `SnapshotHistoryCalculator`, `SqliteTradeRepositoryImpl`.

## Sync behavior

- Throttle: minimum **300s** between Kraken sync runs.
- Full vs incremental is driven by the **effective watermark** (not
  `isHistorySeeded`):
  - `effectiveLatest = latestTradeTime ?: watermarkInstant` — **only**
    null-coalesce, never `max()` the two.
  - `latestTradeTime` includes only `success = true` and `dryRun = false` rows;
    failed attempts and dry-run estimates never advance the exchange cursor.
  - `sync_watermark_epoch_sec` is written after every successful sync so
    dry-run-only accounts still advance.
  - Both null → full history (`startSec` null).
  - otherwise → incremental from effective latest minus **300s** overlap so
    fills near the previous watermark are re-fetched and reconciled.
  - Anti-pattern: `max(latestTradeTime, watermark)` — shrinks overlap below the
    latest fill and skips unreconciled local estimates.
- Within one sync pass, dedupe API rows with `apiFillIdentityKey`:
  `timestamp|pair|side|volume|usdAmount|fee|orderTxid`.
  Include economics **and** timestamp — one AddOrder can produce multiple fill
  legs sharing an `orderTxid`.
  Skip rows whose key is already in `seenApiFillKeys` (newest-first pagination
  overlap).
- `isHistorySeeded` / `history_seeded` only gates progress metadata writes
  (`sync_offset` / `sync_total`) and first-sync completion marking.
- Paginated cold Flow, page size **50**.
- Skip live Kraken sync when `!simulation && !hasValidCredentials()`.
- A real sync brackets pagination with a `ConfigService` execution session in
  addition to `withStableBackend`; settings and credentials captured at entry
  remain published until all pages finish. Throttled and invalid-credential
  no-ops do not open a session, and cancellation/failure closes it in `finally`.
- Reconciliation candidates must be effective local estimates (explicit
  `LOCAL_ESTIMATE` or legacy inferred estimate shape). Persisted `API_FILL`
  rows are never overwritten; an exact persisted API-fill identity is treated
  as already synchronized.
- When the API fill and a local estimate both have nonblank `orderTxid`, an
  exact ID match wins before newest-first economics heuristics. ID-less and
  legacy rows retain the tolerance fallback, but conflicting nonblank IDs never
  reconcile.
- Rows with `submissionState` (`PENDING` / `UNCERTAIN`) are unresolved live
  intents, not reconciliation candidates. They are also excluded from duplicate
  cleanup and age-based trade pruning. Never infer rejection from an empty
  history response or clear them automatically.
- Kraken's response-entry trade ID is persisted separately from `ordertxid` and
  is the primary identity for a fill. Only legacy rows without that ID use the
  complete economics fingerprint (timestamp, pair, side, volume, USD, price,
  fee, and order transaction ID).
- Ambiguous source-less historical rows (`success`, live, no error, no
  slippage) migrate to `LEGACY_UNKNOWN`, not `API_FILL`. They are preserved;
  only an exact conservative fingerprint prevents re-importing the same fill.
- Progress keys (`SyncMetadataKeys`): `sync_offset`, `sync_total`,
  `history_seeded`, `sync_watermark_epoch_sec` — stored in
  `history_sync_metadata`.

## TradeDeduplicator

Window: **300_000 ms** (5 minutes).

| Rule | Behavior |
| :--- | :--- |
| Pair alias | Same symbol+side+volume, **different** pair strings (e.g. `XBTUSD` vs `XXBTZUSD`) |
| Local estimate vs API | Same symbol+side+pair within **10_000 ms**; volume/USD within **1%**; treat as duplicate only if fee-rate differs by ≥ **0.001** (0.1 pp). Prefer deleting the row with `TradeSource.LOCAL_ESTIMATE` (or legacy inferred estimate) when paired with `API_FILL`. |
| Cleanup | Later record IDs deleted via `cleanupDuplicateTrades()`; a row already selected for deletion cannot anchor another comparison |

## Reconcile dry-run vs live

- `isMatchingApiTrade` returns **false** when the local row is `dryRun=true`
  — dry-run estimates never hit the exchange, so sync must not rewrite them
  into live `API_FILL` rows (API fill is inserted as a new trade instead).
- Non-dry-run local estimates may still reconcile with later API fills —
  dedupe prefers API records when alias/estimate rules match.
- Do not invent aggressive deletes outside `TradeDeduplicator` tolerances.

## Simulation seeding

When DB empty and `settings.simulation`:

- `seedHistoricalSnapshots()` — ~**15 days** back, step every **6 hours**.
- `SimulatedKrakenService.seedSimulatedTrades()` — ~**15** trades over ~**5** days.

`SnapshotHistoryCalculator` reconstructs timelines (trades + daily closes).
Pruning / daily-close span uses `HISTORICAL_DAYS_BACK` (**90**); the OHLC
fetch in `TradeHistoryReconstructionService.reconstructHistoricalSnapshots`
uses **95** days so daily closes cover the full reconstruction window.

## Auto snapshot reconstruction

- After sync, when `!simulation && totalTrades > 0 && snapshots.size <= 1`, call
  `TradeHistoryReconstructionService.reconstructHistoricalSnapshots()`
  (OHLC ~95 days; prune span 90 days).
- Simulation seeding is separate (~15 days of snapshots / ~15 fills) and only
  when the DB is empty with `simulation = true`.
- Reconcile updates preserve local `cycleId`, prefer API `orderTxid`, and retain
  `expectedPrice` for slippage recompute.

## Live stream

After sync/rebalance, snapshots emit on `TradeHistorySnapshotStore.snapshotFlow`
for SSE
`/api/status/stream`.

## Checklist

- [ ] Dedupe window/tolerances unchanged unless intentional + tested
- [ ] Sync metadata keys consistent with `:common` `SyncMetadataKeys`
- [ ] Simulation seed durations documented if changed
- [ ] dryRun/live reconcile does not drop legitimate distinct fills
- [ ] Reconcile preserves local `cycleId` and prefers API `orderTxid` when present
- [ ] Unresolved submission rows are not reconciled, deduplicated, or pruned
