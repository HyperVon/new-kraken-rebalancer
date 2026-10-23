---
name: post-deploy-ui-smoke
description: >-
  After a UI deploy, hard-refresh and smoke-check Dashboard and Settings for
  cache, mode, current-cycle freshness, and retained controls. Use
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
- [ ] Step 3: Check Dashboard, Settings, and retired History routes
- [ ] Step 4: Record evidence and report pass/fail/blocked
```

### Step 1 — Refresh static assets

Hard-refresh the page or inspect `<link rel="stylesheet">`. Fail `STYLE-1` from
the [manual QA checklist](../ui-manual-qa/checklist.md) if
`/static/style.css?v=` has no version value.

### Step 2 — Check the header and latest cycle

At laptop/desktop width, use the manual QA cases:

- `GLOBAL-2` — mode plate on Dashboard and Settings matches saved
  settings; SIMULATION takes precedence over DRY RUN.
- `GLOBAL-3` — Dashboard chip is STREAM or STALE and shows the snapshot age;
  it does not imply real orders are executing.
- `DASH-1` — Dashboard shows the latest completed current-cycle snapshot, or
  the expected waiting state before the first cycle.
- `DASH-3` — Asset Performance and over/under-target legend remain readable.

Recent Activity belongs to the latest current cycle. The Dashboard trend is
bounded, process-local, and resets when its source changes.

### Step 3 — Check retained pages and retired routes

- `SETT-1` and `SETT-8` — active Settings controls render without retired
  inception, drawdown, deployment, or retrospective comparison-start fields.
- `/history` and `/api/history/*` are retired and do not expose retrospective data.
- `GLOBAL-6` — health/readiness describe current loop/cycle/snapshot and
  unresolved intent status.

Run only read-only checks on an operator's live host.

### Step 4 — Report

```markdown
# Post-deploy UI smoke
- URL: …
- Host: isolated simulation | existing deploy (read-only)
- STYLE-1: pass/fail
- GLOBAL mode/stream: pass/fail
- DASHBOARD / SETTINGS: pass/fail/blocked
- Retired History routes: absent / unexpected response
- Evidence: screenshot or DOM/network observation
- Next: fix branch / hard-refresh instruction / follow-up QA
```

## Checklist

- [ ] Hard-refresh or version query confirmed
- [ ] Mode plate and STREAM/STALE freshness checked
- [ ] Latest current-cycle Dashboard state checked
- [ ] Active Settings state checked
- [ ] Retired History routes do not expose data
- [ ] Evidence includes screenshots or DOM/network details
- [ ] No Settings or live-trading changes made on an operator deploy
