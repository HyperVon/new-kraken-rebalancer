---
name: ui-manual-qa
description: >-
  Agent-driven manual QA of Dashboard, Settings, and Actual interactions in an
  isolated simulation run. Check the latest current-cycle snapshot, active
  settings, Actual evidence states, and legacy route behavior. Use for UI QA,
  manual testing, interaction smoke tests, or post-frontend changes. Report only;
  do not redesign or implement unless asked.
---

# UI Manual QA

Exercise retained UI controls the way an operator would: open pages, click or
edit controls, and confirm visible results. Prefer browser evidence (snapshot
and screenshot) over code inspection alone.

This is functional QA, not a visual design critique. For appearance findings,
use [ui-visual-review](../ui-visual-review/SKILL.md); for post-deploy hard-refresh
checks, use [post-deploy-ui-smoke](../post-deploy-ui-smoke/SKILL.md).

| Skill | Concern |
| :--- | :--- |
| **ui-manual-qa** (this) | Do the retained page controls work? |
| [post-deploy-ui-smoke](../post-deploy-ui-smoke/SKILL.md) | Did deploy + cache leave the UI broken? |
| [ui-visual-review](../ui-visual-review/SKILL.md) | Does it look right? (recommend only) |
| [ui-visual-implement](../ui-visual-implement/SKILL.md) | Apply approved visual findings |
| [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md) | Refresh active-page screenshots |
| [write-kotest](../write-kotest/SKILL.md) | Automated unit/integration tests |

Full case list → [checklist.md](checklist.md) (read before Step 3).

## Scope

| Mode | When |
| :--- | :--- |
| **Full suite** (default) | User asks to QA/smoke the UI, after UI/CSS/client deploy, or after broad UI work |
| **Scoped** | User names Dashboard, Settings, Actual, or a specific control; run that subset plus Global and relevant style checks |

After UI changes or deploys, include the current Dashboard snapshot, Settings
controls, Actual state, and legacy `/history` redirect/API response checks when
relevant. Use laptop evidence for header density as well as phone evidence for
narrow layouts.

**Always use an isolated simulation run.** Never point QA at the operator's
real configuration or database. Never change settings to live trading or make
live Kraken requests.

## Product contracts to verify

1. **Mode plate** — Every retained page shows the configured `SIMULATION`,
   `DRY RUN`, or `LIVE TRADING` mode; simulation takes precedence over dry run.
2. **Stream chip** — Dashboard only; reads `STREAM` or `STALE` and describes
   snapshot feed freshness, not trading mode.
3. **Dashboard** — Shows the latest completed in-memory cycle snapshot and
   actions from that cycle. It is not a retrospective timeline.
4. **Settings** — Active controls remain editable and save correctly. Retired
   history-only inception/drawdown/deployment/comparison keys disappear from
   the JSON on the next Settings save.
5. **Actual** — Shows raw forward observations and evidence status. Incomplete
   observations and missing coverage stay visible as gaps; simulation does not
   display emulator balances as authenticated Actual data.
6. **Prospective Buy & Hold** — Available only from an eligible complete
   observation after account/event continuity is verified; it uses forward
   observations and is not a retrospective backtest.
7. **Legacy routes** — `/history` redirects permanently to `/actual`; former
   `/api/history/*` reporting endpoints return `410 Gone`.

## Workflow

```text
- [ ] Step 0: Confirm scope and output directory
- [ ] Step 1: Boot isolated simulation (same as docs-screenshot-refresh)
- [ ] Step 2: Open browser, lock tab, wait for a completed Dashboard snapshot
- [ ] Step 3: Run relevant checklist cases; record pass / fail / blocked
- [ ] Step 4: Write QA_DIR/report.md + findings.json
- [ ] Step 5: Stop app, clean RUN_DIR, and present summary
```

### Step 1: Isolated simulation

```bash
./gradlew :backend:fatJar
RUN_DIR=$(mktemp -d)
cp rebalancer-config-template.json "$RUN_DIR/rebalancer-config.json"
# set simulation: true and a short loop delay
# start jar from RUN_DIR; wait for curl -sf http://localhost:8080/api/health
```

Simulation is the required safety flag. It exercises current cycles through the
offline emulator; it does not create authenticated Actual observations. Use
`dryRun: false` only for emulator order/activity visibility. Keep placeholder
credentials and never change simulation off during QA.

```bash
QA_DIR=$(mktemp -d /tmp/ui-manual-qa.XXXXXX)
```

### Step 2: Browser setup

Use the browser tool to open `http://localhost:8080/`, lock the tab, and inspect
Dashboard, Settings, and Actual. Hard-refresh once or confirm the stylesheet
has a versioned `/static/style.css?v=` URL before making styling judgments.
Wait for a completed Dashboard cycle; a startup waiting state is not a failure
until the configured short loop delay has elapsed.

Use the screenshot helper's laptop profile for header/status cases and phone
when responsive evidence matters. Prefer browser snapshots for structure and
stable labels. Unlock the browser only after all selected cases finish.

### Step 3: Execute cases

For each selected case:

1. **Setup** — navigate and establish its stated precondition.
2. **Act** — click, type, select, toggle, or wait as specified.
3. **Assert** — inspect visible text, enabled state, resulting snapshot, URL,
   response status, or saved value.
4. **Record** — `pass`, `fail`, or `blocked` with one-line evidence.
5. **Recover** — restore disposable Settings changes before continuing.

After four consecutive interaction failures without new evidence, stop and
report the blocker.

### Step 4: Report

Write `$QA_DIR/report.md` and `$QA_DIR/findings.json`. Chat summary:

```markdown
# UI manual QA

## Summary
- Scope: full | Dashboard | Settings | Actual
- Result: N passed / N failed / N blocked
- Simulation: on; dryRun: on/off
- Artifacts: `$QA_DIR`

## Failures
### [P0|P1|P2] CASE-ID — Title
- **Page**: Dashboard | Settings | Actual | Global
- **Steps**: what you did
- **Expected**: …
- **Actual**: … (screenshot / snapshot note)
- **Repro**: minimal steps

## Passed (compact)
- GLOBAL-1, DASH-1, … (ids only)
```

Keep results factual. Account-backed Actual capture or a prospective benchmark
that cannot be exercised in the isolated simulation should be marked blocked,
with the safety reason.

### Step 5: Cleanup

Stop the Java process, remove only the isolated `$RUN_DIR`, keep `$QA_DIR` for
review, and unlock the browser tab.

## Interaction principles

1. Prefer one observable assertion per case.
2. Use visible labels and current `HtmlIds` where available.
3. Wait for completed cycles, SSE updates, Settings saves, and Actual page loads
   before asserting.
4. Assert visible outcomes rather than relying on console silence.
5. Keep mode plate and Simulation / Dry Run cards visible and clear.
6. Settings mutations must be restored before leaving the isolated run.

## Anti-patterns

- Using the operator's real config or database
- Treating simulation balances as Actual evidence
- Treating the Dashboard snapshot as a persisted timeline
- Presenting prospective Buy & Hold without verified continuity
- Calling `/history` a retained page or expecting retired reporting APIs to work
- Auto-implementing fixes during QA without a user request
- Checking only mobile width when desktop/laptop layout is in scope

## Checklist

- [ ] Isolated simulation; real config/database untouched
- [ ] Browser locked; current Dashboard snapshot visible
- [ ] [checklist.md](checklist.md) cases run (full or scoped)
- [ ] Failures include expected/actual evidence
- [ ] `report.md` and `findings.json` written
- [ ] App stopped; browser unlocked; summary delivered
- [ ] No live-trading changes or unsolicited redesign
