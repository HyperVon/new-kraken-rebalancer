---
name: frontend-js-development
description: Client-side Kotlin/JS subproject development guidelines — DOM manipulation, HTMX event hooks, Ktor SSE streaming, Chart.js JS interop, deep-cloning, and event listener lifecycle cleanup.
---

# Kotlin/JS Client Development (`:frontend-js`)

Use this skill when modifying, extending, or refactoring client-side JavaScript written in Kotlin in the `:frontend-js` subproject (`frontend-js/src/jsMain/kotlin/`).

## Architecture & Responsibilities

The `:frontend-js` subproject compiles to JavaScript via the Kotlin JS IR backend, outputting `/static/rebalancer.js` served by Ktor.

Key responsibilities:

1. **Live SSE Streaming Listener**: Handles real-time updates over Server-Sent Events (`EventSource("/api/status/stream")`).
2. **HTMX Event Hooks**: Listens to HTMX request lifecycle events (`htmx:afterSwap`, `htmx:configRequest`) to re-trigger charts and updates.
3. **Chart.js Integration**: Renders and updates real-time portfolio performance charts without memory leaks.
4. **Time Frame Selection**: Updates portfolio metric cards and charts dynamically when users select time ranges (24h, 7d, 30d, 90d, All).

---

## Chart.js Option Integrity (CRITICAL)

When creating or updating Chart.js instances, **ALWAYS deep-clone** configuration option objects in Kotlin/JS before passing them to Chart.js. Direct object references cause global option mutations across chart re-renders.

```kotlin
val chartOptions = JSON.parse<dynamic>(JSON.stringify(DEFAULT_CHART_OPTIONS))
chartOptions.plugins.title.text = "Portfolio Allocation"

val chart = Chart(ctx, json(
    "type" to "doughnut",
    "data" to chartData,
    "options" to chartOptions
))
```

---

## DOM Lifecycle & Event Listener Cleanup

To prevent memory leaks in single-page HTMX web applications:

- **Clear Interval Timers**: Cancel active `window.setInterval` or `window.setTimeout` timers when DOM components detach or route changes occur.
- **Remove Event Listeners**: Always call `removeEventListener` when unmounting dynamic view components.
- **DOM Container Guards**: Null-check target DOM containers before querying or mutating properties:

```kotlin
val container = document.getElementById("chart-container") ?: return
container.innerHTML = "" // Clean previous canvas node before re-mounting
```

---

## Consuming Shared KMP Core Constants

Client JS code **MUST** consume shared domain constants, HTML IDs, and `CssClass` sealed class names from the shared `:common` module rather than redefining string literals:

```kotlin
// CORRECT — use shared constants:
import com.gemini.krakenbot.common.CssClass
import com.gemini.krakenbot.common.HtmlIds

val statusCard = document.getElementById(HtmlIds.STATUS_CARD)
statusCard?.className = CssClass.StatusCardActive.name

// WRONG — duplicate string literals:
val statusCard = document.getElementById("status-card")
statusCard?.className = "status-card-active"
```

---

## Verification & Testing

Verify Kotlin/JS modifications:

```bash
./gradlew :frontend-js:jsTest
```

Build the compiled production `/static/rebalancer.js` bundle:

```bash
./gradlew :frontend-js:jsJar
```

---

## Checklist

Before completing Kotlin/JS frontend tasks:

- [ ] Chart.js options deep-cloned via `JSON.parse(JSON.stringify(...))` before rendering
- [ ] Active timers and event listeners cleaned up to prevent DOM memory leaks
- [ ] Shared constants from `:common` consumed for HTML IDs, attributes, and CSS classes
- [ ] Element null safety enforced (`document.getElementById(...) ?: return`)
- [ ] Client unit tests pass cleanly (`./gradlew :frontend-js:jsTest`)
