---
name: trade-history-sync
description: >-
  Trade history sync — TradeHistoryServiceImpl, TradeDeduplicator (pair aliases,
  local-estimate vs API, fee diffs, 5min window), sync metadata offsets,
  dry-run vs live reconcile, and simulation seed ~15 days. Use when changing
  history sync, dedupe, or snapshot seeding.
---

# Trade History Sync

Primary types: `TradeHistoryServiceImpl`, `TradeDeduplicator`,
`SnapshotHistoryCalculator`, `SqliteTradeRepositoryImpl`.

## Sync behavior

- Throttle: minimum **300s** between Kraken sync runs.
- Full vs incremental is driven by **`latestTradeTime` nullity** (not
  `isHistorySeeded`):
  - `null` → full history (`startSec` null).
  - otherwise → incremental from latest trade minus **300s** overlap so
    fills near the previous watermark are re-fetched and reconciled.
- `isHistorySeeded` / `history_seeded` only gates progress metadata writes
  (`sync_offset` / `sync_total`) and first-sync completion marking.
- Paginated cold Flow, page size **50**.
- Skip live Kraken sync when `!simulation && !hasValidCredentials()`.
- Progress keys (`SyncMetadataKeys`): `sync_offset`, `sync_total`,
  `history_seeded` — stored in `history_sync_metadata`.

## TradeDeduplicator

Window: **300_000 ms** (5 minutes).

| Rule | Behavior |
| :--- | :--- |
| Pair alias | Same symbol+side+volume, **different** pair strings (e.g. `XBTUSD` vs `XXBTZUSD`) |
| Local estimate vs API | Same symbol+side+pair within **10_000 ms**; volume/USD within **1%**; treat as duplicate only if fee-rate differs by ≥ **0.001** (0.1 pp). Prefer deleting the row with `TradeSource.LOCAL_ESTIMATE` (or legacy inferred estimate) when paired with `API_FILL`. |
| Cleanup | Later record IDs deleted via `cleanupDuplicateTrades()` |

## Reconcile dry-run vs live

- Local dry-run / estimate trades may coexist with later API fills — dedupe
  prefers API records when alias/estimate rules match.
- Do not invent aggressive deletes outside `TradeDeduplicator` tolerances.

## Simulation seeding

When DB empty and `settings.simulation`:

- `seedHistoricalSnapshots()` — ~**15 days** back, step every **6 hours**.
- `SimulatedKrakenService.seedSimulatedTrades()` — ~**15** trades over ~**5** days.

`SnapshotHistoryCalculator` reconstructs timelines (trades + daily closes).
Pruning / daily-close span uses `HISTORICAL_DAYS_BACK = **90**`; the OHLC
fetch in `TradeHistoryServiceImpl.reconstructHistoricalSnapshots` uses
**95** days so daily closes cover the full reconstruction window.

## Live stream

After sync/rebalance, snapshots emit on `snapshotFlow` for SSE
`/api/status/stream`.

## Checklist

- [ ] Dedupe window/tolerances unchanged unless intentional + tested
- [ ] Sync metadata keys consistent with `:common` `SyncMetadataKeys`
- [ ] Simulation seed durations documented if changed
- [ ] dryRun/live reconcile does not drop legitimate distinct fills
