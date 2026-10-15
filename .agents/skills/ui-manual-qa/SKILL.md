---
name: ui-manual-qa
description: >-
  Agent-driven manual QA of Dashboard, Settings, and History interactions in an
  isolated simulation run. Check the latest current-cycle snapshot, active
  settings, scoped History samples, and account-sync route behavior. Use for UI
  QA, manual testing, interaction smoke tests, or post-frontend changes. Report
  only; do not redesign or implement unless asked.
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
| **Scoped** | User names Dashboard, Settings, History, or a specific control; run that subset plus Global and relevant style checks |

After UI changes or deploys, include the current Dashboard snapshot, Settings
controls, History value samples, and account-sync/API response checks when
relevant. Use laptop evidence for header density as well as phone evidence for
narrow layouts.

**Always use an isolated simulation run.** Never point QA at the operator's
real configuration or database. Never change settings to live trading or make
live Kraken requests.

## Product contracts to verify

1. **Mode plate** — Every retained page shows the configured SIMULATION,
   DRY RUN, or LIVE TRADING mode; simulation takes precedence over dry run.
2. **Stream chip** — Dashboard only; reads STREAM or STALE and describes
   snapshot feed freshness, not trading mode.
3. **Dashboard** — Shows the latest completed in-memory cycle snapshot and
   actions from that cycle. It is not a retrospective timeline.
4. **Settings** — Active controls remain editable. Retired inception, drawdown,
   deployment, and comparison-start keys disappear from the JSON on the next
   Settings save.
5. **History** — Shows directly captured cycle values, account-scoped Kraken
   trades and ledgers, and exchange-reported balance checkpoints. Incomplete
   samples and missing periods are not filled; simulation values remain in a
   separate process-scoped series.
6. **Account sync** — Available only after the authenticated account identity
   is verified. A sync request uses CSRF protection and reports per-feed
   progress; simulation does not import Kraken trades or ledgers.

## Workflow

1. Boot isolated simulation.
2. Open a browser, lock the tab, and wait for a completed Dashboard snapshot.
3. Run the relevant checklist cases; record pass, fail, or blocked.
4. Write QA_DIR/report.md and QA_DIR/findings.json.
5. Stop the app, remove only the isolated RUN_DIR, and present the summary.

### Step 1: Isolated simulation

Run these commands from the repository:

    ./gradlew :backend:fatJar
    RUN_DIR=$(mktemp -d)
    cp rebalancer-config-template.json "$RUN_DIR/rebalancer-config.json"
    # Set simulation: true, dryRun: true, and a short loop delay in the copied file.
    # From RUN_DIR, initialize once with --initialize-data, then start the jar.
    # Wait for curl -sf http://localhost:8080/api/health
    QA_DIR=$(mktemp -d /tmp/ui-manual-qa.XXXXXX)

Simulation is the required safety flag. It exercises cycles through the offline
emulator and writes a process-scoped History value series. It does not
authenticate an account or synchronize Kraken trades and ledgers. Keep both
simulation and dryRun enabled, use placeholder credentials, and never change
simulation off during QA.

### Step 2: Browser setup

Open `http://localhost:8080/`, lock the tab, and inspect Dashboard, Settings, and
History. Hard-refresh once or confirm the stylesheet has a versioned
/static/style.css?v= URL before making styling judgments. Wait for a completed
Dashboard cycle; startup waiting is expected until the short loop delay passes.

Use the screenshot helper's laptop profile for header/status cases and phone
when responsive behavior matters. Prefer browser snapshots for structure and
stable labels. Unlock the browser only after all selected cases finish.

### Step 3: Execute cases

For each selected case:

1. **Setup** — navigate and establish its stated precondition.
2. **Act** — click, type, select, toggle, or wait as specified.
3. **Assert** — inspect visible text, enabled state, resulting snapshot, URL,
   response status, or saved value.
4. **Record** — pass, fail, or blocked with one-line evidence.
5. **Recover** — restore disposable Settings changes before continuing.

After four consecutive interaction failures without new evidence, stop and
report the blocker.

### Step 4: Report

Write QA_DIR/report.md and QA_DIR/findings.json. Keep results factual. Mark
account-backed History synchronization blocked when it cannot be exercised in
simulation, with the safety reason.

### Step 5: Cleanup

Stop the Java process, remove only the isolated RUN_DIR, keep QA_DIR for review,
and unlock the browser tab.

## Interaction principles

1. Prefer one observable assertion per case.
2. Use visible labels and current HtmlIds where available.
3. Wait for completed cycles, SSE updates, Settings saves, and History page
   loads before asserting.
4. Assert visible outcomes rather than relying on console silence.
5. Keep the mode plate and simulation / dry-run cards visible and clear.
6. Restore all Settings mutations before leaving the isolated run.

## Anti-patterns

- Using the operator's real config or database
- Treating simulation History values as authenticated account evidence
- Treating the Dashboard snapshot as the persisted History series
- Treating direct cycle samples as a reconstructed portfolio or return series
- Auto-implementing fixes during QA without a user request
- Checking only mobile width when desktop/laptop layout is in scope

## Checklist

- [ ] Isolated simulation; real config/database untouched
- [ ] Browser locked; current Dashboard snapshot visible
- [ ] [checklist.md](checklist.md) cases run (full or scoped)
- [ ] Failures include expected/actual evidence
- [ ] report.md and findings.json written
- [ ] App stopped; browser unlocked; summary delivered
- [ ] No live-trading changes or unsolicited redesign
