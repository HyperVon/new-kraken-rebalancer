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
  - Prefer `latestTradeTime` (excludes dry-run rows) so real/sim fills bound the
    window.
  - Fall back to persisted `sync_watermark_epoch_sec` (written after every
    successful sync) so a dry-run-only account does not re-pull from EPOCH.
  - Both null → full history (`startSec` null).
  - otherwise → incremental from effective latest minus **300s** overlap so
    fills near the previous watermark are re-fetched and reconciled.
- Within one sync pass, API fills are fingerprinted (timestamp/pair/side/
  volume/USD/fee/`orderTxid`) so a pagination window shift cannot
  double-insert the same fill.
- `isHistorySeeded` / `history_seeded` only gates progress metadata writes
  (`sync_offset` / `sync_total`) and first-sync completion marking.
- Paginated cold Flow, page size **50**.
- Skip live Kraken sync when `!simulation && !hasValidCredentials()`.
- Progress keys (`SyncMetadataKeys`): `sync_offset`, `sync_total`,
  `history_seeded`, `sync_watermark_epoch_sec` — stored in
  `history_sync_metadata`.

## TradeDeduplicator

Window: **300_000 ms** (5 minutes).

| Rule | Behavior |
| :--- | :--- |
| Pair alias | Same symbol+side+volume, **different** pair strings (e.g. `XBTUSD` vs `XXBTZUSD`) |
| Local estimate vs API | Same symbol+side+pair within **10_000 ms**; volume/USD within **1%**; treat as duplicate only if fee-rate differs by ≥ **0.001** (0.1 pp). Prefer deleting the row with `TradeSource.LOCAL_ESTIMATE` (or legacy inferred estimate) when paired with `API_FILL`. |
| Cleanup | Later record IDs deleted via `cleanupDuplicateTrades()` |

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
