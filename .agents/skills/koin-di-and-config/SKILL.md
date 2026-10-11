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

Koin version in `gradle/libs.versions.toml`: **4.2.2** — keep `.agents/AGENTS.md` in sync.

`OrderIntentRepository` and `OrderIntentService` are singleton bindings.
Production `OrderExecutorImpl` receives the service explicitly so live attempts
use the durable journal; compatibility constructors without the optional
service remain for legacy/unit doubles.

---

## Config models in `:common`

Pure KMP types under `common/.../config/`:

- `AppConfig` — top-level document
- `Settings` — loop delay, deviation trigger, minimum order size, `dryRun`,
  `simulation`, and optional quality scores
- `Allocation` — symbol + percent + optional `#rrggbb` color
- `KrakenCredentials` — key/secret holders (never commit real values)

## Validation (`ConfigServiceImpl`)

- Allocations **sum to 100%** within tolerance `0.01`
- **Must include USD** (`symbol.isUsd`)
- Non-empty, no duplicate symbols, symbols match `^[A-Z0-9]{1,16}$`
- Missing or invalid allocation colors are normalized by `AssetColorAssigner`;
  persisted colors use `#rrggbb`
- `loopDelaySeconds > 0`, `deviationTriggerPercent ≥ 0`, `minimumOrderSizeUSD ≥ 2`
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

### Active execution sessions and migration

`PortfolioManagerImpl.performCycleWithStableSession()` owns the outer,
cycle-wide `beginExecutionSession()` / `endExecutionSession()` boundary. It
covers current balance and price reads, planning, order execution, and
order-specific sell settlement. The same boundary owns the outer
`DynamicKrakenService.withStableBackend` pin; nested order-executor pins reuse
it. Startup does not synchronize account history or reconstruct reports.

While the session depth is positive, `updateConfig()` still validates and
persists atomically, and `loadConfig()` still validates disk content, but both
stage the runtime value in `pendingConfig`; they must not replace `appConfig`
or emit `_configFlow`. The outermost `endExecutionSession()` publishes the
last staged config and emits its settings. This keeps one money-moving cycle
on one coherent config and credential version.

`ExecutionJournalBootstrap` still runs before the live loop for safe legacy
intent migration and restart recovery. The legacy `kraken-rebalancer.db` path
is preserved for journal migration and account-history continuity during
binding; it is not a reporting destination. Existing legacy files and tables
are left untouched by the retired history-sync/reconstruction paths. Startup
then starts `ActualObservationDispatcher` and the portfolio manager; it does
not rebuild retrospective reports.

Older settings JSON may include retired history-only drawdown, deployment,
inception, or comparison-start keys. The parser drops these keys before model
deserialization, and a later Settings save writes only active controls.

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
