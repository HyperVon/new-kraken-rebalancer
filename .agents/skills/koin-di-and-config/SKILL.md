---
name: koin-di-and-config
description: >-
  Koin 4.2.2 dependency injection and ConfigService — AppModule bindings,
  AppConfig/Settings/Allocation in :common, atomic write-then-rename of
  rebalancer-config.json, validation (allocations sum 100%, USD required), env
  overrides, and watchConfigChanges. Use when changing DI, config schema, or
  settings persistence.
---

# Koin Dependency Injection & Configuration

## Koin 4.2.2 module

Bindings live in `config/AppModule.kt` using Koin DSL (`single` / `singleOf`):

```kotlin
// Explicit constructor (not singleOf) so the default RateLimiter() is used:
// the limiter is a constructor param only so tests can inject a recording fake.
single { KrakenServiceImpl(configService = get(), objectMapper = get(), httpClient = get()) }
singleOf(::SimulatedKrakenService)
single<KrakenService> {
    DynamicKrakenService(
        realService = get(),
        simulatedService = get(),
        configService = get(),
    )
}
single<ConfigService> { ConfigServiceImpl(objectMapper = get()) }
```

- Prefer **`single` / `singleOf`** for services, repositories, and the exchange facade.
- Use **`factory`** only for short-lived workers.

### DI traps (AppModule)

- `PortfolioManagerImpl`: use explicit
  `single { PortfolioManagerImpl(..., krakenService = get()) }` — `singleOf`
  skips the nullable `krakenService` and disables cycle-level pinning.
- `KrakenServiceImpl`: explicit `single { KrakenServiceImpl(...) }` so the
  default `RateLimiter()` is used; tests inject a recording subclass.
- The `KrakenService` port is always `DynamicKrakenService`; code needing
  stability calls `withStableBackend`.

Koin version in `build.gradle.kts`: **4.2.2** — keep `.agents/AGENTS.md` in sync.

---

## Config models in `:common`

Pure KMP types under `common/.../config/`:

- `AppConfig` — top-level document
- `Settings` — loop delay, triggers, dust, `dryRun`, `simulation`, fiat params
- `Allocation` — symbol + percent
- `KrakenCredentials` — key/secret holders (never commit real values)

## Validation (`ConfigServiceImpl`)

- Allocations **sum to 100%** within tolerance `0.001`
- **Must include USD** (`symbol.isUsd`)
- Non-empty, no duplicate symbols, symbols match `^[A-Z0-9]{1,16}$`
- `loopDelaySeconds > 0`, `deviationTriggerPercent ≥ 0`, `dustThresholdUSD ≥ 0`
- `fiatMaxDrawdown` in `0..100`, `fiatDeploymentExponent > 0`

## Atomic persistence

`writeConfigAtomically()`:

1. Write `rebalancer-config.json.tmp`
2. `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` → `rebalancer-config.json`

File is gitignored — never commit secrets.

### Credential persistence (never write resolved secrets)

- `ConfigServiceImpl.updateConfig` calls `configForPersistence`: when posted
  `KrakenCredentials` equals the **previous resolved** pair, persist
  `persistedKrakenCredentials` (raw `${ENV:default}` placeholders from disk),
  not the resolved runtime values.
- The settings form posts resolved credentials back unchanged — saving loop
  delay / allocations must **not** materialize env vars into JSON.
- Anti-pattern: `updateConfig` → `writeConfigAtomically(full resolved config)`
  without the placeholder-preservation branch.
- Checklist: [ ] saving non-credential fields leaves `${KRAKEN_*}` intact;
  [ ] real rotation still writes new values when the user changes keys.

## Watching & env

- `_configFlow`: `MutableSharedFlow<Settings>(replay=1, DROP_OLDEST)`
- `watchConfigChanges()` collected with **`collectLatest`** in `PortfolioManagerImpl`
  so loop restarts immediately on change
- Support environment variable overrides for credentials / paths where already wired

### `${ENV:default}` substitution + JSON escaping

- Pattern `"${KRAKEN_API_KEY:YOUR_KRAKEN_API_KEY}"` — regex `\$\{([^}]+)}`,
  key/default split on the first `:`.
- Resolution order: non-blank `System.getenv(key)` → placeholder default → `""`;
  then JSON-escape `\` and `"` before splicing into raw file text.
- Validation throws `InvalidConfigurationException` wrapping the
  `IllegalArgumentException` message for UI display.

## Checklist

- [ ] Koin **4.2.2**; `KrakenService` → `DynamicKrakenService`
- [ ] Models in `:common`; validation enforces 100% + USD
- [ ] Atomic write-then-rename
- [ ] Reactive updates via `watchConfigChanges()` / `collectLatest`
- [ ] No secrets in VCS; shutdown hooks cancel loops cleanly
