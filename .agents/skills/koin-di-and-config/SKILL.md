---
name: koin-di-and-config
description: Koin dependency injection and dynamic configuration — appModule bindings, singletons vs factories, ConfigService watching, environment variables, and shutdown hooks.
---

# Koin Dependency Injection & Configuration

Use this skill when defining Koin dependency injection modules, declaring service singletons, managing runtime application configuration (`rebalancer-config.json`), or binding JVM shutdown hooks.

## Koin Module Declarations

Declare application dependencies inside `appModule` in `KrakenRebalancerApplication.kt` using Koin 4.2.1 DSL:

```kotlin
val appModule = module {
    single<Database> { DatabaseConfig.init(getProperty("db.path", "kraken-rebalancer.db")) }
    single<TradeRepository> { SqliteTradeRepositoryImpl(get()) }
    single<PortfolioStatsRepository> { SqlitePortfolioStatsRepositoryImpl(get()) }
    single<KrakenService> { KrakenServiceImpl(get()) }
    single<ConfigService> { ConfigServiceImpl("rebalancer-config.json") }
    single<PortfolioAnalyzer> { PortfolioAnalyzerImpl(get(), get()) }
    single<OrderExecutor> { OrderExecutorImpl(get()) }
    single<PortfolioManager> { PortfolioManagerImpl(get(), get(), get()) }
}
```

- **Singletons (`single`)**: Repositories, API clients, orchestrators, and database connectors.
- **Factories (`factory`)**: Short-lived worker instances or stateless command handlers.

---

## Dynamic Configuration Watching

`ConfigService` monitors `rebalancer-config.json` for live configuration changes (e.g., target asset allocation percentages, drift thresholds, rebalance intervals) using coroutine `Flow`:

```kotlin
class PortfolioManagerImpl(
    private val configService: ConfigService,
    private val portfolioAnalyzer: PortfolioAnalyzer,
    private val orderExecutor: OrderExecutor
) : PortfolioManager {

    suspend fun watchConfig() {
        configService.watchConfigChanges().collect { newConfig ->
            log.info("Dynamic configuration updated: {}", newConfig)
            // Reactively update rebalancing thresholds
        }
    }
}
```

---

## Checklist

Before submitting Koin DI or configuration code:

- [ ] Services registered in `appModule` using `single` or `factory`
- [ ] Environment variable overrides supported for config properties
- [ ] Reactive config changes handled via `watchConfigChanges()` Flow
- [ ] JVM shutdown hooks registered for graceful coroutine loop cancellation
- [ ] No circular dependencies in Koin module graphs
