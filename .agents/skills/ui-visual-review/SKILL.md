---
name: ui-visual-review
description: >-
  Review Dashboard and Settings visuals in simulation mode and report concrete
  design findings without changing code.
---

# UI Visual Review

Review the retained pages at desktop and phone widths. Use screenshots and
browser structure when available. Report specific location, observed behavior,
impact, and a practical improvement. Do not implement changes during review.

## Pages

| Page | Route | Main content |
| --- | --- | --- |
| Dashboard | / | Latest cycle, allocation bars, performance table, current-cycle activity, health and live-order safety |
| Settings | /settings | Loop controls, thresholds, simulation and dryRun, target allocations, optional quality-score preview |

## Review points

- Confirm the navigation, mode plate, and loop control remain legible and aligned.
- Dashboard allocation bars and performance table should support the current
  Rebalancer workflow without redundant portfolio summary cards.
- Dashboard activity and live-order action-required content should be scannable.
- Settings should make safety modes, allocation targets, and score preview easy
  to distinguish.
- Check responsive reflow, contrast, spacing, overflow, and control states.
- Separate stream freshness from trading mode and account for paused states.

## Report format

For each finding, include page, viewport, location, evidence, severity, and a
concrete recommendation. If a page is sound, say so briefly. Do not add
hypothetical findings or redesign the application's strategy.
