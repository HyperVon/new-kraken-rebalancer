---
name: ktor-html-views
description: >-
  Server-side HTML/CSS for the dashboard — view/component/*, DashboardRoutes,
  DashboardController, kotlinx.html/css, HTMX, and Routes from :common. Use when
  changing SSR templates, CSS modules, or Ktor HTML/SSE routes.
---

# Ktor Server-Side HTML & CSS Views

## Layout (actual package structure)

1. **Routing** — `DashboardRoutes.dashboardRouting()` injects `DashboardController`
   and registers routes.
2. **Controller** — `DashboardController.registerRoutes()` — HTML pages, fragments,
   history APIs, SSE `/api/status/stream`.
3. **Page orchestration** — `DashboardView` composes feature pages.
4. **Components** — `view/component/*`:
   - `DashboardShellComponent`, `DashboardFragmentComponent`
   - `OverviewGridComponent`, `AllocationChartComponent`, `PerformanceTableComponent`
   - `RecentActivityComponent`, `HistoryPageComponent`, `SettingsFormComponent`
5. **CSS** — `view/css/*` (`CssTheme`, `LayoutStyles`, `ComponentStyles`, …,
   `CssStyles` facade).
6. **Shared paths/IDs** — `:common` `view/util/Routes.kt`, `HtmlIds`, `HtmlAttrs`,
   `HtmxAttrs`, `ViewText`, `CssClass`.

Path constants (`Routes`): `/`, `/settings`, `/history`, `/fragments/dashboard`,
`/api/status/stream`, `/api/history/*`, `/api/health`, `/static/*`.

---

## Type-safe `:common` usage

```kotlin
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmlIds
import com.gemini.krakenbot.view.util.ViewText

div(classes = CssClass.GlassCard.name) {
    id = HtmlIds.STATUS_CARD
    h2 { +ViewText.PORTFOLIO_SUMMARY }
}
```

No duplicated magic strings for IDs, CSS classes, or user-visible labels.

## HTMX

Use `HtmxAttrs` / `Routes` for partial swaps (e.g. dashboard fragment refresh).
Prefer type-safe attribute keys over raw `"hx-*"`.

## SSE

Live updates: `GET /api/status/stream` — controller collects
`TradeHistoryService` snapshot flow and sends `ServerSentEvent` payloads.
See [coroutines-flows-sse](../coroutines-flows-sse/SKILL.md).

## Design notes

Dark glass aesthetic via `CssTheme` HSL tokens; responsive grid/flex. Prefer
component helpers over one-off markup copies.

## Security note

Dashboard has **no user authentication**. CORS is limited by
`isLocalOrPrivateOrigin` — do not weaken origin checks casually.

## Docs screenshots

Visible changes to Dashboard / Settings / History should refresh README PNGs via
[docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md) and update
[docs/USER_GUIDE.md](../../../docs/USER_GUIDE.md) when user-facing meaning
changes ([user-guide](../user-guide/SKILL.md)).

For interaction smoke testing after SSR/HTMX changes, use
[ui-manual-qa](../ui-manual-qa/SKILL.md). For a full visual critique or redesign
pass, use [ui-visual-review](../ui-visual-review/SKILL.md) then
[ui-visual-implement](../ui-visual-implement/SKILL.md) (capture with
`--out-dir`, read PNGs, iterate).

## Checklist

- [ ] New UI goes in `view/component/` + CSS module; routes via controller
- [ ] Consumes `CssClass` / `HtmlIds` / `ViewText` / `Routes` from `:common`
- [ ] SSE path remains `/api/status/stream`
- [ ] No FQNs; markdown/docs updated if route tree changes
- [ ] Visual changes → run docs-screenshot-refresh when shipping docs
