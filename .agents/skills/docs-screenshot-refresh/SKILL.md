---
name: docs-screenshot-refresh
description: >-
  Boot the rebalancer in isolated simulation mode and capture Dashboard,
  Settings, and Actual screenshots for the README and User Guide. Use when
  retained UI visuals change or current screenshots need refresh.
---

# Documentation Screenshot Refresh

Keep screenshots in `docs/images/` aligned with the retained Dashboard,
Settings, and Actual pages. Do not keep captures or captions for the retired
History/reporting UI.

Related:

- Incremental text sync → [changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md)
- Full text audit → [documentation-review](../documentation-review/SKILL.md)
- End-user walkthrough → [user-guide](../user-guide/SKILL.md)
- Flag semantics → [dry-run-and-simulation](../dry-run-and-simulation/SKILL.md)
- Visual critique → [ui-visual-review](../ui-visual-review/SKILL.md)
- Apply + verify UI changes → [ui-visual-implement](../ui-visual-implement/SKILL.md)
- Functional click-through QA → [ui-manual-qa](../ui-manual-qa/SKILL.md)

## When to run

Run after changes that alter what users see on Dashboard, Settings, or Actual:

- `view/component/*`, `view/css/*`, `DashboardView`, or HTMX fragments
- Dashboard SSE status or current-cycle snapshot rendering
- Settings form controls or allocation preview
- Actual evidence states, coverage gaps, or prospective Buy & Hold display
- Navigation, layout, or theme tokens shown in screenshots

Skip pure backend/math/docs-text changes that do not affect a retained page.

## Capture targets

Targets live in [`scripts/targets.json`](scripts/targets.json) and define the
file, route, readiness waits, and scroll position.

| File | Capture |
| :--- | :--- |
| `dashboard.png` | `/` — latest cycle snapshot and allocation overview |
| `dashboard-performance.png` | `/` — Asset Performance and deviation legend |
| `dashboard-bottom.png` | `/` — actions from the latest current cycle |
| `settings.png` | `/settings` — active settings controls and safety modes |
| `settings-allocations.png` | `/settings` — allocations and quality-score preview |
| `actual.png` | `/actual` — raw observation state, evidence, and optional prospective Buy & Hold section |

The capture helper supports the retained routes and section anchors. Do not add
`/history` targets: that route redirects to `/actual`.

The default `desktop` profile produces canonical PNGs at **2880×1800**
(1440×900 @2×). Other supported profiles are `phone` (390×844), `tablet`
(768×1024), `laptop` (1280×800), and `wide` (1920×1080), all at DPR 2. Run
multiple profiles with `--profile laptop,tablet,phone` or `--profile all`.
Profile captures are written under per-profile directories; a no-argument run
keeps the flat `docs/images/<target>.png` layout.

### Documentation presentation

- README: a short curated set for Dashboard, Settings, and Actual.
- User Guide: page-specific captures for Dashboard, Settings, and Actual; add
  responsive Dashboard images when they clarify layout.
- Link each image to its full-resolution source and give the displayed image an
  explicit width suitable for a 2× capture.
- Inspect the README and User Guide at normal 100% browser zoom after updating
  image references.

## Workflow

```text
- [ ] Step 0: Confirm a retained-page visual change or requested refresh
- [ ] Step 1: Create an isolated simulation run directory
- [ ] Step 2: Start the app and wait for a current-cycle snapshot
- [ ] Step 3: Capture active targets
- [ ] Step 4: Read and verify every requested image
- [ ] Step 5: Adapt targets only for retained pages
- [ ] Step 6: Stop app, clean the run directory, update docs references
```

### Step 1: Isolated simulation run directory

Never use the operator's real `rebalancer-config.json`, database, or API
credentials. Run from a throwaway directory:

```bash
./gradlew :backend:fatJar
RUN_DIR=$(mktemp -d)
cp rebalancer-config-template.json "$RUN_DIR/rebalancer-config.json"
```

Set `simulation: true` and a short `loopDelaySeconds` in the copied config.
Simulation routes exchange calls to the offline emulator. `dryRun` is optional;
keep placeholder API keys because simulation does not use them. A temporary run
directory keeps the active stores isolated from operator data.

### Step 2: Start and wait for a current-cycle snapshot

```bash
cd "$RUN_DIR" && java -Xshare:off --sun-misc-unsafe-memory-access=allow \
  --enable-native-access=ALL-UNNAMED \
  -jar <project>/backend/build/libs/kraken-bot-*-all.jar
```

Wait until `/api/health` reports the process as up and the Dashboard shows its
first completed current-cycle snapshot. Simulation does not seed retrospective
history or authenticated Actual observations. The Actual page should show its
simulation-unavailable state; emulator balances are not Actual evidence.

```bash
curl -sf http://localhost:8080/api/health
```

### Step 3: Capture

```bash
python3 -m venv /tmp/kraken-screenshots
/tmp/kraken-screenshots/bin/pip install playwright
/tmp/kraken-screenshots/bin/python \
  .agents/skills/docs-screenshot-refresh/scripts/capture_screenshots.py
```

Useful flags:

- `--only dashboard.png,settings.png,actual.png` — capture retained page views
- `--out-dir /tmp/ui-review` — write elsewhere; leave canonical assets alone
- `--profile laptop,tablet,phone` or `--profile all` — capture responsive views
- `--list-profiles` — print profile dimensions without opening Chrome
- `--discover` — report reachable pages/sections that have no target
- `--base-url`, `--chrome`, `--manifest` — override defaults

Target fields include `file`, `path`, `await_text`, `position`, `anchor`,
`ensure_visible`, and optional viewport overrides. The helper opens a fresh
browser context per profile. Use it for static evidence instead of direct
embedded-browser screenshots.

### Step 4: Verify

Read every regenerated PNG and check:

- Dashboard shows a completed latest snapshot and its current-cycle actions.
- Settings shows active controls and clear Simulation / Dry Run states.
- Actual does not present emulator balances as authenticated observations; its
  current simulation-unavailable state is expected.
- No credentials, personal hostnames, clipping, or OS chrome appear.
- Layout differences across profiles reflect responsive reflow.

The Dashboard mode plate should read **SIMULATION** and its stream chip should
read **STREAM** or **STALE**. If the plate reads **LIVE TRADING**, stop and fix
the copied configuration before accepting the capture.

Functional interaction checks belong in [ui-manual-qa](../ui-manual-qa/SKILL.md).

### Step 5: Adapt targets

Run `capture_screenshots.py --discover` when a retained page gains a major
section. Add a target only when the helper can reach the page and wait for its
content. Capture the target, link the image in the User Guide, and add it to
README only when it fits the curated set. Do not retain targets for retired
History pages or endpoints.

### Step 6: Stop and clean up

1. Stop the Java process.
2. Remove only the isolated `$RUN_DIR`.
3. After visual changes are complete, capture final profiles and update README
   and User Guide references together.
4. Inspect both documents at 100% zoom and verify full-resolution image links.

## Checklist

- [ ] Simulation enabled in copied config; real config/database untouched
- [ ] First completed Dashboard cycle visible
- [ ] Every requested image read and visually verified
- [ ] Actual simulation state shown without emulator values as Actual evidence
- [ ] `--discover` reviewed for retained routes/sections
- [ ] README curated set and User Guide gallery use active targets only
- [ ] App stopped and isolated run directory removed
