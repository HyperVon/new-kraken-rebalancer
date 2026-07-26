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

## What belongs here

| Area | Types / files |
| :--- | :--- |
| Config | `AppConfig`, `Settings`, `Allocation`, `KrakenCredentials` |
| Domain | `TimeRange`, `OrderSide`, `OrderType`, `Asset`, `Result`, `TradeSourceKeys`, `SyncMetadataKeys` |
| Wire DTOs (`api/`) | `PortfolioSnapshot`, `TradeRecord`, `HistoryStats`, `SyncProgressResponse` |
| Precision | `PrecisionConstants` |
| View util | `CssClass`, `HtmlIds`, `HtmlAttrs`, `HtmxAttrs`, `ViewText`, `Routes`, `FormFields`, `QueryParamKeys`, `DataProps`, `ChartProps` |

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
- HTTP paths → `Routes`
- Shared enums/constants used by backend **and** `:frontend-js`

Consume from JVM views and Kotlin/JS via the same packages
(`com.gemini.krakenbot.view.util`, `com.gemini.krakenbot.config`, …).

## Checklist

- [ ] New shared strings/IDs/CSS land in `:common`, not duplicated
- [ ] No JVM/JS-only imports in `commonMain`
- [ ] Both backend and frontend compile against the change
