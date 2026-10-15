---
name: trade-history-sync
description: >-
  Owns clean History capture and account-scoped Kraken trade/ledger
  synchronization, cursor commits, direct value evidence, and route safety.
  Keep portfolio-value samples independent from retrospective reconstruction
  and from order-specific fill confirmation.
---

# History Capture and Exchange Synchronization

History has two distinct evidence paths:

- **Cycle values and activity:** the portfolio manager submits the selected
  direct cycle sample and activity to the bounded History recorder. It writes
  to the separate History store after the cycle captures its data.
- **Exchange events:** an operator starts paginated Kraken Trades and Ledgers
  synchronization from the History page. The synchronizer commits each page
  and cursor before requesting the next page.

The History page reports exchange records and direct cycle samples. It does not
reconstruct historical portfolio values from trades and ledgers, fill gaps, or
calculate Actual / Buy & Hold performance. Order-specific sell-fill checks
remain a separate execution path.

## Identity and persistence boundaries

- History records are scoped by an authenticated account identity digest and
  configured managed-asset set. Do not store or log the raw account identity.
- The shared data directory binds the account-scoped History and execution
  stores to one account with account-identity.sha256. A changed or corrupt
  identity fails closed; do not add a fallback that merges accounts.
- history.db and execution.db are separate files. The new History schema is
  explicitly initialized; normal startup must not create or upgrade a missing
  file-backed database. Do not import the former reporting or Actual stores.
- Simulation observations use a process-session scope distinct from account
  History. Simulation does not authenticate an account or sync Kraken history.
- Treat incomplete direct evidence as incomplete. A missing or ambiguous
  balance, invalid price, timestamp window over two minutes, or balance/ticker
  gap over 60 seconds must not produce a total value.
- Unknown fee denomination remains unknown. Do not subtract a trade fee as USD
  unless source evidence establishes USD denomination.

## Bounded synchronization rules

1. Use ConfigService.withExecutionSession across each bounded identity, page,
   and commit operation so one page cannot mix settings or credential backend.
2. Verify authenticated account identity and credential scope before and after
   every Kraken page request. Never commit a page after the verified identity
   changes.
3. Request one page at a time, cap page size, and commit its rows and next
   cursor atomically before fetching another page. Resume from the committed
   cursor after interruption.
4. Keep each page request bounded by the configured timeout. Retry a failed or
   invalid page from the start of its bounded window at most once; leave further
   failures visible in sync status.
5. Synchronization yields while a portfolio execution cycle is active. Do not
   let reporting reads or writes block a safe rebalance cycle.
6. Expose account sync only after identity is verified. Mutation routes require
   same-origin validation and CSRF protection; do not add a UI control that
   submits authenticated sync requests in Simulation.

## Keep the data paths separate

- Kraken Trades / Ledgers account history is synchronized for display and
  balance checkpoints; it is not a pricing source for historical portfolio
  reconstruction.
- Cycle values use the sample's direct balance and ticker evidence. Do not
  backfill past values with current prices, later prices, or OHLC closes.
- Sell settlement may read TradesHistory only for transaction IDs returned by
  current-cycle sell orders. It caps confirmed proceeds to observed spendable
  USD and does not trigger account-wide synchronization.

For active runtime wiring see [docs/FLOWS.md](../../../docs/FLOWS.md),
[docs/ALGORITHM.md](../../../docs/ALGORITHM.md), the
[Kraken integration skill](../kraken-api-integration/SKILL.md), and the
[execution-journal storage notes](../../../SECURITY.md#execution-journal-storage-and-recovery).

## Checklist

- [ ] Account identity and managed-asset scope are explicit on every row
- [ ] Each page fetch and commit uses a pinned execution session
- [ ] Page identity is checked around the Kraken request
- [ ] Cursor and rows commit atomically; retry count is bounded
- [ ] Incomplete direct observations have no total and gaps are not interpolated
- [ ] Simulation data stays separate from authenticated account history
- [ ] Order-specific sell fills remain isolated from account-history sync
