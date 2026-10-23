---
name: frontend-js-development
description: >-
  Maintain Kotlin/JS interactions for Dashboard freshness and sorting, active
  Settings allocation controls, and HTMX lifecycle handling.
---

# Kotlin/JS Client Development

The Kotlin/JS module compiles to the browser bundle. The retained pages are
Dashboard and Settings; server-rendered HTML owns page content.

## Responsibilities

1. Update the Dashboard snapshot age and STREAM/STALE freshness chip.
2. Preserve Dashboard asset-table sorting after current-cycle fragments swap.
3. Keep Settings allocation totals, USD requirement, symbol validation, and
   dynamically added rows in sync with server validation.
4. Handle HTMX lifecycle events for Settings and order-intent error fragments,
   including CSRF token updates.

## SSE and lifecycle

The HTMX SSE extension connects to the status stream and requests a Dashboard
fragment after current-cycle snapshots arrive. Kotlin/JS does not open
EventSource or parse SSE payloads.

Update snapshot age after fragment swaps and on its timer. STREAM/STALE reports
feed freshness; the server-rendered mode plate reports simulation, dryRun, or
live trading independently.

## Sorting and Settings

Preserve the selected Dashboard sort column and direction when rows are
replaced. Numeric columns prefer server-rendered sort values, then parse visible
text.

The Settings allocation total is valid only when it is within tolerance of
100% and includes USD. Invalid totals disable Save. Normalize new symbols before
duplicate checking.

## Tests and documentation

Relevant browser specs are DashboardTest, SettingsTest, and MainTest under
frontend-js/src/jsTest. Add focused coverage for changes to freshness, sorting,
allocation checks, or HTMX error handling.

When a retained page changes visually, follow the screenshot refresh guidance.
Keep shared catalogs and rendered markup consistent.
