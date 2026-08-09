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

Capture through the shared screenshot helper at **desktop**, **laptop**, and
**phone** profiles (add **tablet** and **wide** when the layout is a focus).
Recent production regressions such as header status density and History toolbar
styling only show at laptop breakpoints, while mobile-only captures miss narrow
reflow failures. Do not take direct embedded-browser screenshots for static
evidence.

**Out of scope for this skill:** implementing CSS/HTML/JS, changing trading
math, rewriting docs (except quoting findings).

---

## Refined Glass baseline (current UI)

See [refined-glass-baseline.md](refined-glass-baseline.md) for the shipped
baseline table (mode plate, STREAM/STALE chip, hero, activity, safety cards,
History charts, trade table). Treat missing/broken baseline items as **P0/P1**;
do not re-propose them as new ideas.

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
  --profile desktop,laptop,phone \
  --out-dir "$REVIEW_DIR"
```

The helper writes `$REVIEW_DIR/<profile>/<target>.png`; read every PNG in each
requested profile. Also run `--discover` once and note uncovered pages/sections
in the report.

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
- Theme: Refined Glass (`CssTheme` — Inter/Outfit, cool-blue glass, cyan rim)
- Captures: `$REVIEW_DIR` (list files)
- Simulation seed: yes/no; dryRun: on/off
- Mode plate observed: SIMULATION | DRY RUN | LIVE TRADING (must match settings)

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

1. **Hierarchy** — What does the eye hit first? Is the mode plate / STREAM chip /
   portfolio hero competing with noise?
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
8. **Activity / trade log** — Scanability of cycle groups, BUY/SELL/DRY RUN
   badges, and the quiet success dot (see Refined Glass checks below).
9. **Responsive risk** — Does `80rem` max-width leave awkward margins; would
   narrow viewports collapse badly?
10. **Motion / feedback** — HTMX/SSE updates feel calm vs jumpy (if observed live).

### Refined Glass checks (mandatory on full reviews)

Judge each from the PNGs; report any miss as a finding rather than assuming the
implementation is fine:

1. **Mode plate** — Present beside the brand on **all three** pages, label
   matches the run's settings (`simulation: true` → `SIMULATION`), and
   `LIVE TRADING` reads as high-consequence. Fail if it is missing on Settings /
   History, or if plate and stream chip look like the same control.
2. **Stream chip** — On Dashboard only: reads `STREAM` / `STALE` (never `LIVE`)
   and sits on one line with relative age + timestamp at 1280–1440px, no wrap or
   clipping.
3. **Hero** — Total value clearly dominant; 24H delta colour/sign match the
   direction; sparkline actually renders (not an empty or clipped box).
4. **Cash / Crypto tiles** — Bar fill visually matches the stated percentage,
   fill colour matches the asset series, target and deviation stay legible.
5. **Activity feed** — Cycles visually separated with relative time and correct
   singular/plural action counts; quiet cycles show the tolerance summary;
   **View all history** reads as a link, not stray text.
6. **Safety cards** — Checked vs unchecked is obvious from the `ON`/`OFF` pill
   and card treatment; consequence prose is readable, not truncated; cards never
   degrade to bare native checkboxes.
7. **History chart header** — Title and zoom share one compact row above the
   legend (no three stacked bands); the net cash flow caption is present and
   muted; canvases look taller, not squat.
8. **Trade table** — Zero price/fee render as a muted em dash (not
   `0.00000000`); the plain-success dot is visible and not mistaken for an empty
   cell; dry-run and failed rows keep labelled badges.

### Production regression smells (mandatory on full reviews)

Flag these explicitly when seen — they map to `ui-manual-qa` `STYLE-*` /
`REGRESSION-*` / History cases:

1. **Stale CSS after deploy** — controls render as default white native
   `<button>` / `<select>` because `/static/style.css` was cached (24h); verify
   `?v=` cache-bust or hard-refresh before judging styling.
2. **Desktop header density (~1280–1440px)** — Brand, mode plate, stream chip,
   age, and nav vertically squished, clipped, or illegibly stacked (not only a
   mobile problem).
3. **Deviation legend spacing** — Dashboard Asset Performance **Over target** /
   **Under target** labels concatenated without spaced amber/blue dots.
4. **History toolbar styling** — Views select and Zoom − / + / Reset must match
   dark glass theme; white OS-native chrome is a regression.
5. **Day · Total only preset** — Portfolio Value chart should hide non-**Total**
   series in legend and on canvas (not just de-emphasized).
6. **Chart zoom vs pan** — Drag on chart should zoom the x-axis, not pan; after
   **any** zoom (Zoom + buttons, drag-select, or wheel), the bottom range scrubber
   must become **enabled** and **actually pan** the visible window when dragged
   (fail if the thumb moves but the chart does not). Do not re-enable chart
   drag-pan as a substitute.
7. **Chart.js legend markers** — Line/point swatches (`usePointStyle`); not
   heavy bordered box chips around every legend label.

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
  The mode plate and the Settings safety cards are the authoritative indicators;
  never propose merging the plate into the stream chip or dropping either.

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

`~/.cursor/projects/<workspace>/canvases/ui-visual-review.canvas.tsx`

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

Or open the generated UI review canvas, tick findings,
and click **Implement selected**.
```

Then wait for the user’s selection before running ui-visual-implement.

---

## Checklist

- [ ] Isolated sim run; captures in temp dir (docs/images untouched)
- [ ] Every capture PNG read with vision
- [ ] Refined Glass baseline evaluated ([refined-glass-baseline.md](refined-glass-baseline.md))
      and checks 1–8 (mode plate, stream chip, hero, tiles, activity feed, safety
      cards, chart header, trade table)
- [ ] Findings use SHORT-IDs, severity, acceptance criteria
- [ ] `findings.json` written under `$REVIEW_DIR`
- [ ] Interactive canvas picker created + linked (checkboxes + Implement selected)
- [ ] Chat apply cheat-sheet included
- [ ] Ambitious redesigns labeled; safety modes not weakened
- [ ] App stopped; RUN_DIR cleaned
- [ ] User chose what to implement before ui-visual-implement
