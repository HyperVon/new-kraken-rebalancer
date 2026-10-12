---
name: ktor-html-views
description: >-
  Server-side HTML/CSS for Dashboard, Settings, and Actual: view components,
  DashboardController routes, kotlinx.html/CSS, HTMX, and SSE. Use when changing
  SSR templates, CSS modules, or Ktor UI routes.
---

# Ktor Server-Side HTML & CSS Views

## Active layout

1. `DashboardRoutes.dashboardRouting()` registers `DashboardController`.
2. `DashboardController` serves Dashboard, Settings, Actual, fragments, health,
   readiness, order-intent actions, and the current-cycle SSE stream.
3. `DashboardView` coordinates page rendering.
4. `view/component/` contains `DashboardShellComponent`,
   `DashboardFragmentComponent`, the Dashboard overview/allocation/performance/
   activity components, `SettingsFormComponent`, and
   `ActualObservationsPageComponent`.
5. `view/css/` contains the theme and layout/component style modules.
6. Shared `:common` catalogs define `Routes`, IDs, attributes, selectors,
   visible text, and CSS classes.

The retained user pages are `/`, `/settings`, and `/actual`. `/history` is a
compatibility route that permanently redirects to `/actual`; former
`/api/history/*` routes return 410 Gone. The corresponding legacy route
constants remain in `:common` for this response behavior.

## Type-safe view helpers (`:common` + JVM `HtmlExtensions`)

```kotlin
import com.gemini.krakenbot.view.util.CssClass
import com.gemini.krakenbot.view.util.HtmxAttrs
import com.gemini.krakenbot.view.util.HtmxValues
import com.gemini.krakenbot.view.util.Routes
import com.gemini.krakenbot.view.util.ViewText
import com.gemini.krakenbot.view.util.div // JVM HtmlExtensions

div {
    attributes[HtmxAttrs.HX_GET] = Routes.FRAGMENT_DASHBOARD
    attributes[HtmxAttrs.HX_TRIGGER] = HtmxValues.TRIGGER_LOAD_SSE_MESSAGE
    div(CssClass.Loading.SpinnerContainer) {
        p { +ViewText.CONNECTING }
    }
}
```

Use generated `:common` values for rendered contracts. Boundary tests that
assert markup, IDs, classes, attributes, routes, headers, or CDN values should
use independent raw expected literals rather than the same catalog value used
by the renderer.

## Header, mode, and freshness

Every retained page uses `brandWithMode(settings)` and the primary navigation.
The mode plate reflects persisted Settings, with this precedence:

| Condition | Plate |
| :--- | :--- |
| `settings.simulation` | SIMULATION |
| otherwise `settings.dryRun` | DRY RUN |
| otherwise | LIVE TRADING |

Keep the mode plate and its tooltip visible. Dashboard's separate STREAM/STALE
chip describes the freshness of the latest current-cycle snapshot; it does not
report trading mode. The Dashboard shell holds the mode plate outside the HTMX
fragment, and the fragment refreshes mode from current Settings while updating
`#header-status` out-of-band.

Pass current `Settings` to every page and fragment renderer that displays the
header. For example, `DashboardView.renderActualObservationsPage(settings,
page, ...)` and `renderDashboardFragment(settings, latest, ...)` both need the
same persisted mode information.

## Dashboard and SSE

The Dashboard shows the latest completed in-memory cycle snapshot, including
the current allocation/performance view and activity from that cycle. Its hero
trend uses up to 24 recent snapshots from the same mode and credential scope;
the window resets when that source changes. It is process-local, with no
retrospective report history. Before the first completed cycle, the fragment
shows a waiting state.

`DashboardShellComponent` loads HTMX plus its SSE extension. The shell connects
to `/api/status/stream`; `sse:message` triggers a refreshed
`/fragments/dashboard`. `CurrentPortfolioState` retains the latest snapshot
and a source-scoped bounded window for the hero chart. The JS client updates
age/freshness and preserves table sorting; it does not open `EventSource` or
reconstruct reports.

The Actual page uses `ActualObservationsPageComponent`. It presents directly
observed raw values, evidence gaps, and an optional prospective Buy & Hold
comparison after eligible evidence and continuity verification. It does not
backfill or reconstruct earlier performance.

## HTMX and static assets

- Use `HtmxAttrs` and `Routes` for partial requests and swaps.
- Keep Settings and order-intent error fragments limited to server-marked HTML
  responses with the intended status and content type.
- Keep `#header-status` refreshed via `hx-swap-oob`; the mode plate remains
  independently rendered from current Settings.
- Use `commonMetadataAndStyles()` and `rebalancerJsSrc()` to keep static assets
  cache-busted. Do not hand-roll `/static/...` URLs.
- Do not add ARIA attributes or roles unless explicitly requested; follow
  `.agents/OPERATING.md` for that project constraint.

## Design and security

Reuse `CssTheme` glass, surface, inset-highlight, and shadow tokens rather than
ad hoc color literals. Keep text contrast consistent with the established
theme, and prefer shared component helpers for repeated markup.

Dashboard has no user authentication. CORS is limited by
`isLocalOrPrivateOrigin`; preserve its structural origin parsing and rejection
of hostname-prefix lookalikes when editing the access boundary.

## Documentation and focused checks

Visible changes to Dashboard, Settings, or Actual should refresh the retained
screenshots via [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md)
and update the User Guide when user-facing behavior changes. Use
[ui-manual-qa](../ui-manual-qa/SKILL.md) for interaction checks, and
[ui-visual-review](../ui-visual-review/SKILL.md) followed by
[ui-visual-implement](../ui-visual-implement/SKILL.md) for visual redesigns.

Relevant backend checks include the focused Dashboard cutover/API and view
component tests. Retired History pages, reporting APIs, retrospective chart
sections, and reconstruction tests are not active UI surfaces.

## Checklist

- [ ] New UI belongs to a current page or fragment and uses shared catalogs
- [ ] Every page or fragment header receives current Settings
- [ ] Mode plate precedence remains Simulation, then Dry Run, then Live Trading
- [ ] STREAM/STALE remains current-snapshot freshness only
- [ ] Dashboard uses the latest current-cycle snapshot
- [ ] Actual preserves raw observation gaps and prospective continuity rules
- [ ] `/history` redirects and legacy history API routes return 410
- [ ] SSE refresh, out-of-band freshness swap, and static cache-busting remain intact
- [ ] User-facing visual changes update active screenshots and guide text
