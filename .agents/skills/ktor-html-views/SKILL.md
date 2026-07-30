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

## Type-safe view helpers (`:common` + JVM `HtmlExtensions`)

```kotlin
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div // JVM HtmlExtensions, not :common

div {
    attributes[HtmxAttrs.HX_GET] = Routes.FRAGMENT_DASHBOARD
    attributes[HtmxAttrs.HX_TRIGGER] = HtmxValues.TRIGGER_LOAD_SSE_MESSAGE
    div(CssClass.Loading.SpinnerContainer) {
        div(CssClass.Loading.Spinner) {}
        p { +ViewText.CONNECTING }
    }
}
```

Use `:common` for IDs, CSS class names, routes, and user-visible labels. The
`div(CssClass)` helper lives in JVM `view/util/HtmlExtensions.kt`.

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

## SSE delivery = HTMX + server push (not `:frontend-js` EventSource)

`DashboardShellComponent`:

- loads the `CdnUrls.HTMX_SSE` script
- wrapper sets `HtmxAttrs.HX_EXT` = `sse` and `SSE_CONNECT` = `Routes.API_STATUS_STREAM`
- inner fragment uses `hx-get="/fragments/dashboard"` with
  `hx-trigger="load, sse:message"`

Server: `DashboardController.handleSseStream` JSON-encodes snapshots. Client JS
only updates stream-chip timing; it never opens an EventSource.

See [coroutines-flows-sse](../coroutines-flows-sse/SKILL.md) for flow ownership.

### Stream status OOB swap

`DashboardFragmentComponent.renderStreamStatus` sets `hx-swap-oob="true"` on
`#header-status` only; the mode plate stays in the shell. `StatusCard.Live`
means a healthy **stream**, not live trading.

### Static asset cache-bust (required on every layout change)

- CSS via `commonMetadataAndStyles()` → `/static/style.css?v=<hash>`
- JS via `rebalancerJsSrc()` → `/static/rebalancer.js?v=<hash>`
- Never hand-roll `/static/...` hrefs in new pages
- `DashboardViewTest` asserts `link href="/static/style.css?v="`

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
`isLocalOrPrivateOrigin` — do not weaken origin checks casually. The predicate
parses HTTP(S) origins structurally and accepts only localhost, valid `.local`
hosts, numeric loopback/private/link-local IPv4, and IPv6 loopback; hostname
prefix lookalikes must remain rejected.

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
- [ ] SSE path remains `/api/status/stream`; HTMX shell wiring intact
- [ ] Static assets cache-busted via `commonMetadataAndStyles()` / `rebalancerJsSrc()`
- [ ] `#header-status` refreshed via `hx-swap-oob`; mode plate stays in shell
- [ ] No FQNs; markdown/docs updated if route tree changes
- [ ] Visual changes → run docs-screenshot-refresh when shipping docs
