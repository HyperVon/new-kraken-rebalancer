---
name: ui-manual-qa
description: >-
  Exercise Dashboard and Settings interactions in an isolated simulation run.
  Report findings without redesigning or implementing fixes.
---

# UI Manual QA

Exercise retained controls the way an operator would. Use an isolated simulation
run; never use the operator's real configuration, database, or API credentials.
Keep simulation and dryRun enabled and never send live Kraken requests.

## Product contracts

1. Dashboard shows the latest completed in-memory cycle and its current-cycle
   actions. The hero trend and older activity are process-local.
2. The STREAM/STALE chip reports update freshness; the mode plate reports
   SIMULATION, DRY RUN, or LIVE TRADING.
3. Settings exposes the active controls. Retired settings fields disappear from
   saved JSON after the next successful save.
4. Settings allocation totals must equal 100% and include USD. Quality scores
   only suggest targets after the operator presses Calculate from scores and
   saves the form.
5. An ambiguous live order blocks later submissions until its durable intent is
   reviewed and resolved.

## Workflow

1. Start the app from an isolated directory with simulation and dryRun enabled.
2. Open Dashboard and Settings; wait for a completed cycle.
3. Exercise the requested controls and record visible evidence.
4. Restore disposable Settings changes.
5. Stop the app and remove only the isolated run directory.

## Interaction principles

- Assert visible outcomes, enabled state, current-cycle snapshot, route, or saved
  values rather than console silence.
- Wait for completed cycles, SSE updates, and Settings saves before asserting.
- Keep the mode plate and safety controls visible.
- Do not implement fixes during QA unless separately asked.

## Checklist

- [ ] Isolated simulation; real config and account data untouched
- [ ] Dashboard cycle and Settings controls visible
- [ ] Results include expected and actual evidence
- [ ] App stopped and temporary data removed
