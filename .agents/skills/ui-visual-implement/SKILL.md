---
name: ui-visual-implement
description: >-
  Implement approved UI visual-review findings, then verify by booting
  simulation, capturing screenshots, and reading the PNGs until the UI matches
  acceptance criteria. Use after ui-visual-review, or when the user asks to
  apply visual redesigns/fixes and confirm appearance.
---

# UI Visual Implement & Verify

Take **approved** findings from [ui-visual-review](../ui-visual-review/SKILL.md)
(or an explicit user brief), change the UI, and **prove** the result by running
the app and visually inspecting fresh captures. Do not declare done on code
diff alone.

Related:

- Review / brief → [ui-visual-review](../ui-visual-review/SKILL.md)
- Functional click-through QA → [ui-manual-qa](../ui-manual-qa/SKILL.md)
- SSR / CSS → [ktor-html-views](../ktor-html-views/SKILL.md)
- Charts / SSE → [frontend-js-development](../frontend-js-development/SKILL.md)
- Shared IDs/strings → [common-kmp-module](../common-kmp-module/SKILL.md)
- Docs PNGs after ship → [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md)
- User-facing copy → [user-guide](../user-guide/SKILL.md)

---

## Preconditions

- A findings list with SHORT-IDs and **Acceptance** criteria — prefer loading
  `$REVIEW_DIR/findings.json` from [ui-visual-review](../ui-visual-review/SKILL.md)
  (or the canvas / chat `apply …` selection).
- User confirmed which IDs to implement via canvas **Implement selected**, chat
  `apply …`, or an explicit “implement all / these IDs” message.
- Prefer `simulation: true` for all visual verify runs
  ([dry-run-and-simulation](../dry-run-and-simulation/SKILL.md)).

---

## Workflow

```text
- [ ] Step 0: Lock the brief (SHORT-IDs + acceptance)
- [ ] Step 1: Optional baseline capture → /tmp/ui-visual-before
- [ ] Step 2: Implement changes (ktor views/CSS, :frontend-js, :common)
- [ ] Step 3: Quality gates (spotless / relevant tests)
- [ ] Step 4: Boot isolated simulation
- [ ] Step 5: Capture → /tmp/ui-visual-after (or per-iteration dir)
- [ ] Step 6: Read PNGs; compare to acceptance criteria
- [ ] Step 7: If mismatch → fix and repeat Steps 2–6 (cap iterations)
- [ ] Step 8: Docs handoff (screenshots + USER_GUIDE + CHANGELOG)
- [ ] Step 9: Stop app; clean RUN_DIR; summarize
```

### Step 0: Brief

Copy the approved findings into the working notes. For each ID track:

| ID | Acceptance | Status |
| :--- | :--- | :--- |
| … | … | pending / done / blocked |

Do not expand scope into unapproved P3 taste items mid-flight without asking.

### Step 1: Baseline (recommended)

Same capture flow as the review skill, `--out-dir /tmp/ui-visual-before`, so
after shots have a comparison set. Skip only if the review `$REVIEW_DIR` is
still available and current.

### Step 2: Implement

Follow existing packages:

| Concern | Where |
| :--- | :--- |
| Markup / HTMX | `view/component/*`, `DashboardView` |
| Styles / theme | `view/css/*` (`CssTheme` tokens first) |
| Routes / pages | `DashboardController` / `DashboardRoutes` |
| Client charts / DOM | `frontend-js/src` |
| Strings / IDs / classes | `:common` (`ViewText`, `HtmlIds`, `CssClass`) |

Rules:

- Prefer token-level and shared component changes over one-off inline styles.
- Keep `:common` pure; no JVM/JS-only imports there.
- Preserve safety-mode clarity (Simulation / Dry Run labels and settings).
- Match project formatting (Spotless / 120 cols).

### Step 3: Gates before verify

At minimum for UI-facing Kotlin:

```bash
./gradlew spotlessApply spotlessCheck
./gradlew test :frontend-js:jsTest
```

Fix failures before claiming visual success.

### Steps 4–5: Verify capture

```bash
./gradlew fatJar
RUN_DIR=$(mktemp -d)
cp rebalancer-config-template.json "$RUN_DIR/rebalancer-config.json"
# simulation: true; start jar from $RUN_DIR; health-check :8080

AFTER_DIR=$(mktemp -d /tmp/ui-visual-after.XXXXXX)
/tmp/kraken-screenshots/bin/python \
  .agents/skills/docs-screenshot-refresh/scripts/capture_screenshots.py \
  --out-dir "$AFTER_DIR"
```

Capture only the pages touched if faster (`--only …`), but prefer full set
when Global / nav / theme tokens changed.

### Step 6: Visual verification (mandatory)

For each acceptance criterion:

1. **Read** the relevant after PNG(s) with the image tool.
2. Mark the SHORT-ID **done** only if the criterion is visibly met.
3. Call out regressions (new clipping, worse contrast, broken charts).

Optional: browser snapshot for hover/focus if the finding required it.

If acceptance touched History zoom/pan: also run
[ui-manual-qa](../ui-manual-qa/SKILL.md) `HIST-ZOOM-5/6/7` — scrubber must enable
after drag/wheel zoom and **pan the chart** (thumb-only motion is a fail).

### Step 7: Iteration cap

If acceptance fails, fix and re-capture. After **3** unsuccessful verify loops
on the same ID, stop, report what still fails, and ask the user how to proceed
(relax acceptance, redesign approach, or drop the item).

### Step 8: Docs handoff

When shipping visual changes users will see in README / User Guide:

1. Run [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md) to update
   `docs/images/*.png` (overwrite canonical assets — not the `/tmp` verify dir).
2. Update [docs/USER_GUIDE.md](../../../docs/USER_GUIDE.md) if meaning/layout of
   controls changed ([user-guide](../user-guide/SKILL.md)).
3. CHANGELOG `### Changed` (and `### Added` if new UI).
4. Point skills/AGENTS only if invariants or workflows changed.

### Step 9: Cleanup & report

Stop Java, `rm -rf "$RUN_DIR"`, leave `/tmp/ui-visual-*` only if the user wants
artifacts. Final message:

```markdown
## Implemented
- SHORT-ID — what changed (files) — verified via <png>

## Deferred / blocked
- …

## Docs
- screenshots refreshed: yes/no
- USER_GUIDE touched: yes/no
```

---

## Anti-patterns

- Declaring success from code review without reading after-PNGs
- Overwriting `docs/images/` during mid-iteration verify (use `--out-dir`)
- Using the user’s real `rebalancer-config.json` / DB for verify runs
- Implementing unapproved redesign scope “while we’re here”
- Weakening CORS / adding auth theater / hiding dryRun·simulation
- Skipping `:frontend-js` tests after chart/DOM changes

---

## Checklist

- [ ] Approved SHORT-IDs only
- [ ] Changes in view/css/component / frontend-js / :common as appropriate
- [ ] spotless + relevant tests green
- [ ] Isolated sim verify; after PNGs read against acceptance
- [ ] Iterations capped; regressions noted
- [ ] docs-screenshot-refresh + USER_GUIDE/CHANGELOG when shipping
- [ ] App stopped; real config/DB untouched
