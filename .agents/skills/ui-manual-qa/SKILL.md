---
name: ui-manual-qa
description: >-
  Agent-driven manual QA of live Dashboard / Settings / History interactions in
  simulation mode — click every control, verify charts/forms/nav/SSE behavior,
  and report pass/fail. Use when the user asks for UI QA, manual testing,
  interaction smoke test, click-through testing, or to verify the UI still works
  after frontend/SSR/CSS changes. For post-deploy hard-refresh smoke only, use
  post-deploy-ui-smoke. Run the **full** checklist (including STYLE-* and
  REGRESSION-*) after UI work — not only mobile viewport. Report only; do not
  redesign (ui-visual-review) or implement visual polish (ui-visual-implement)
  unless asked to fix a found bug.
---

# UI Manual QA (agent click-through)

Exercise **every interactive control** on the live app the way a human QA
tester would: open pages, click / type / toggle, and confirm the UI responds
correctly. Prefer evidence from the browser (snapshot + screenshot) over code
inspection alone.

This skill is **functional** — not a visual design critique. For a short
post-deploy hard-refresh smoke (stale CSS, mode plate, STREAM/STALE, Day · Total
only), use [post-deploy-ui-smoke](../post-deploy-ui-smoke/SKILL.md).

| Skill | Concern |
| :--- | :--- |
| **ui-manual-qa** (this) | Does each control work? |
| [post-deploy-ui-smoke](../post-deploy-ui-smoke/SKILL.md) | Did deploy + cache leave the UI broken? |
| [ui-visual-review](../ui-visual-review/SKILL.md) | Does it look right? (recommend only) |
| [ui-visual-implement](../ui-visual-implement/SKILL.md) | Apply approved visual findings |
| [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md) | Refresh README PNGs |
| [write-kotest](../write-kotest/SKILL.md) | Automated unit/JS tests |

Related: [ktor-html-views](../ktor-html-views/SKILL.md),
[frontend-js-development](../frontend-js-development/SKILL.md),
[user-guide](../user-guide/SKILL.md),
[dry-run-and-simulation](../dry-run-and-simulation/SKILL.md).

Full case list → [checklist.md](checklist.md) (read it before Step 3).

---

## Scope

| Mode | When |
| :--- | :--- |
| **Full suite** (default) | User says “QA the UI”, “manual test”, “smoke the app”, **after UI/CSS/frontend deploy**, or after broad UI work |
| **Scoped** | User names a page or feature (e.g. “QA History views + zoom only”) — run that subset + Global nav + **STYLE-1/2** |

After any UI/CSS/JS change or production deploy, default to the **full**
checklist — especially `STYLE-*`, `REGRESSION-*`, `GLOBAL-5`/`GLOBAL-7` (mode
plate + STREAM/STALE), strengthened `HIST-VIEW-2`, and `HIST-ZOOM-*` (zoom vs
pan/scrubber). For History zoom work, **always** run HIST-ZOOM-5/6/7 and watch
the **chart**, not only the scrubber thumb. Do not treat mobile-only checks as
sufficient for desktop/laptop layout regressions.

**Always in simulation** on an isolated run directory. Never point QA at the
user’s real `rebalancer-config.json` / DB. Never flip live trading flags.

**Out of scope unless asked:** fixing bugs, visual redesign, rewriting docs,
changing trading math, live Kraken API calls.

---

## Redesign semantics (assert these)

Keep these visible contracts in mind while running cases:

1. **Mode plate (every page)** — Brand-adjacent plate shows **SIMULATION**,
   **DRY RUN**, or **LIVE TRADING** with an explanatory tooltip. Precedence:
   simulation → dry run → live trading.
2. **Stream chip (Dashboard)** — Reads **STREAM** / **STALE**, never
   **LIVE** / **DELAYED** (that wording meant stream health, not trading mode).
3. **Dashboard hero** — Total Portfolio + 24H delta/sparkline; Cash/Crypto tiles
   with bars, target, and deviation/meta.
4. **Recent Activity** — Grouped by rebalance cycle; **View all history** →
   `/history`.
5. **Settings safety** — Simulation / Dry Run are rich cards with visible
   **ON** / **OFF** state (not bare checkboxes in the numeric grid).
6. **History** — Six summary cards; chart title + zoom in one header row; net
   cash flow caption; successful trade rows use a subtle status dot; zero USD
   price/fee show em dash; failed/dry-run badges remain.
7. **Async freshness & protection** — Verify timeframe changes cleanly cancel/supersede
   in-flight queries; Settings submit buttons indicate pending state during in-flight saves;
   semantic HTML used without `aria-*` attributes.

---

## Workflow

```text
- [ ] Step 0: Confirm scope (full vs page/feature) and output dir
- [ ] Step 1: Boot isolated simulation (same as docs-screenshot-refresh)
- [ ] Step 2: Open browser → lock tab → wait for seeded data
- [ ] Step 3: Run checklist cases; record pass / fail / blocked
- [ ] Step 4: Write QA_DIR/report.md + findings.json
- [ ] Step 5: Stop app; clean RUN_DIR; present summary + next actions
```

### Step 1: Isolated simulation

```bash
./gradlew :backend:fatJar
RUN_DIR=$(mktemp -d)
cp rebalancer-config-template.json "$RUN_DIR/rebalancer-config.json"
# set simulation: true, loopDelaySeconds: 15
# start jar from $RUN_DIR; wait for curl -sf http://localhost:8080/api/health
```

Prefer `dryRun: false` under simulation so trade rows and activity look realistic
(emulator fills). Use `dryRun: true` only when testing dry-run badges / filter.

```bash
QA_DIR=$(mktemp -d /tmp/ui-manual-qa.XXXXXX)
```

### Step 2: Browser setup

Use the **cursor-ide-browser** MCP (navigate → lock → snapshot/screenshot):

1. `browser_navigate` → `http://localhost:8080/`
2. `browser_lock` `{ action: "lock" }`
3. **Post-deploy / cache:** hard-refresh once (or confirm `/static/style.css?v=`
   in the stylesheet link) before style assertions — stale 24h CSS makes controls
   look like default browser buttons (`STYLE-1`).
4. Wait until Dashboard shows portfolio hero / tiles (not empty seed flash) —
   poll health / snapshot / screenshot until data age is recent.
5. For header/status and `REGRESSION-*` cases, use the screenshot helper's
   `laptop` profile (1280px) in addition to `phone` when static visual evidence
   is needed; do not rely on mobile-only evidence.
6. Prefer `browser_snapshot` for structure + refs. For standalone page captures,
   use `capture_screenshots.py --profile laptop,phone --out-dir "$QA_DIR"`;
   use `browser_take_screenshot` only when asserting a visual state immediately
   after an interaction that the capture script cannot reproduce.
7. Unlock only when **all** cases for this run are finished.

If a control has no stable accessibility name, use DOM ids from
`:common` `HtmlIds` / visible `ViewText` labels (see checklist).

### Step 3: Execute cases

For each case in [checklist.md](checklist.md) (or the scoped subset):

1. **Setup** — navigate / preconditions.
2. **Act** — click, type, select, toggle, scroll as specified.
3. **Assert** — expected DOM text, enabled/disabled state, chart rebuild,
   table rows, toast/alert, URL, or localStorage side effect.
4. **Record** — `pass` | `fail` | `blocked` with one-line evidence.
5. **Recover** — if a fail leaves the UI dirty, reset via reload or Undo path
   before the next case (especially Settings mutations — restore values or
   re-copy template config and restart only if necessary).

**Hard stop rule:** After **4** consecutive interaction failures with no new
evidence, stop the suite, report what blocked progress, and ask how to proceed.

Do **not** skip Global nav cases even in a scoped History/Settings run — broken
routing makes other results meaningless.

### Step 4: Report

Write `$QA_DIR/report.md` and `$QA_DIR/findings.json`. Chat summary:

```markdown
# UI manual QA

## Summary
- Scope: full | History | …
- Result: N passed / N failed / N blocked
- Simulation seed: yes; dryRun: on/off
- Artifacts: `$QA_DIR`

## Failures
### [P0|P1|P2] CASE-ID — Title
- **Page**: Dashboard | Settings | History | Global
- **Steps**: what you did
- **Expected**: …
- **Actual**: … (screenshot / snapshot note)
- **Repro**: minimal steps

## Passed (compact)
- GLOBAL-1, DASH-1, … (ids only)

## Suggested next steps
- Fix failures in chat, or open a follow-up implement pass
- Optional: ui-visual-review if failures look like design/layout
```

`findings.json` shape (for handoff):

```json
{
  "qaDir": "/tmp/ui-manual-qa.XXX",
  "generatedAt": "ISO-8601",
  "scope": "full",
  "results": [
    {
      "id": "HIST-ZOOM-1",
      "status": "fail",
      "severity": "P1",
      "page": "History",
      "title": "Zoom + expands x-axis",
      "expected": "…",
      "actual": "…",
      "evidence": "screenshot or snapshot note"
    }
  ]
}
```

Severity: **P0** broken navigation / data loss / unsafe mode confusion;
**P1** control does nothing or wrong result; **P2** polish / edge / flaky timing.

### Step 5: Cleanup

Stop the Java process, `rm -rf "$RUN_DIR"`. Keep `$QA_DIR` until the user is
done reviewing. Unlock the browser tab.

---

## Interaction principles

1. **One assertion per case** where possible — easier triage.
2. **Prefer labeled controls** (`ViewText`) and `HtmlIds` over brittle CSS paths.
3. **Wait for async** — History sync banner, chart rebuild after range change,
   HTMX/SSE dashboard refresh; snapshot again after settle.
4. **Do not trust console silence** — assert visible outcome.
5. **Safety modes stay obvious** — Settings Simulation / Dry Run cards must
   remain reachable with clear ON/OFF; header mode plate must stay visible on
   every page. Never hide them while “cleaning up” after a case.
6. **Settings mutations are reversible** — change a disposable field, save, verify
   hot-reload signal if observable, then restore prior values and save again
   before leaving the page (throwaway DB, but keep later cases predictable).

---

## Anti-patterns

- Judging “works” from static screenshots without clicking
- Using the user’s real config/DB
- Declaring pass when charts are blank / still syncing
- Auto-implementing fixes mid-suite without user ask (finish the report first)
- Confusing this skill with ui-visual-review (looks) or Kotest (unit coverage)
- Skipping zoom / view-preset / allocation add-remove because they feel “extra”
- QA only at mobile width and missing desktop header density / glass-button regressions
- Skipping `STYLE-*` after a deploy and mis-reporting styled controls as “broken”
- Expecting **LIVE** / **DELAYED** on the stream chip (it is **STREAM** / **STALE**)
- Calling HIST-ZOOM-6 pass because the scrubber thumb moved while **time ticks /
  series stayed put** (chart must pan — usually needs `chart.zoomScale`)
- Enabling scrubber only via Zoom + buttons and skipping drag/wheel zoom
  (HIST-ZOOM-5 / HIST-ZOOM-7)
- Counting only four History summary cards (there are **six**)

---

## Checklist

- [ ] Isolated sim; `QA_DIR` created; real config untouched
- [ ] Browser locked; seeded data visible
- [ ] [checklist.md](checklist.md) cases run (full or scoped)
- [ ] Failures have expected/actual + evidence
- [ ] `report.md` + `findings.json` written
- [ ] App stopped; browser unlocked; summary delivered
- [ ] No unsolicited redesign or live-trading changes
