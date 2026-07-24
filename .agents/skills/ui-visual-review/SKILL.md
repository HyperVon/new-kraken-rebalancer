---
name: ui-visual-review
description: >-
  Visually review the live Dashboard / Settings / History UI in simulation mode
  and recommend redesigns, removals, additions, or polish. Use when the user
  asks for a UI review, visual audit, design critique, or screenshot-based UX
  improvements — recommend only; do not implement (use ui-visual-implement).
---

# UI Visual Review

Produce a **recommendation-only** visual critique of the rebalancer UI. Capture
fresh screenshots from a running simulation instance, **read the PNGs**, and
propose concrete improvements. Do **not** edit application code in this skill —
hand approved findings to [ui-visual-implement](../ui-visual-implement/SKILL.md).

Related:

- Capture plumbing → [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md)
- Functional click-through QA → [ui-manual-qa](../ui-manual-qa/SKILL.md)
- SSR / CSS → [ktor-html-views](../ktor-html-views/SKILL.md)
- Charts / SSE DOM → [frontend-js-development](../frontend-js-development/SKILL.md)
- Operator meaning → [user-guide](../user-guide/SKILL.md)

---

## Scope

Review every primary surface users see:

| Page | Route | Typical shots |
| :--- | :--- | :--- |
| Dashboard | `/` | top (cards/allocation) + bottom (table/activity) |
| Settings | `/settings` | full form |
| History | `/history` | summary/charts, mid charts, trade log |

Also note empty/error/loading states if you can trigger them safely in simulation
(e.g. before seed finishes — usually skip; prefer seeded happy path + obvious
clutter).

**Out of scope for this skill:** implementing CSS/HTML/JS, changing trading
math, rewriting docs (except quoting findings).

---

## Workflow

```text
- [ ] Step 0: Confirm review goal (full UI vs one page; any focus themes)
- [ ] Step 1: Boot isolated simulation (same as docs-screenshot-refresh)
- [ ] Step 2: Capture PNGs to a temp review directory (do NOT overwrite docs/images)
- [ ] Step 3: Read every PNG with the image tool; optionally snapshot via browser
- [ ] Step 4: Write the findings report (template below)
- [ ] Step 5: Stop the app; clean the run directory
- [ ] Step 6: Ask which findings to implement → ui-visual-implement
```

### Step 1–2: Capture for review (not docs)

Reuse the screenshot skill’s isolation rules (`simulation: true`, throwaway
`RUN_DIR`, fresh DB). Write captures **outside** `docs/images/`:

```bash
./gradlew fatJar
RUN_DIR=$(mktemp -d)
cp rebalancer-config-template.json "$RUN_DIR/rebalancer-config.json"
# set simulation: true, loopDelaySeconds: 15; dryRun optional
# start jar from $RUN_DIR; wait for curl -sf http://localhost:8080/api/health

REVIEW_DIR=$(mktemp -d /tmp/ui-visual-review.XXXXXX)
python3 -m venv /tmp/kraken-screenshots
/tmp/kraken-screenshots/bin/pip install -q playwright
/tmp/kraken-screenshots/bin/python \
  .agents/skills/docs-screenshot-refresh/scripts/capture_screenshots.py \
  --out-dir "$REVIEW_DIR"
```

Also run `--discover` once and note uncovered pages/sections in the report.

### Step 3: Visual analysis (mandatory)

For **each** PNG under `$REVIEW_DIR`:

1. **Read the image file** (vision) — do not judge from filenames alone.
2. Note hierarchy, density, alignment, contrast, clipping, empty space, chart
   readability, badge/table scanability, and mobile risk if the layout looks
   desktop-only.
3. Optionally open the live page in the browser for hover/focus states the PNG
   misses — still ground findings in what you saw.

### Step 4: Findings report

Present findings to the user **before** any implementation. Use this structure:

```markdown
# UI visual review

## Summary
1–3 sentences: overall impression + biggest opportunity.

## Baseline
- Theme: dark glass (`CssTheme` — Inter/Outfit, slate glass, blue accents)
- Captures: `$REVIEW_DIR` (list files)
- Simulation seed: yes/no; dryRun: on/off

## Findings

### [P0|P1|P2|P3] SHORT-ID — Title
- **Type**: redesign | add | remove | restyle | layout | typography | chart | a11y | motion
- **Page**: Dashboard | Settings | History | Global
- **Evidence**: which screenshot / region (and what you saw)
- **Problem**: what’s wrong or weak
- **Recommendation**: concrete change (can be ambitious)
- **Acceptance**: how implement skill will know it’s done (visual criteria)
- **Touch likely**: `view/css/*`, `view/component/*`, `:frontend-js`, `:common` …

## Explicit non-issues
Things that look intentional / fine — avoids churn.

## Suggested implement order
Ordered SHORT-IDs for ui-visual-implement.
```

Severity guide:

| Sev | Meaning |
| :--- | :--- |
| **P0** | Broken / unreadable / clipped / misleading |
| **P1** | Clear UX friction or visual defect |
| **P2** | Meaningful polish or coherent redesign slice |
| **P3** | Taste / nice-to-have |

---

## What to evaluate

Be willing to recommend **redesigns, removals, and additions** — not only
pixel tweaks. Cover at least:

1. **Hierarchy** — What does the eye hit first? Is LIVE / Data Age / portfolio
   value competing with noise?
2. **Density & whitespace** — Cramped cards vs sparse voids; consistent gaps.
3. **Alignment & rhythm** — Columns, table headers, form labels, chart legends.
4. **Contrast & color** — Secondary text on glass; success/danger badges;
   chart series distinctness.
5. **Component consistency** — Glass cards, pills, buttons, badges share one
   language (`CssTheme` / `CssClass`).
6. **Information architecture** — Duplicate stats? Missing empty states? Settings
   field grouping / labels?
7. **Charts** — Legend clutter, overlapping lines, timeframe control clarity,
   History mid-page charts vs summary.
8. **Activity / trade log** — Scanability of BUY/SELL/DRY RUN / SUCCESS.
9. **Responsive risk** — Does `80rem` max-width leave awkward margins; would
   narrow viewports collapse badly?
10. **Motion / feedback** — HTMX/SSE updates feel calm vs jumpy (if observed live).

### Design-system stance

- **Default**: improve within the existing dark-glass system
  (`CssTheme`, `LayoutStyles`, `ComponentStyles`) — evolve, don’t randomly
  restyle one card.
- **Allowed**: propose a larger visual direction change when hierarchy or brand
  is weak — mark Type `redesign`, call out migration scope, and keep trading
  semantics intact.
- **Do not** invent fake features that need backend work without labeling them
  as product/API dependencies.
- **Do not** recommend live-trading UI that hides `simulation` / `dryRun`
  clarity — safety modes must stay obvious ([dry-run-and-simulation](../dry-run-and-simulation/SKILL.md)).

---

## Handoff — selection UX (required)

Do **not** end with a vague “which ones?” question only. Always give the user an
easy picker:

### 1. Persist findings

Write `$REVIEW_DIR/findings.json` (and a short `findings.md`) so
ui-visual-implement can reload the brief:

```json
{
  "reviewDir": "/tmp/ui-visual-review.XXX",
  "generatedAt": "ISO-8601",
  "findings": [
    {
      "id": "DASH-1",
      "severity": "P1",
      "title": "…",
      "type": "layout",
      "page": "Dashboard",
      "problem": "…",
      "recommendation": "…",
      "acceptance": "…",
      "touchLikely": ["view/css/ComponentStyles.kt"],
      "defaultSelected": true
    }
  ]
}
```

`defaultSelected`: `true` for P0–P1, `false` for P2–P3 (user can still toggle).

### 2. Interactive Canvas picker (preferred)

Create a Cursor Canvas at:

`/Users/<user>/.cursor/projects/<workspace>/canvases/ui-visual-review.canvas.tsx`

Embed the findings **inline** (no fetch). Use `Checkbox` + `useCanvasState` so
the user can toggle each SHORT-ID. Include:

- Severity pills / page labels
- One-line problem + recommendation
- Quick actions: **Select P0–P1**, **Select all**, **Clear**
- Primary button **Implement selected** that calls `useCanvasAction()` →
  `newComposerChat` with a `userPrompt` like:

  ```text
  Implement these UI visual-review findings with the ui-visual-implement skill:
  DASH-1, SETT-2

  Use findings from /tmp/ui-visual-review.XXX/findings.json
  ```

- Secondary **Skip for now** → chat prompt `Skip UI implement; review only.`

Link the canvas in the chat reply so the user can open it beside the conversation.

### 3. Chat fallback (always include)

Below the report, add a compact reply cheat-sheet:

```markdown
## Apply picker (chat)

Reply with one of:

- `apply DASH-1, HIST-2` — listed SHORT-IDs only
- `apply P0-P1` — all severity ≤ P1
- `apply all` — every finding
- `none` — stop after review

Or open the [UI review canvas](…/ui-visual-review.canvas.tsx), tick findings,
and click **Implement selected**.
```

Then wait for the user’s selection before running ui-visual-implement.

---

## Checklist

- [ ] Isolated sim run; captures in temp dir (docs/images untouched)
- [ ] Every capture PNG read with vision
- [ ] Findings use SHORT-IDs, severity, acceptance criteria
- [ ] `findings.json` written under `$REVIEW_DIR`
- [ ] Interactive canvas picker created + linked (checkboxes + Implement selected)
- [ ] Chat apply cheat-sheet included
- [ ] Ambitious redesigns labeled; safety modes not weakened
- [ ] App stopped; RUN_DIR cleaned
- [ ] User chose what to implement before ui-visual-implement
