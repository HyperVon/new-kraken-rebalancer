---
name: frontend-js-development
description: >-
  Client Kotlin/JS for Dashboard freshness and sorting, active Settings
  allocation controls, and HTMX swap/error handling. Use when editing
  frontend-js/src.
---

# Kotlin/JS Client Development (`:frontend-js`)

Compiles via Kotlin JS IR to `/static/rebalancer.js`.

## Responsibilities

1. Update the Dashboard snapshot age and STREAM/STALE freshness chip.
2. Preserve Dashboard asset-table sorting after current-cycle fragments swap.
3. Keep Settings allocation totals, USD requirement, symbol validation, and
   dynamically added rows in sync with the server-rendered form.
4. Handle HTMX lifecycle events for Settings and order-intent error fragments,
   including current CSRF token updates.

The retained browser pages are Dashboard (`/`), Settings (`/settings`), and
History (`/history`). Dashboard reports the latest current cycle only. History
data and filters are rendered by the server. `History.kt` progressively
enhances its SVG evidence markers with Chart.js tooltips, zoom/pan, view modes,
and expansion; blocked chart dependencies leave the SVG visible. `HistoryViews.kt`
stores named range/filter selections locally. The client does not fetch exchange
history or reconstruct past portfolio values.

## SSE ownership and status semantics

The HTMX SSE extension connects to `/api/status/stream` and asks the Dashboard
fragment to refresh when a current-cycle snapshot arrives. `:frontend-js` does
not open `EventSource` or parse SSE payloads.

`updateAge()` reads the server-rendered observation timestamp and updates the
header chip once per second and after fragment swaps. It toggles the shared
freshness classes and writes `STREAM` or `STALE`, based on the shared
`PrecisionConstants.STALE_THRESHOLD_SECONDS` value. This chip describes stream
freshness; the server-rendered mode plate separately shows SIMULATION, DRY RUN,
or LIVE TRADING.

## HTMX and DOM lifecycle

`main.kt` registers Dashboard and Settings globals once, then handles swaps:

- Recompute the snapshot age and reapply the current table sort.
- Reinitialize Settings totals when the Settings form is present.
- Reveal a rendered Settings or order-intent error after its targeted swap.
- Update hidden CSRF fields from a refreshed response token and preserve form
  fields when a session-expired response is received.
- Swap only server-marked HTML error fragments with an allowed response status
  and HTML content type; do not convert JSON API errors into HTML swaps.

Use generated `:common` catalogs for IDs, attributes, events, selectors, CSS
classes, routes, and user-visible text. Keep null guards around optional DOM
elements. When a test asserts emitted DOM, use an independent raw expected
literal for the contract instead of the catalog value used by production code.

## Dashboard table sorting

Dashboard sorting preserves the selected column and direction when current
cycle rows are replaced. Asset symbols sort as strings; numeric columns prefer
the server-rendered `data-sort-value`, then parse visible text after removing
currency, percent, and whitespace decoration. Keep History tables, trade
formatting, time ranges, filters, and chart evidence
server-rendered. Client chart enhancement must preserve explicit null gaps and
keep asset checkpoints as individual points.

## Settings allocation controls

The allocation total is valid only when it is within the shared tolerance of
100% and includes USD. Invalid totals disable Save. Newly added allocation
symbols are normalized and canonicalized before duplicate checking, so aliases
such as BTC/XBT cannot create duplicate rows. The new row starts with a zero
target and a free palette color when one is available.

Keep the calculation in step with server validation and the active Settings
contract. Retired inception, drawdown, and retrospective comparison controls
must not be added back to the form.

## Current JS tests

Relevant specs are `DashboardTest`, `SettingsTest`, `MainTest`, `HistoryTest`,
and `HistoryViewsTest` under
`frontend-js/src/jsTest/kotlin`. Add focused coverage for changes to freshness,
sorting, allocation checks, or HTMX error handling. Coverage thresholds are
configured in `frontend-js/karma.config.d/coverage.js`.

## Documentation visuals

When a retained page changes visually, follow
[docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md). Its targets
cover Dashboard, Settings, and History. Use
[ui-manual-qa](../ui-manual-qa/SKILL.md) for interaction checks and the visual
review/implementation skills for design changes.

## Checklist

- [ ] HTMX owns SSE transport; JS updates freshness after swaps and on its timer
- [ ] STREAM/STALE freshness remains separate from the trading mode plate
- [ ] Dashboard current-cycle sorting survives fragment replacement
- [ ] Settings requires valid 100% allocation within tolerance and includes USD
- [ ] New symbols are canonicalized before duplicate checking
- [ ] HTMX error swaps remain limited to marked HTML fragments
- [ ] Shared `:common` contracts are used and asserted independently in tests
- [ ] Visual changes refresh retained-page documentation screenshots
