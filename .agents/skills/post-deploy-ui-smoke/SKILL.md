---
name: post-deploy-ui-smoke
description: >-
  After deploying UI changes, hard-refresh and smoke-check Dashboard / History /
  Settings for stale CSS, mode plate, STREAM/STALE status, header density, view
  presets, and zoom/scrubber. Use when the user says production looks wrong,
  after deploy, or post-release UI verification.
---

# Post-deploy UI smoke

Fast verification after a deploy (LAN “production” or real host). Complements
full [ui-manual-qa](../ui-manual-qa/SKILL.md); does **not** replace it for large
UI features. After a messy deploy, start here then escalate to full QA if needed.

Related: [ui-manual-qa](../ui-manual-qa/SKILL.md),
[ui-visual-review](../ui-visual-review/SKILL.md),
[dry-run-and-simulation](../dry-run-and-simulation/SKILL.md).

---

## Preconditions

- Prefer an isolated **simulation** instance when you control the host.
- If checking the user’s already-running deploy: **read-only** clicks; do not
  flip `simulation` / `dryRun` off or save live-trading settings.

---

## Workflow

```text
- [ ] Step 0: Note base URL (e.g. http://10.0.0.x:8080)
- [ ] Step 1: Hard-refresh / confirm stylesheet ?v=
- [ ] Step 2: Global mode + stream status (fast)
- [ ] Step 3: STYLE-* + REGRESSION-* cases (checklist)
- [ ] Step 4: HIST-VIEW-2 (Day · Total only) + HIST-ZOOM-5/6 if History shipped
- [ ] Step 5: Report pass/fail; open fix branch if P0/P1
```

### Step 1 — Kill stale CSS

1. Hard-refresh the page (bypass cache) **or** inspect
   `<link rel="stylesheet" href="/static/style.css?v=…">`.
2. Fail **STYLE-1** if there is no `?v=` (clients may keep 24h-old CSS).
3. Fail **STYLE-2** if History Views/Zoom look like white native OS buttons.

### Step 2 — Global mode / status (critical, keep fast)

From [checklist.md](../ui-manual-qa/checklist.md), at **~1280–1440px**:

- **GLOBAL-7** — Mode plate on `/`, `/history`, `/settings` (sim → **SIMULATION**)
- **GLOBAL-5** — Dashboard chip is **STREAM** or **STALE**, never **LIVE** /
  **DELAYED**; relative age + clock time present
- Spot-check: Settings Safety Modes still show Simulation / Dry Run cards with
  visible **ON** / **OFF** (do not toggle on the user’s live deploy)

### Step 3 — Desktop density

- **REGRESSION-1** — Mode plate + STREAM/STALE + relative age/time not squished
- **REGRESSION-2** — Over target / Under target spaced with dots
- **REGRESSION-3** — Chart legends use line/point markers, not heavy boxes

### Step 4 — History interactions (if that surface changed)

From [checklist.md](../ui-manual-qa/checklist.md):

- **HIST-VIEW-2** — Day · Total only hides non-Total series
- **HIST-ZOOM-5** — drag zooms, does not pan; scrubber enables after drag-zoom
- **HIST-ZOOM-6** — scrubber drag **moves the chart** (not only the thumb)
- **HIST-ZOOM-7** — wheel zoom also enables scrubber + pan works

Fail HIST-ZOOM-6 if the range thumb moves but time ticks / series stay put
(usually means pan wrote `options.scales` instead of `chart.zoomScale`).

### Step 5 — Report

```markdown
# Post-deploy UI smoke
- URL: …
- STYLE-*: pass/fail
- GLOBAL mode/stream: pass/fail
- REGRESSION-*: pass/fail
- History extras: pass/fail/skipped
- Next: fix branch / hard-refresh instruction for operators
```

---

## Checklist

- [ ] Hard-refresh or `?v=` confirmed
- [ ] Mode plate + STREAM/STALE checked
- [ ] Laptop viewport checked
- [ ] Failures have screenshots or DOM evidence
- [ ] No live-trading settings changed on the user’s deploy
