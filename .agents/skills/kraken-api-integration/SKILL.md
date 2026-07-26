---
name: kraken-api-integration
description: >-
  Kraken REST integration — symbol mapping, HMAC-SHA512 signing, RateLimiter
  call-counter (safeLimit 12, decay 0.33), Mutex, retryWithFlow lockout backoff,
  and public vs private paths. Use when changing KrakenServiceImpl, RateLimiter,
  DynamicKrakenService, or exchange credentials handling.
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

---

## Checklist

- [ ] Symbols mapped (`BTC` → `XBTUSD`/`XXBT`, etc.)
- [ ] Private calls use RateLimiter + Mutex; public do not
- [ ] Signing and secrets never logged
- [ ] Lockout backoff 10s → 15m via `retryWithFlow`
- [ ] Cross-check dryRun/simulation via DynamicKrakenService
