---
name: kraken-api-integration
description: Kraken REST API integration — endpoint signatures, symbol mapping (BTC -> XBTUSD/XXBT), rate limiting via Mutex, exponential lockout backoff, and secret key protection.
---

# Kraken API & Exchange Integration

Use this skill when implementing, modifying, or testing REST API client calls to the Kraken Cryptocurrency Exchange.

## Kraken Symbol Conventions

Kraken uses legacy ISO-4217 symbol prefixes for major assets. All code interacting with Kraken API payloads **MUST** map display symbols into Kraken REST format:

- **BTC**:
  - Ticker Pair: `XBTUSD`
  - Balance Key: `XXBT` or `XBT`
- **DOGE**:
  - Ticker Pair: `XDGUSD`
  - Balance Key: `XXDG` or `DOGE`
- **USD**:
  - Fiat Balance Key: `ZUSD` or `USD`

```kotlin
fun normalizeSymbol(krakenSymbol: String): String = when (krakenSymbol) {
    "XXBT", "XBT" -> "BTC"
    "XXDG", "XDG", "DOGE" -> "DOGE"
    "ZUSD" -> "USD"
    else -> krakenSymbol
}
```

---

## Rate Limiting & Backoff

Private Kraken API endpoints are protected by an IP-based call counter. Exceeding call rate limits triggers `EGeneral:Temporary lockout`.

Rules:

1. **Mutex Protection**: Wrap private API calls in a coroutine `Mutex` rate-limiter.
2. **Exponential Backoff**: Handle lockout responses by backing off with exponential delays (starting at 10 seconds, scaling up to 15 minutes on repeated lockouts).

```kotlin
suspend fun <T> executeRateLimitedCall(block: suspend () -> T): T {
    return rateLimiter.withLock {
        try {
            block()
        } catch (e: KrakenLockoutException) {
            log.error("Kraken temporary lockout encountered. Backing off for {}s", backoffSeconds)
            delay(backoffSeconds * 1000L)
            throw e
        }
    }
}
```

---

## Security & API Credentials

- **Secrets**: API Key (`kraken.key`) and API Secret (`kraken.secret`) must be loaded from environment variables or `rebalancer-config.json`.
- **Log Masking**: Never print raw HMAC-SHA512 request signatures or private API keys to application logs.

---

## Checklist

Before submitting Kraken API integration code:

- [ ] Ticker and balance symbols mapped cleanly (`BTC` -> `XBTUSD`/`XXBT`)
- [ ] Tickers normalized to `BASE/USD` display format across UI and logs
- [ ] Private endpoint calls protected by coroutine `Mutex` rate limiter
- [ ] `EGeneral:Temporary lockout` handled with exponential backoff
- [ ] No API keys or secret tokens logged
