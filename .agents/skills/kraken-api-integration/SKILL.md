---
name: kraken-api-integration
description: >-
  Kraken REST integration — symbol mapping, HMAC-SHA512 signing, RateLimiter
  call-counter (safeLimit 12, decay 0.33), Mutex, retryWithFlow lockout backoff,
  AddOrder cl_ord_id open-order uniqueness (not userref), and public vs private
  paths. Use when changing KrakenServiceImpl, RateLimiter, DynamicKrakenService,
  OrderExecutorImpl client order ids, or exchange credentials handling.
---

# Kraken API & Exchange Integration

Related: [dry-run-and-simulation](../dry-run-and-simulation/SKILL.md) for
`dryRun` / `simulation` and `DynamicKrakenService` routing.

## Symbol conventions

| Display | Ticker pair | Balance keys |
| :--- | :--- | :--- |
| BTC | `XBTUSD` | `XXBT`, `XBT` |
| DOGE | `XDGUSD` | `XXDG`, `DOGE` |
| USD | — | `ZUSD`, `USD` |

Normalize UI/logs to clean `BASE/USD` display form.

## Public vs private

- **Public** (`queryPublic`): ticker/OHLC — no rate limiter, no HMAC.
- **Private** (`queryPrivate`): balances, orders, trades history — `RateLimiter` +
  HMAC-SHA512 + `retryWithFlow`.

DI binds `KrakenService` → `DynamicKrakenService` (live `KrakenServiceImpl` or
`SimulatedKrakenService`).

---

## RateLimiter

`service/impl/RateLimiter.kt`:

- `safeLimit = 12.0`, `decayRate = 0.33`; the counter decays linearly by
  `elapsedSeconds × 0.33`
- All counter updates under coroutine **`Mutex`** (released **before** throttle
  `delay` so waiters do not HOL-block other private calls)
- Waits until `callCounter + cost ≤ safeLimit`

Per-endpoint **cost** (in `KrakenServiceImpl.queryPrivate`):

- **2.0** if path contains `TradesHistory`, `Ledgers`, or `ClosedOrders`
- **1.0** otherwise

---

## HMAC-SHA512 signing

`KrakenServiceImpl.signRequest` / `KrakenApiConstants`:

1. SHA-256 of `(nonce + postData)`
2. Prepend URI path bytes
3. HMAC-SHA512 with Base64-decoded private key
4. Base64 signature header

Never log raw signatures, API keys, or secrets. Load credentials from env or
gitignored config.

---

## Nonce (private calls only)

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

---

## retryWithFlow & lockout backoff

Defaults in `KrakenServiceImpl`:

| Param | Value |
| :--- | :--- |
| `maxAttempts` | 5 (network / rate-limit) |
| `maxLockoutAttempts` | 9 |
| `initialBackoffMs` | 2000 |
| `rateLimitBackoffMs` | 10000 |
| Lockout start | **10_000** ms |
| Lockout cap | **15 minutes** (doubles each lockout) |

Retry on `IOException`, `ResponseException`, and messages containing
`Rate limit exceeded` or `Temporary lockout`.

`AddOrder` is the safety exception: it uses `maxAttempts = 1`. A transport or
response failure may occur after Kraken accepted the order, and `cl_ord_id`
uniqueness is guaranteed only while an order remains open, so never re-POST an
ambiguous AddOrder response. Journal it as unresolved and require reconciliation.

- `retryWithFlow` tracks `attempt` (network / rate limit) and `lockoutAttempt`
  separately — lockout doubles 10s → 15m without consuming the 5 network attempts.
- `queryPublic` uses `retryWithFlow` but no RateLimiter; private calls always
  `acquireWithCost` first.
- `getTradeHistory` returns `emptyList()` when credentials are missing — do not
  treat that as "no trades on the exchange".

---

## AddOrder field names (Kraken REST)

Kotlin `KrakenService.executeOrder(pair, type, side, …)` maps to Kraken POST
fields as follows (do **not** confuse Kotlin param names with Kraken keys):

- Kraken **`type`** ← Kotlin **`side`** (`buy` / `sell` via `OrderSide.apiValue`)
- Kraken **`ordertype`** ← Kotlin **`type`** (`market`)
- `cl_ord_id` seed uses lowercase `side.apiValue` in
  `OrderExecutorImpl.clientOrderId(cycleId, symbol, side)`.
- Volume: scale 8, `stripTrailingZeros()`, `toPlainString()` before POST.
- A non-null `dryRun` argument overrides config; `OrderExecutor` always passes
  `settings.dryRun` explicitly.

---

## Open-order uniqueness (`cl_ord_id`)

Kraken enforces uniqueness of `cl_ord_id` among the client's **open** orders
only — not full request idempotency across filled/canceled orders. Verify any
stronger claim against current Kraken docs before shipping:

- [REST AddOrder](https://docs.kraken.com/api/docs/rest-api/add-order)
- [Client order id guide](https://docs.kraken.com/exchange/guides/general/clordid)

- **`cl_ord_id`**: Client-assigned UUID string. Canonical seed is
  `OrderExecutorImpl.clientOrderId`:
  `UUID.nameUUIDFromBytes("$cycleId|$symbol|$side")` (pipe-separated; blank
  `cycleId` → omit the param). `side` must be `OrderSide.apiValue` (lowercase
  `"buy"` / `"sell"`), not display casing. Do not invent a different seed
  format in docs or callers.
- **`userref`**: 32-bit integer user reference tag. **Do NOT use `userref` for
  uniqueness or idempotency** — Kraken permits duplicate open orders with
  identical `userref` values. `cl_ord_id` and `userref` are mutually exclusive
  on AddOrder.

---

## Checklist

- [ ] Symbols mapped (`BTC` → `XBTUSD`/`XXBT`, etc.)
- [ ] Private calls use RateLimiter + Mutex; public do not
- [ ] Signing and secrets never logged
- [ ] Lockout backoff 10s → 15m via `retryWithFlow`
- [ ] Cross-check dryRun/simulation via DynamicKrakenService
- [ ] AddOrder uses `cl_ord_id` for open-order uniqueness (not `userref`)
