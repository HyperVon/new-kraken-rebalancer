---
name: post-deploy-ui-smoke
description: >-
  After deploying UI changes, hard-refresh and smoke-check Dashboard / History /
  Settings for stale CSS, header density, view presets, and zoom/scrubber.
  Use when the user says production looks wrong, after deploy, or post-release
  UI verification.
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
- [ ] Step 2: STYLE-* + REGRESSION-* cases (checklist)
- [ ] Step 3: HIST-VIEW-2 (Day · Total only) + HIST-ZOOM-5/6 if History shipped
- [ ] Step 4: Report pass/fail; open fix branch if P0/P1
```

### Step 1 — Kill stale CSS

1. Hard-refresh the page (bypass cache) **or** inspect
   `<link rel="stylesheet" href="/static/style.css?v=…">`.
2. Fail **STYLE-1** if there is no `?v=` (clients may keep 24h-old CSS).
3. Fail **STYLE-2** if History Views/Zoom look like white native OS buttons.

### Step 2 — Desktop density

At **~1280–1440px** width (laptop, not mobile-first):

- **REGRESSION-1** — LIVE/DELAYED + Data Age not vertically squished
- **REGRESSION-2** — Over target / Under target spaced with dots
- **REGRESSION-3** — Chart legends use line/point markers, not heavy boxes

### Step 3 — History interactions (if that surface changed)

From [checklist.md](../ui-manual-qa/checklist.md):

- **HIST-VIEW-2** — Day · Total only hides non-Total series
- **HIST-ZOOM-5** — drag zooms, does not pan
- **HIST-ZOOM-6** — scrubber pans when zoomed

### Step 4 — Report

```markdown
# Post-deploy UI smoke
- URL: …
- STYLE-*: pass/fail
- REGRESSION-*: pass/fail
- History extras: pass/fail/skipped
- Next: fix branch / hard-refresh instruction for operators
```

---

## Checklist

- [ ] Hard-refresh or `?v=` confirmed
- [ ] Laptop viewport checked
- [ ] Failures have screenshots or DOM evidence
- [ ] No live-trading settings changed on the user’s deploy
