---
name: docs-screenshot-refresh
description: >-
  Boot the rebalancer in simulation mode, capture README screenshots under
  docs/images/, and overwrite outdated PNGs. Use when UI/CSS/HTMX/Kotlin-JS
  visuals change, when refreshing documentation images, or when README
  screenshots no longer match the live dashboard.
---

# Documentation Screenshot Refresh

Keep `docs/images/*.png` (linked from `README.md`) visually current. Text docs
must not ship with stale UI screenshots.

Related:

- Incremental text sync → [changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md)
- Full text audit → [documentation-review](../documentation-review/SKILL.md)
- End-user walkthrough → [user-guide](../user-guide/SKILL.md)
- Flag semantics → [dry-run-and-simulation](../dry-run-and-simulation/SKILL.md)
- Visual critique (recommend) → [ui-visual-review](../ui-visual-review/SKILL.md)
- Apply + verify UI changes → [ui-visual-implement](../ui-visual-implement/SKILL.md)

---

## When to run

Run after changes that alter what users see on Dashboard, Settings, or History:

- `view/component/*`, `view/css/*`, `DashboardView`, HTMX fragments
- `:frontend-js` charts, SSE-driven DOM, History summary cards
- Nav labels / layout / glass theme tokens that show in README screenshots

Skip for pure backend/math/docs-text changes that do not affect those pages.

---

## Capture targets

Targets live in [`scripts/targets.json`](scripts/targets.json) — filename, route,
readiness waits, and scroll position. Current set:

| File | Capture |
| :--- | :--- |
| `dashboard.png` | `/` — overview cards + allocation chart |
| `dashboard-bottom.png` | `/` — asset table + recent activity |
| `settings.png` | `/settings` — full settings form |
| `history.png` | `/history` (30d) — summary cards + first charts |
| `history-charts.png` | `/history` (30d) — allocation drift + cumulative P&L |
| `history-bottom.png` | `/history` (30d) — trade log |

Canonical PNGs are **2880×1800** (1440×900 @2×). This closely frames the app's
`80rem` (1280 px) max-width container without triggering responsive layouts.

**This list is not fixed.** As the app grows, add targets rather than
reproducing only the existing files — see [Step 5](#step-5-adapt-targets-as-the-app-grows).

---

## Workflow

```text
- [ ] Step 0: Confirm visual-impacting change (or user asked for refresh)
- [ ] Step 1: Isolated simulation run directory
- [ ] Step 2: Start the app on :8080 and wait for seeded data
- [ ] Step 3: Capture the PNGs
- [ ] Step 4: Verify each image
- [ ] Step 5: Adapt targets as the app grows
- [ ] Step 6: Stop the app, clean up, update README/CHANGELOG
```

### Step 1: Isolated simulation run directory

**Never point a capture run at the user's real `rebalancer-config.json` or
`kraken-rebalancer.db`.** Both resolve relative to the process working
directory, so run from a throwaway directory instead:

```bash
./gradlew fatJar
RUN_DIR=$(mktemp -d)
cp rebalancer-config-template.json "$RUN_DIR/rebalancer-config.json"
```

Then set in `$RUN_DIR/rebalancer-config.json`:

```json
"simulation": true,
"loopDelaySeconds": 15
```

- **`simulation: true` is the required safety flag** — it routes all exchange
  calls to `SimulatedKrakenService`, so no real API is ever contacted.
- **`dryRun` is optional here.** With `simulation: true`, leaving `dryRun: false`
  is safe and produces more realistic screenshots: the emulator executes orders,
  so balances move and the trade log shows completed trades instead of every row
  tagged `DRY RUN`. Set `dryRun: true` only when you specifically want to
  document dry-run badges.
- Keep placeholder API keys; they are unused in simulation.

An isolated run directory also guarantees a **fresh database**, which matters
for chart quality (see below).

### Step 2: Start and wait for seeded data

```bash
cd "$RUN_DIR" && java -Xshare:off --sun-misc-unsafe-memory-access=allow \
  --enable-native-access=ALL-UNNAMED \
  -jar <project>/build/libs/kraken-bot-*-all.jar
```

An empty DB plus `simulation: true` seeds ~15 days of snapshots at 6-hour
intervals, so History charts have a continuous series immediately.

Ready check:

```bash
curl -sf http://localhost:8080/api/health
```

Capture **shortly after** startup. Live cycles append snapshots every
`loopDelaySeconds`; if the app runs for a long time, those dense points pile up
at the right edge of the History charts and look like a vertical spike.

> **Chart-quality trap**: reusing an old database is the main cause of
> weird-looking History charts. A stale DB has seeded points ending days ago
> plus a cluster of live points, so Chart.js draws a long flat line across the
> unrecorded gap and a near-vertical stack where the old run stopped. A fresh
> seeded DB avoids both.

### Step 3: Capture

```bash
python3 -m venv /tmp/kraken-screenshots
/tmp/kraken-screenshots/bin/pip install playwright
/tmp/kraken-screenshots/bin/python \
  .agents/skills/docs-screenshot-refresh/scripts/capture_screenshots.py
```

Useful flags:

- `--only history.png,history-bottom.png` — recapture a subset
- `--out-dir /tmp/ui-review` — write elsewhere (UI review / verify; leave
  `docs/images/` alone)
- `--discover` — report pages/sections with no target (Step 5)
- `--base-url`, `--chrome`, `--manifest` — override defaults

Do not use the embedded Cursor browser for these assets: its panel dimensions
crop the UI, and CDP device emulation can tile the page on Retina displays. The
Playwright helper is tested at the canonical output size.

### Step 4: Verify

**Read** every regenerated PNG and check:

- Cards, tables, and charts are populated — no empty states or error banners
- Charts show a continuous series without long flat gaps or vertical stacks
- Nothing is clipped at the right edge
- No credentials, personal hostnames, or OS chrome in frame

If a chart looks wrong, fix the data (fresh DB, wait for seeding) rather than
accepting the image.

### Step 5: Adapt targets as the app grows

Screenshots should track the app, not the existing file list. Run:

```bash
/tmp/kraken-screenshots/bin/python \
  .agents/skills/docs-screenshot-refresh/scripts/capture_screenshots.py --discover
```

It prints routes reachable from the UI and headings on each page that no target
references. Cross-check against `Routes` in `:common`. When a new page or major
section appears:

1. Add a target to `scripts/targets.json` (readiness waits + anchor heading).
2. Capture it.
3. Add the image to the README **Screenshots** section **and** embed it in
   [`docs/USER_GUIDE.md`](../../../docs/USER_GUIDE.md) with a short caption
   (see [user-guide](../user-guide/SKILL.md)).
4. Note the addition in `CHANGELOG.md`.

Conversely, remove targets and README references for pages that no longer exist.

### Step 6: Stop and clean up

1. Stop the Java process.
2. `rm -rf "$RUN_DIR"` — nothing to restore, since the real config and DB were
   never touched.
3. Update README captions if the asset set changed.
4. Add a brief `CHANGELOG` entry (`### Changed` — updated documentation
   screenshots).

---

## Checklist

- [ ] `simulation: true` for the capture run (`dryRun` optional)
- [ ] Ran from a throwaway directory; real config/DB untouched
- [ ] Fresh seeded DB; charts continuous with no gap or spike
- [ ] Every regenerated PNG read and visually verified
- [ ] `--discover` reviewed; new pages/sections captured and added to README
- [ ] App stopped, run directory removed, CHANGELOG updated
