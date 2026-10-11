---
name: trade-history-sync
description: >-
  Retired account-wide history synchronization and retrospective reporting
  scope. For the preserved legacy account-continuity checks, prospective Actual
  benchmark evidence, and sell-order fill confirmation, follow the active
  skills linked below. Do not implement the removed reporting pipeline.
---

# Retired Trade History Sync Scope

The former account-wide trade/ledger import, simulation history seeding,
retrospective snapshot reconstruction, report APIs, and reporting writes are
retired. No startup or rebalance path should restore them. `/history` redirects
to `/actual`; former `/api/history/*` reporting endpoints return 410 Gone.

This skill remains as a migration note for work touching the preserved account
continuity boundary. The legacy `kraken-rebalancer.db` path is retained for safe
execution-journal migration and account-history continuity during binding. It
is not an active reporting destination. Existing legacy files and tables are
left untouched by the retired reporting paths. `execution.db` and
`kraken-actual.db` remain the active execution-journal and Actual stores.

## Still-active boundaries

- **Legacy account continuity:** `ExecutionJournalBootstrap` migrates prior
  unresolved intents before the live loop. Account binding can verify legacy
  account history as migration/binding evidence. Preserve the existing schema
  migrations, scope checks, source backup, and fail-closed handling; do not
  turn them into account-wide report synchronization.
- **Actual observations:** the Actual store retains directly observed raw
  evidence and gaps for its account/configured-asset scope. The optional
  prospective Buy & Hold segment begins at an eligible complete observation.
  It advances only after event continuity is verified and uses evidence after
  that baseline; it does not reconstruct an earlier period.
- **Prospective continuity reads:** the verifier may read Kraken ledger and
  trade events only for the interval from the benchmark's last verified time
  through the observation being evaluated. It needs complete pagination to
  verify that segment. These checks are forward comparison evidence, not a
  general retrospective report or synchronization service.
- **Sell settlement:** after successful sells, execution can query
  `TradesHistory` using the transaction IDs returned for those orders. This is
  order-specific fill confirmation for the current cycle; see
  [coroutines-flows-sse](../coroutines-flows-sse/SKILL.md) and
  [portfolio-rebalancing-math](../portfolio-rebalancing-math/SKILL.md).

For active runtime ownership, see [`docs/FLOWS.md`](../../../docs/FLOWS.md),
the [Ktor view skill](../ktor-html-views/SKILL.md), and the
[execution-journal recovery notes](../../../SECURITY.md#execution-journal-storage-and-recovery).

## Checklist

- [ ] Do not add account-wide trade/ledger synchronization or historical report writes
- [ ] Do not restore snapshot reconstruction, simulation history seeding, or retired report APIs
- [ ] Keep existing schema migrations and safe legacy account-continuity checks intact
- [ ] Keep prospective benchmark verification scoped to its baseline and forward interval
- [ ] Keep sell fill checks scoped to transaction IDs from the current cycle's orders
