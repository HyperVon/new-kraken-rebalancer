---
name: ui-visual-implement
description: >-
  Apply an approved visual change to Dashboard or Settings and verify the
  retained interface.
---

# UI Visual Implementation

Implement the visual change the user requested or approved. Preserve Rebalancer
behavior and active Settings functionality.

## Working method

1. Locate the server-rendered component, CSS, and client interaction involved.
2. Judge every visual change at laptop **~1280–1440px** first, then phone
   (~390px). Use
   `.agents/skills/docs-screenshot-refresh/scripts/capture_screenshots.py
   --profile laptop,phone` for repeatable evidence.
3. Verify the stylesheet link is cache-busted (`/static/style.css?v=…`).
   White native-looking OS buttons mean stale CSS — hard-refresh before
   judging.
4. Make the smallest coherent change across desktop and phone layouts.
5. Keep labels, HTML IDs, and CSS classes consistent with shared catalogs. Do
   not add or "improve" accessibility metadata (no `aria-*`) unless explicitly
   requested — consistency means leaving existing metadata unchanged.
6. Re-check the known misses after edits: concatenated deviation legend
   ("Over targetUnder target"), squished STREAM/STALE + relative-age cluster on
   laptop widths, clipped or empty Cash/Crypto summary tiles, and mode-plate
   visibility.
7. Update the User Guide and refresh retained-page screenshots when the UI
   changes. Even when canonical screenshots are unaffected, take a temp capture
   when the change claims a visual fix.
8. Report the changed surfaces and any verification that was performed.

## Visual contract

- The navigation, loop state, and mode plate remain clear on Dashboard and
  Settings.
- Dashboard focuses on the latest cycle, allocation, asset performance,
  activity, and operational safety.
- Settings retains loop timing, triggers, safety modes, allocation editing, and
  quality-score target suggestions.
- Responsive layouts avoid clipped tables and controls.
- STREAM/STALE describes feed freshness and is separate from trading mode.

## Checklist

- [ ] Dashboard and Settings remain usable at laptop 1280–1440px and phone widths
- [ ] Stylesheet is cache-busted and judged after a hard refresh
- [ ] Known misses re-checked: legend separation, STREAM/STALE cluster, tiles, mode plate
- [ ] No new ARIA / accessibility metadata added
- [ ] Active controls and safety labels remain understandable
- [ ] Shared catalogs remain consistent with rendered markup
- [ ] Documentation matches any changed UI
