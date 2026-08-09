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
- Functional click-through QA → [ui-manual-qa](../ui-manual-qa/SKILL.md)

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
| `dashboard-performance.png` | `/` — Asset Performance table and deviation legend |
| `dashboard-bottom.png` | `/` — recent activity feed |
| `settings.png` | `/settings` — full settings form |
| `history.png` | `/history` (30d) — summary cards, comparison, rewards, and first charts |
| `history-portfolio-charts.png` | `/history` (30d) — portfolio value + asset holdings |
| `history-charts.png` | `/history` (30d) — allocation deviation + cumulative net cash flow |
| `history-bottom.png` | `/history` (30d) — trade log |

The default `desktop` profile produces canonical PNGs at **2880×1800**
(1440×900 @2×). This closely frames the app's `80rem` (1280 px) max-width
container without triggering responsive layouts.

The capture script also owns these reusable review profiles:

| Profile | CSS viewport | DPR | PNG size | Use |
| :--- | :--- | :---: | :---: | :--- |
| `phone` | 390×844 | 2 | 780×1688 | narrow phone reflow |
| `tablet` | 768×1024 | 2 | 1536×2048 | tablet layout |
| `laptop` | 1280×800 | 2 | 2560×1600 | laptop density / breakpoint regressions |
| `desktop` | 1440×900 | 2 | 2880×1800 | canonical README/User Guide images |
| `wide` | 1920×1080 | 2 | 3840×2160 | wide-desktop spacing and max-width behavior |

Run multiple profiles in one command with `--profile laptop,tablet,phone` (or
`--profile all`). Explicit profiles are written below one directory per
profile, for example `$REVIEW_DIR/laptop/dashboard.png`; a no-argument run
keeps the historical flat `docs/images/dashboard.png` layout.

**This list is not fixed.** As the app grows, add targets rather than
reproducing only the existing files — see [Step 5](#step-5-adapt-targets-as-the-app-grows).

## Documentation presentation policy

The capture matrix and the documentation gallery serve different purposes.
Keep all five DPR 2 profiles for visual verification, but curate what is shown
in each document:

- README: normally four representative images only — the canonical desktop
  dashboard, a phone dashboard preview, Settings, and History.
- User Guide: the full responsive dashboard gallery plus the page-specific
  Settings and History captures.
- Embed screenshots with linked HTML `<img width="...">` elements. Use the
  intended CSS-viewport width or a deliberate capped width rather than the
  native 2x PNG width. Do not use unbounded Markdown image syntax for DPR 2
  captures.
- After updating images, render or inspect both Markdown documents at normal
  100% browser zoom. Check that the phone image reads as a phone preview, that
  the README remains scannable, and that links still open the full-resolution
  assets.

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
- `--profile laptop,tablet,phone` — capture the same targets at several common sizes
- `--profile all` — capture every named profile into separate subdirectories
- `--list-profiles` — print profile dimensions without opening Chrome
- `--discover` — report pages/sections with no target (Step 5)
- `--base-url`, `--chrome`, `--manifest` — override defaults

Target fields in `targets.json`: `file`, `path`, optional `click_button`,
`await_text`, `await_charts`, `position` (`top`/`bottom`), `anchor` (heading
scrolled to start), `ensure_visible` (substring scrolled with
`block: 'nearest'` so a trailing caption stays in frame after the anchor), and
optional `viewport` height/width overrides when a section is taller than its
profile's default frame. The profile owns `deviceScaleFactor`, so all targets
within one profile remain comparable.

Use this helper for static screenshot evidence instead of taking direct browser
screenshots. Do not use the embedded Cursor browser for these assets: its panel
dimensions crop the UI, and CDP device emulation can tile the page on Retina
displays. The helper creates a fresh browser context for each profile.

### Step 4: Verify

**Read** every regenerated PNG under every requested profile and check the basics:

- Cards, tables, and charts are populated — no empty states or error banners
- Charts show a continuous series without long flat gaps or vertical stacks
- Nothing is clipped at the right edge
- No credentials, personal hostnames, or OS chrome in frame
- The same target remains comparable across profiles; differences should come
  from intentional responsive reflow, not capture setup

Then confirm the current UI semantics survived the capture:

| Where | Expect |
| :--- | :--- |
| Every page header | Mode plate reads **SIMULATION** (the capture run is simulated) |
| Dashboard header | Stream chip reads **STREAM** / **STALE** (never `LIVE` / `DELAYED`) plus relative age/time |
| `dashboard.png` | Hero total with 24h delta chip + sparkline; Cash / Crypto tiles show bars, target, deviation |
| `dashboard-bottom.png` | Activity feed grouped per cycle with relative times, quiet-cycle summary, and the "View all history" link |
| `settings.png` | Safety Modes render as cards with icon, description, and ON/OFF pill |
| `history.png` / `history-portfolio-charts.png` / `history-charts.png` | Single-row chart headers (title + zoom); staking rewards, comparison delta, and net cash flow captions visible |
| `history-bottom.png` | Trade table shows em-dashes for zero economics and status dots for plain successes |

A capture that shows `LIVE TRADING` means the run directory was misconfigured —
stop, fix `simulation: true`, and recapture. If a chart looks wrong, fix the
data (fresh DB, wait for seeding) rather than accepting the image.

Functional click-through checks belong in
[ui-manual-qa](../ui-manual-qa/SKILL.md), not here.

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
2. Capture it with the relevant profile set.
3. Embed the relevant final images in [`docs/USER_GUIDE.md`](../../../docs/USER_GUIDE.md)
   with an explicit display width and short caption. Add an image to the README
   only when it fits the curated four-image overview; do not mirror the full
   gallery there (see [user-guide](../user-guide/SKILL.md)).
4. Note the addition in `CHANGELOG.md`.

Conversely, remove targets and README references for pages that no longer exist.

### Step 6: Stop and clean up

1. Stop the Java process.
2. `rm -rf "$RUN_DIR"` — nothing to restore, since the real config and DB were
   never touched.
3. After all UI/code iterations are complete, run the final profile set and
   update README/User Guide image references and captions together.
4. Inspect the rendered README and User Guide at 100% zoom, including the
   phone preview and the links to full-resolution images.
5. Add a brief `CHANGELOG` entry (`### Changed` — updated documentation
   screenshots and responsive viewport coverage).

---

## Checklist

- [ ] `simulation: true` for the capture run (`dryRun` optional)
- [ ] Ran from a throwaway directory; real config/DB untouched
- [ ] Fresh seeded DB; charts continuous with no gap or spike
- [ ] Every regenerated PNG read and visually verified
- [ ] Mode plate reads `SIMULATION`; stream chip reads `STREAM` / `STALE`
- [ ] Hero, activity feed, safety cards, history headers/caption, and trade
      table details present in the canonical PNGs
- [ ] `--discover` reviewed; new pages/sections captured and added to README
- [ ] README uses a curated image set with explicit HTML display widths
- [ ] User Guide uses explicit widths and includes the full responsive gallery
- [ ] README and User Guide checked at 100% zoom
- [ ] App stopped, run directory removed, CHANGELOG updated
