---
name: common-kmp-module
description: >-
  Owns what belongs in common/src/commonMain — CssClass, HtmlIds, HtmlAttrs,
  HtmxAttrs, ViewText, Routes, TimeRange, OrderSide, PrecisionConstants,
  AppConfig/Settings, api wire DTOs — and purity rules (no JVM/JS imports). Use
  when adding UI strings, IDs, CSS class names, shared models, History JSON DTOs,
  or touching the :common module.
---

# `:common` Kotlin Multiplatform Module

Path: `common/src/commonMain/kotlin/com/gemini/krakenbot/`.

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **common-kmp-module** (this) | What belongs in `:common` + purity rules |
| [koin-di-and-config](../koin-di-and-config/SKILL.md) | Loading/validating/persisting `Settings` on the JVM |
| [ktor-html-views](../ktor-html-views/SKILL.md) | SSR that **consumes** `CssClass` / `ViewText` / `Routes` |
| [frontend-js-development](../frontend-js-development/SKILL.md) | JS that **consumes** the same shared symbols / `api/` DTOs |

## What belongs here

| Area | Types / files |
| :--- | :--- |
| Config | `AppConfig`, `Settings`, `Allocation`, `KrakenCredentials` |
| Domain | `TimeRange`, `OrderSide`, `OrderType`, `Asset`, `Result`, `TradeSource`, `SyncMetadataKeys` |
| Wire DTOs (`api/`) | `PortfolioSnapshot`, `TradeRecord`, `HistoryStats`, `SyncProgressResponse` |
| Precision | `PrecisionConstants` |
| View util | `CssClass`, `HtmlQueries`, `HtmlIds`, `HtmlAttrs`, `HtmxAttrs`, `ViewText`, `Routes`, `FormFields`, `QueryParamKeys`, `ChartProps` |

## Generated catalog boundary & CodeGen pattern

Constant catalogs (UI strings, HTML attributes/IDs, CSS classes, routes, metadata keys, chart properties, exchange ticker aliases) are maintained as declarative YAML resources under `common/src/commonMain/resources/codegen/` and generated into pure KMP Kotlin by the JVM-only `codegen` KSP processor.

### When to move things to CodeGen

- **Static Constant Groups**: Any group of 3+ related string or scalar constants (`ViewText`, `HtmlAttrs`, `HtmlIds`, `Routes`, `SyncMetadataKeys`, `ChartProps`, `KrakenAssetAliases`). Do not write handwritten `object Foo { const val ... }` in Kotlin.
- **CSS Class Tokens**: Hierarchical CSS class tokens (`CssClassSchema` → `css-classes.yaml`).
- **Exchange Asset / Symbol Aliases**: Known ticker aliases and exchange codes (`KrakenAssetAliases` → `kraken-asset-aliases.yaml`).

### The standard 3-step pattern

1. **Declarative YAML Resource**: Add `common/src/commonMain/resources/codegen/<name>.yaml` with explicit key-value pairs.
2. **Schema Declaration**: Add `@GenerateStringConstants(fileName = "<Name>", resource = "codegen/<name>.yaml")` in `StringConstantSchemas.kt` (or `@GenerateCssClasses` in `CssClassSchema.kt`).
3. **Runtime Extensions / Helpers (if needed)**: If the constants are consumed in maps, lists, or functions, place those in a dedicated handwritten `<Name>Mappings.kt` (e.g. `KrakenAssetMappings.kt`) or `<Name>Extensions.kt` (e.g. `ChartColors.kt`), referencing the generated `const val` properties directly.

- Preserve public names and `const val` semantics for generated string catalogs.
- Keep every group/name/value explicit. Do not infer composite CSS classes,
  selectors, routes, or HTML attributes from naming conventions.
- `CssClass` owns class values; `HtmlQueries` owns selectors that combine class,
  tag, and form-field semantics. Keep those responsibilities separate.
- Keep mixed semantic catalogs, JVM values, numeric precision rules, and
  behavior-bearing objects handwritten unless a typed generator is proven to
  preserve their contracts.
- Generated output must compile for both common metadata/JVM and JS targets;
  it may not import JVM, Ktor, Exposed, logging, or browser DOM APIs.

### Two TradeRecord types (do not merge)

| Layer | Path | Fields |
| :--- | :--- | :--- |
| `:common` wire DTO | `api/TradeRecord.kt` | `timestamp`, `volume`, `usdAmount` … as **strings** for History JSON |
| JVM domain | `model/TradeRecord.kt` | `Instant`, `BigDecimal`, `TradeSource`, `cycleId`, `orderTxid` |

- Reconcile/dedupe extensions (`isMatchingApiTrade`, `isPairAliasDuplicateOf`)
  live on the **JVM model**, not in `:common`.
- Map explicitly at HTTP boundaries; never put `java.time.Instant` or JVM
  `BigDecimal` in `commonMain`.

### Kraken aliases live in `Asset` (`:common`)

- Ticker remap: `BTC→XBT`, `DOGE→XDG` via `toKrakenTicker()` / `tradingPair()`.
- Price lookup: the **exact set** `acceptedUsdQuotedPairs(symbol)` — never
  `pair.contains(symbol)` (prevents `XBTUSDT` → BTC mis-mapping).
- Balance keys: `possibleBalanceKeys()` + JVM `resolveBalance()` (`XXBT`, `ZUSD`, …).
- API → allocation symbol: `Asset.fromTradingPair(pair, allocations)` before
  persisting trades.
- New asset support: extend the `Asset` companion helpers first; do not hardcode
  pair strings in services.

### `OrderSide`: `apiValue` vs stored `name`

- REST / Kraken: lowercase `apiValue` (`buy` / `sell`); DB and UI: uppercase
  `name` via `OrderSide.normalize()`.
- `OrderType.MARKET` is the only wired order type in live and emulator paths.

## Purity (non-negotiable)

`commonMain` must compile for **both** JVM and JS:

- **Do not** import `java.*`, SLF4J, Exposed, Ktor server, or browser DOM APIs.
- Prefer Kotlin stdlib / multiplatform libraries only.
- Monetary math that needs `BigDecimal` stays on the JVM (or use shared
  string/Double display helpers carefully — engine math is JVM `BigDecimal`).

## When to add symbols

Add or extend `:common` when you introduce:

- User-visible UI copy → `ViewText`
- Element IDs / data attributes → `HtmlIds` / `HtmlAttrs` / `HtmxAttrs`
- CSS class names → `CssClass` sealed hierarchy
- DOM selectors combining classes/tags/fields → `HtmlQueries`
- HTTP paths → `Routes`
- Shared enums/constants used by backend **and** `:frontend-js`

Consume from JVM views and Kotlin/JS via the same packages
(`com.gemini.krakenbot.view.util`, `com.gemini.krakenbot.config`, …).

## Checklist

- [ ] New shared strings/IDs/CSS land in `:common`, not duplicated
- [ ] No JVM/JS-only imports in `commonMain`
- [ ] Both backend and frontend compile against the change
