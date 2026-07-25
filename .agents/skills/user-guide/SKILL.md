---
name: user-guide
description: >-
  Author and maintain docs/USER_GUIDE.md — end-user walkthrough of Dashboard,
  Settings, and History with embedded docs/images screenshots. Use when adding
  user-facing features, changing settings semantics, or updating the manual.
---

# User Guide Maintenance

Canonical end-user walkthrough: [`docs/USER_GUIDE.md`](../../../docs/USER_GUIDE.md).

This is **product documentation for operators**, not developer internals.
Deep math → [ALGORITHM.md](../../../docs/ALGORITHM.md). Install/stack → README.
Agent how-to for screenshots → [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md).

---

## When to update

| Change | Action |
| :--- | :--- |
| New dashboard card / table column / activity badge | Update the matching Dashboard section + screenshot if visuals changed |
| Header mode plate or STREAM/STALE stream chip | Clarify trading mode vs stream health; never call the chip “live trading” |
| Dashboard hero / Cash·Crypto tiles / cycle activity feed | Update Dashboard sections; refresh overview + bottom screenshots |
| New Settings field or safety-mode cards (ON/OFF) | Update Settings table; clarify dryRun vs simulation |
| History charts, range pills, six summary cards, trade log | Update History sections; refresh related PNGs |
| New primary page/route | Add a guide section **and** a capture target in `docs-screenshot-refresh` |
| Removed UI | Delete the section and README/guide image references |

After UI changes, run [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md)
**before** finalizing guide captions — the guide must embed current images, not
describe a UI the screenshots no longer show.

---

## Structure & tone

Keep the guide:

1. **Visual-first** — every major page/section should include at least one
   `docs/images/*.png` (relative paths like `images/dashboard.png` from
   `docs/USER_GUIDE.md`). Do not ship long text-only feature dumps.
2. **Operator-facing** — explain what the user sees and what to do; link out for
   math/architecture instead of duplicating ALGORITHM/FLOWS.
3. **Safety-clear** — distinguish `simulation` (offline emulator) from `dryRun`
   (suppress orders). Live trading requires both off plus real credentials.
4. **Screenshot-honest** — captions may note that images were taken in
   simulation; do not present emulator numbers as a user’s live book.

Suggested section order (match the live nav):

1. Safety modes
2. Navigation overview
3. Dashboard (overview + scrolled detail images)
4. Settings (global params + allocations)
5. History (summary/charts, mid charts, trade log)
6. Suggested workflows
7. Links to README / ALGORITHM / FLOWS / SECURITY

---

## Image inventory (keep in sync)

| Image | Typical guide use |
| :--- | :--- |
| `docs/images/dashboard.png` | Dashboard overview / hero KPI + Cash·Crypto tiles |
| `docs/images/dashboard-bottom.png` | Allocation, performance table, cycle activity feed |
| `docs/images/settings.png` | Global parameters + allocations + safety cards |
| `docs/images/history.png` | Range pills, six summary cards, value/holdings charts |
| `docs/images/history-charts.png` | Allocation drift + cumulative net cash flow (+ caption) |
| `docs/images/history-bottom.png` | Trade history table (status dots / badges) |

When `docs-screenshot-refresh` gains a new target file, add it here **and**
embed it in USER_GUIDE (and README Screenshots if appropriate).

---

## Checklist

- [ ] USER_GUIDE sections match current Dashboard / Settings / History UI
- [ ] Every major section embeds a current screenshot under `docs/images/`
- [ ] dryRun vs simulation explained without collapsing the flags
- [ ] README links to the guide; guide links back to Getting Started / ALGORITHM
- [ ] Screenshots refreshed if visuals changed
- [ ] CHANGELOG notes user-guide updates when shipping
- [ ] `markdownlint-cli` clean on `docs/USER_GUIDE.md`
