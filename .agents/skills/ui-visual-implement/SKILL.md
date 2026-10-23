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
2. Make the smallest coherent change across desktop and phone layouts.
3. Keep labels, HTML IDs, CSS classes, and accessibility states consistent with
   shared catalogs.
4. Update the User Guide and refresh retained-page screenshots when the UI
   changes.
5. Report the changed surfaces and any verification that was performed.

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

- [ ] Dashboard and Settings remain usable at desktop and phone widths
- [ ] Active controls and safety labels remain understandable
- [ ] Shared catalogs remain consistent with rendered markup
- [ ] Documentation matches any changed UI
