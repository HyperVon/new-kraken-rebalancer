---
name: post-deploy-ui-smoke
description: >-
  After a UI deploy, hard-refresh and smoke-check Dashboard, Settings, and
  History for cache, mode, current-cycle freshness, and retained controls. Use
  when the user reports a production UI issue or requests post-release
  verification.
---

# Post-deploy UI smoke

Run a quick read-only check after a deploy on a host you cannot isolate. This
complements the full [ui-manual-qa](../ui-manual-qa/SKILL.md) checklist; it does
not replace it for broad UI changes. For static captures on a controllable
simulation host, use [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md).

## Preconditions

- Prefer an isolated Simulation instance when you control the host.
- For an operator's running deploy, use read-only clicks. Do not save Settings
  or change Simulation, Dry Run, or live-trading mode.

## Workflow

```text
- [ ] Step 0: Note the base URL and whether the host is isolated
- [ ] Step 1: Hard-refresh or confirm the versioned stylesheet URL
- [ ] Step 2: Check the current mode plate and Dashboard freshness
- [ ] Step 3: Check Dashboard, Settings, History, and History API responses
- [ ] Step 4: Record evidence and report pass/fail/blocked
```

### Step 1 — Refresh static assets

Hard-refresh the page or inspect `<link rel="stylesheet">`. Fail `STYLE-1` from
the [manual QA checklist](../ui-manual-qa/checklist.md) if
`/static/style.css?v=` has no version value.

### Step 2 — Check the header and latest cycle

At laptop/desktop width, use the manual QA cases:

- `GLOBAL-2` — mode plate on Dashboard, Settings, and History matches saved
  settings; SIMULATION takes precedence over DRY RUN.
- `GLOBAL-3` — Dashboard chip is STREAM or STALE and shows the snapshot age;
  it does not imply real orders are executing.
- `DASH-1` — Dashboard shows the latest completed current-cycle snapshot, or
  the expected waiting state before the first cycle.
- `DASH-3` — Asset Performance and over/under-target legend remain readable.

Recent Activity belongs to the latest current cycle. History shows directly
recorded portfolio values and exchange-synchronized trades and ledgers; it does
not reconstruct earlier values or present a benchmark.

### Step 3 — Check retained pages and compatibility routes

- `SETT-1` and `SETT-8` — active Settings controls render without retired
  inception, drawdown, deployment, or retrospective comparison-start fields.
- `HIST-1` and `HIST-2` — History shows process-scoped simulation values and an
  unavailable account-sync state; emulator activity is not shown as account
  trades or ledgers.
- `HIST-5` and `GLOBAL-4` — sync status is an empty list in simulation and
  `/api/history/trades` responds with 503 Service Unavailable.
- `/history` renders the History page; it is not redirected to another page.
- `GLOBAL-6` — health/readiness describe current loop/cycle/snapshot and
  unresolved intent status.

Do not try to create an authenticated observation or benchmark on an operator's
live host. Mark account-backed checks blocked unless they can be exercised in
an approved isolated environment.

### Step 4 — Report

```markdown
# Post-deploy UI smoke
- URL: …
- Host: isolated simulation | existing deploy (read-only)
- STYLE-1: pass/fail
- GLOBAL mode/stream: pass/fail
- DASHBOARD / SETTINGS / HISTORY: pass/fail/blocked
- History APIs: pass/fail/blocked
- Evidence: screenshot or DOM/network observation
- Next: fix branch / hard-refresh instruction / follow-up QA
```

## Checklist

- [ ] Hard-refresh or version query confirmed
- [ ] Mode plate and STREAM/STALE freshness checked
- [ ] Latest current-cycle Dashboard state checked
- [ ] Active Settings and History states checked
- [ ] History page and simulation API responses checked
- [ ] Evidence includes screenshots or DOM/network details
- [ ] No Settings or live-trading changes made on an operator deploy
