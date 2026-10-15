# UI Manual QA — Interaction Checklist

Read this from [SKILL.md](SKILL.md) Step 3. Mark each selected case pass,
fail, or blocked in $QA_DIR. Prefer visible labels and shared IDs from :common.

Stable controls include save-button, total-allocated-display,
allocations-container, new-symbol-input, csrf-token, the Dashboard
header-status, and the History value chart and filter IDs. Do not look for the
removed Actual or Buy & Hold controls.

## Stylesheet and global navigation

| ID | Steps | Expected |
| :--- | :--- | :--- |
| STYLE-1 | Hard-refresh / or inspect the stylesheet link | /static/style.css?v= has a non-empty version query. |
| STYLE-2 | Inspect retained page headers at laptop width | Dashboard, Settings, and History headers remain readable and unclipped. |
| GLOBAL-1 | Open /; click Settings, then Dashboard, then History | URLs are /settings, /, /history; active nav marks the current page. |
| GLOBAL-2 | Inspect the brand and mode plate on each page | Brand is present and plate reports the configured mode; SIMULATION takes precedence over DRY RUN. |
| GLOBAL-3 | Inspect Dashboard status cluster | STREAM or STALE and relative age are shown; neither indicates the trading mode. |
| GLOBAL-4 | Open /api/history/trades during simulation | Response is 503 Service Unavailable; no emulator activity is presented as account trades. |
| GLOBAL-5 | Open /api/health and /api/readiness | Health reports process/current state; readiness reports unresolved intent or current-loop conditions. |
| GLOBAL-6 | Click pause then resume on a retained page | Loop state changes visibly and the control remains usable. |

Run header checks at the capture helper's laptop profile (1280px); include
phone width when responsive behavior is in scope.

## Dashboard (/)

Wait for one completed cycle; before then, the waiting state is expected.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| DASH-1 | Load / and wait for the first completed cycle | Dashboard shows the latest current-cycle snapshot, not the persisted History series. |
| DASH-2 | Inspect portfolio overview and Cash / Crypto allocation | Current total and configured allocations render without implying historical returns. |
| DASH-3 | Inspect Asset Performance and deviation legend | Current asset rows render; Over target and Under target indicators are readable. |
| DASH-4 | Inspect Recent Activity | Actions from the latest cycle are shown, or a clear no-action state appears. |
| DASH-5 | Sort an asset table column, then wait for an SSE fragment refresh | Sort direction and selected column remain applied after the current snapshot updates. |
| DASH-6 | Wait one configured loop interval or SSE update | Fragment/age refreshes without a blank or broken intermediate page. |

## Settings (/settings)

Mutate only the throwaway simulation configuration. Restore any changed value
before continuing.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| SETT-1 | Open Settings | Active global parameters, safety modes, target allocations, and quality-score controls render. |
| SETT-2 | Change loop delay by one step and save | Save succeeds; the changed active value remains after reload. |
| SETT-3 | Restore loop delay and save | Original disposable test value is restored. |
| SETT-4 | Toggle Dry Run and save; keep Simulation on | Mode plate and safety cards match saved settings; Simulation remains enabled throughout QA. |
| SETT-5 | Inspect allocation total and Save state | A valid total near 100% including USD enables Save; an invalid total or missing USD disables it. |
| SETT-6 | Add a disposable new symbol | A canonical non-duplicate row appears with a zero target; symbol aliases cannot duplicate an existing asset. |
| SETT-7 | Restore the allocation rows and save | The original valid allocation is restored without empty or duplicate rows. |
| SETT-8 | Inspect settings form for retired fields | No inception, drawdown, deployment, or comparison-start control is present. |
| SETT-9 | If using a disposable legacy config, save once and inspect that copy | Retired history-only keys are omitted; active settings remain, and the operator's config is untouched. |

## History (/history)

Simulation History values come from emulator cycles and have their own
process-session scope. Account trade and ledger sync must remain unavailable;
do not disable simulation to force an authenticated account check.

| ID | Steps | Expected |
| :--- | :--- | :--- |
| HIST-1 | Open /history and wait for at least two completed cycles | Portfolio Value Over Time shows the simulation samples; the account-sync banner says account identity is not verified. |
| HIST-2 | Inspect the page sections | Portfolio value, asset holdings, Trade History, and Transactions & Ledgers sections render with clear empty states when account data is unavailable. |
| HIST-3 | Select a time range | The browser stays on /history and the selected range is marked active. |
| HIST-4 | Submit asset, pair, and type filters in simulation | The page retains the filter values and does not display emulator activity as account trades or ledgers. |
| HIST-5 | Open /api/history/sync-status | A valid empty status list is returned for the process-scoped simulation source. |
| HIST-6 | If account-backed evidence is unavailable | Mark authenticated sync, trade/ledger pagination, and exchange checkpoints blocked; do not disable simulation or use operator credentials. |

## Error and session behavior

| ID | Steps | Expected |
| :--- | :--- | :--- |
| ERROR-1 | Submit an invalid Settings value in the disposable config | The rendered error remains visible, edited form values are retained, and Save can be retried after correction. |
| ERROR-2 | If a test session expires, submit the current Settings form | Browser refreshes the hidden CSRF token, preserves edited values, and explains that the rejected action must be retried. |
| ERROR-3 | If an unresolved live-order intent can be represented in an isolated test | Dashboard shows the unresolved status and evidence-based resolution controls; do not make live orders during manual QA. |

## Optional narrow-layout check

| ID | Steps | Expected |
| :--- | :--- | :--- |
| OPT-1 | Open Dashboard, Settings, and History at phone width (~390px) | Nav, status, forms, charts, filters, and tables remain usable or any limitation is documented. |
| OPT-2 | Compare one route at laptop and desktop widths | Content reflows without clipping or hidden controls. |

## Maintenance

When a retained page gains or changes a control, add a focused case here using
the same ID style. Do not restore tests for Actual or Buy & Hold; keep direct
sample gaps and account identity boundaries explicit in History cases.
