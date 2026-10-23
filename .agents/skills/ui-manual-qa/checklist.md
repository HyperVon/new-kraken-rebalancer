# UI Manual QA — Interaction Checklist

Read this from SKILL.md. Mark each selected case pass, fail, or blocked in the
isolated QA directory. Prefer visible labels and shared IDs.

## Stylesheet and global navigation

| ID | Steps | Expected |
| :--- | :--- | :--- |
| STYLE-1 | Hard-refresh / or inspect the stylesheet link | The stylesheet has a non-empty version query. |
| STYLE-2 | Inspect headers at laptop width | Dashboard and Settings headers remain readable and unclipped. |
| GLOBAL-1 | Open /; click Settings, then Dashboard | Routes are /settings and /; active nav marks the current page. |
| GLOBAL-2 | Inspect brand and mode plate | Brand is present; SIMULATION takes precedence over DRY RUN. |
| GLOBAL-3 | Inspect Dashboard status cluster | STREAM or STALE and relative age are shown separately from trading mode. |
| GLOBAL-4 | Open /api/health and /api/readiness | Health reports current state; readiness reports loop and intent conditions. |
| GLOBAL-5 | Pause and resume the loop | State changes visibly and the control remains usable. |

Run header checks at laptop width and include phone width when responsive
behavior is in scope.

## Dashboard

Wait for one completed cycle; the waiting state is expected before then.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| DASH-1 | Load / and wait for the first completed cycle | Dashboard shows the latest current-cycle snapshot. |
| DASH-2 | Inspect total, Cash, and Crypto overview | Current value and configured allocations render clearly. |
| DASH-3 | Inspect allocation and performance sections | Current asset rows and over/under-target indicators are readable. |
| DASH-4 | Inspect Recent Activity | Current process activity is shown, or a clear no-action state appears. |
| DASH-5 | Sort an asset-table column, then wait for an SSE refresh | Sort direction and column remain selected. |
| DASH-6 | Wait for the next cycle update | Snapshot and age refresh without a broken intermediate page. |

## Settings

Change only the throwaway simulation configuration. Restore changed values.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| SETT-1 | Open Settings | Active parameters, safety modes, allocations, and score controls render. |
| SETT-2 | Change loop delay and save | The active value remains after reload. |
| SETT-3 | Restore loop delay and save | The disposable value is restored. |
| SETT-4 | Toggle Dry Run while Simulation remains on | Mode plate and safety cards match; Simulation stays enabled. |
| SETT-5 | Inspect allocation total and Save state | Valid 100% allocation including USD enables Save. |
| SETT-6 | Add a disposable symbol | A canonical non-duplicate row appears with a zero target. |
| SETT-7 | Restore allocations and save | The original valid allocation is restored. |
| SETT-8 | Inspect for retired configuration fields | No retired inception, drawdown, deployment, or comparison controls appear. |

## Error and responsive behavior

| ID | Steps | Expected |
| :--- | :--- | :--- |
| ERROR-1 | Submit an invalid disposable Settings value | Error and edited values remain visible for correction. |
| ERROR-2 | If a session expires, submit Settings | The token refreshes and entered values remain. |
| ERROR-3 | If an uncertain intent is represented in an isolated environment | Dashboard shows evidence-based resolution controls; no live order is placed. |
| OPT-1 | Open Dashboard and Settings at phone width | Navigation, status, forms, and tables reflow without clipping. |
| OPT-2 | Compare one page at laptop and desktop widths | Content reflows without hidden controls. |

## Maintenance

Add a focused case when a retained page gains or changes a control. Keep all
checks isolated from operator credentials and live trading.
