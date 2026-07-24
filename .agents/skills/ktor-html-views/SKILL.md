---
name: ktor-html-views
description: Server-side HTML DSL and CSS development — kotlinx.html DSL, kotlinx-css styling, HTMX dynamic attributes, Layouts.kt helpers, and Ktor HTML routing.
---

# Ktor Server-Side HTML & CSS Views

Use this skill when creating or modifying server-side rendered HTML web templates, Ktor routing endpoints, layout helpers, or CSS styles (`kotlinx.html` DSL, `kotlinx-css` DSL).

## Architectural Decomposition

Server-side HTML rendering follows a strict modular structure:

1. **View Routes** (`com.gemini.krakenbot.controller`): Ktor routing functions that respond to HTTP GET/POST with `call.respondHtml { ... }`.
2. **Layout Helpers** (`com.gemini.krakenbot.view.Layouts`): Reusable layout components (e.g. `statusCard`, `glassPanel`, `headerNav`).
3. **Domain Views** (`com.gemini.krakenbot.view`): Feature pages (`DashboardView.kt`, `HistoryView.kt`, `SettingsView.kt`).
4. **Modular CSS Styles** (`com.gemini.krakenbot.view.css`): Domain-specific styling files (`CssTheme`, `LayoutStyles`, `ComponentStyles`, `FormStyles`, `NavigationStyles`, `MediaQueries`, `CssStyles` facade).

---

## Type Safety & Shared `:common` Core

HTML templates and CSS builders **MUST** consume shared domain constants, HTML IDs/attributes, and `CssClass` sealed class hierarchies from the shared `:common` module rather than duplicating string literals:

```kotlin
// CORRECT:
import com.gemini.krakenbot.common.CssClass
import com.gemini.krakenbot.common.HtmlIds
import com.gemini.krakenbot.common.ViewText

div(CssClass.GlassCard.name) {
    id = HtmlIds.STATUS_CARD
    h2 { +ViewText.PORTFOLIO_SUMMARY }
}

// WRONG — magic string literals:
div("glass-card") {
    id = "status-card"
    h2 { +"Portfolio Summary" }
}
```

---

## HTMX Integration

Use `HtmxAttrs` constants for dynamic partial page updates without full browser reloads:

```kotlin
button {
    attributes["hx-post"] = "/api/rebalance/trigger"
    attributes["hx-target"] = "#status-card"
    attributes["hx-swap"] = "outerHTML"
    +ViewText.TRIGGER_REBALANCE
}
```

---

## Visual Design System & Aesthetics

All views must maintain modern dark mode aesthetics:

- **Colors**: Use HSL color tokens defined in `CssTheme` (dark background, sleek glassmorphism borders, neon accent badges).
- **Typography**: Clean hierarchy utilizing Inter font styling.
- **Layout**: Dynamic CSS Grid and Flexbox responsive layouts.

---

## Checklist

Before submitting server-side HTML/CSS code:

- [ ] Consumes `CssClass`, `HtmlIds`, `HtmlAttrs`, and `ViewText` from `:common`
- [ ] Uses modular layout helpers in `Layouts.kt` for repeated UI structures
- [ ] Modular CSS definitions placed in `com.gemini.krakenbot.view.css`
- [ ] HTMX attributes use type-safe `HtmxAttrs` constants
- [ ] No inline FQNs present
- [ ] Zero markdown lint or Kotlin compiler warnings
