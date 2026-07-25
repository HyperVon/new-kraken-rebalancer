---
name: ktor-html-views
description: >-
  Server-side HTML/CSS for the dashboard — view/component/*, DashboardRoutes,
  DashboardController, kotlinx.html/css, HTMX, the settings-backed mode plate,
  and Routes from :common. Use when changing SSR templates, CSS modules, or
  Ktor HTML/SSE routes.
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

## Page headers: `brandWithMode(settings)`

Every page header uses the shared helper in `view/util/Layouts.kt`:

```kotlin
header {
    brandWithMode(settings)   // brandMark() + modePlate(settings)
    primaryNav(ActiveNav.HISTORY)
}
```

`modePlate` is the **authoritative trading-mode indicator** and is derived from
persisted `Settings`, with strict precedence:

| Condition | Plate | Class |
| :--- | :--- | :--- |
| `settings.simulation` | `ViewText.MODE_SIMULATION` | `CssClass.Mode.Simulation` |
| else `settings.dryRun` | `ViewText.MODE_DRY_RUN` | `CssClass.Mode.DryRun` |
| else | `ViewText.MODE_LIVE` | `CssClass.Mode.Live` |

- Simulation wins over dryRun — the emulator is the stronger safety guarantee
  ([dry-run-and-simulation](../dry-run-and-simulation/SKILL.md)).
- Each plate carries a `MODE_*_TITLE` tooltip; live trading is styled as
  high-consequence. **Never** drop, hide, or downgrade the plate.
- The header's separate stream chip (`ViewText.STREAM` / `STREAM_STALE`) reports
  SSE freshness only and must not read as live trading. On the Dashboard it sits
  beside the mode plate in `HeaderTitleSection`; the shell renders a placeholder
  and the fragment refreshes `#header-status` via `hx-swap-oob`.

### Settings must reach the renderers

`DashboardController` resolves `configService.getConfig().settings` per request
and threads it through:

```kotlin
dashboardView.renderHistoryPage(settings)
dashboardView.renderDashboardFragment(latest, history)
```

New pages or HTMX fragments that render a header need the same `Settings`
parameter — otherwise a swapped fragment silently loses the mode plate.

## HTMX

Use `HtmxAttrs` / `Routes` for partial swaps (e.g. dashboard fragment refresh).
Prefer type-safe attribute keys over raw `"hx-*"`.

## SSE

Live updates: `GET /api/status/stream` — controller collects
`TradeHistoryService` snapshot flow and sends `ServerSentEvent` payloads.
See [coroutines-flows-sse](../coroutines-flows-sse/SKILL.md).

## Design notes

Refined Glass aesthetic: cool-blue glass sheen with light blur, cyan rim glow,
and raised drop shadows. Reuse `CssTheme` tokens (`colorSurface1/2`,
`shadowSurface1/2`, `glassSurfaceGradient`, `insetTopHighlight`) instead of
ad-hoc `rgba(...)` literals, and keep text tokens at WCAG AA on glass. Prefer
component helpers over one-off markup copies.

History chart sections render **one** header row (title + zoom tools) plus an
optional `HistoryChartSection.caption` under the canvas — put legend caveats in
the caption rather than lengthening legend labels.

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
- [ ] Header uses `brandWithMode(settings)`; `Settings` threaded to every page
      and fragment renderer
- [ ] Mode plate precedence simulation > dryRun > live is intact
- [ ] SSE path remains `/api/status/stream`
- [ ] No FQNs; markdown/docs updated if route tree changes
- [ ] Visual changes → run docs-screenshot-refresh when shipping docs
