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
- `Allocation` — symbol + percent + optional `#rrggbb` color
- `KrakenCredentials` — key/secret holders (never commit real values)

## Validation (`ConfigServiceImpl`)

- Allocations **sum to 100%** within tolerance `0.001`
- **Must include USD** (`symbol.isUsd`)
- Non-empty, no duplicate symbols, symbols match `^[A-Z0-9]{1,16}$`
- Missing or invalid allocation colors are normalized by `AssetColorAssigner`;
  persisted colors use `#rrggbb`
- `loopDelaySeconds > 0`, `deviationTriggerPercent ≥ 0`, `dustThresholdUSD ≥ 0`
- `fiatMaxDrawdown` in `0..100`, `fiatDeploymentExponent > 0`
- Every `Double` setting and allocation percentage must be finite; reject
  `NaN` and positive/negative infinity before publishing or persisting config.
- The Settings controller requires every numeric trading field to parse
  strictly, requires each scalar field exactly once, and requires aligned
  symbol/target/color rows. It rejects malformed submissions before
  `updateConfig`; it never supplies trading defaults or truncates allocation
  rows.

## Atomic persistence

`writeConfigAtomically()`:

1. Write `rebalancer-config.json.tmp`
2. `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` → `rebalancer-config.json`

Only after the move succeeds may `appConfig`, raw persisted credentials, and
the settings flow publish the new values. File reload follows the same
transactional rule: parse and validate both representations before assigning
runtime or raw-credential state.

### Active execution sessions

`PortfolioManagerImpl.performRebalanceCycle()` brackets each cycle, and
`TradeHistorySyncService` brackets each non-no-op standalone paginated sync,
with `beginExecutionSession()` / `endExecutionSession()`. While the session
depth is positive, `updateConfig()` still validates and persists atomically, and
`loadConfig()` still validates disk content, but both stage the runtime value in
`pendingConfig`; they must not replace `appConfig` or emit `_configFlow`. The
outermost `endExecutionSession()` publishes the last staged config and emits
its settings. This keeps one money-moving cycle or multi-page account sync on
one coherent config and credential version.

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
  so an idle loop restarts immediately on change; active sessions publish only
  after execution ends
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
- [ ] Active execution session defers runtime config + flow publication
- [ ] Reactive updates via `watchConfigChanges()` / `collectLatest`
- [ ] No secrets in VCS; shutdown hooks cancel loops cleanly
