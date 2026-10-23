---
name: docs-screenshot-refresh
description: >-
  Boot the rebalancer in isolated simulation mode and capture Dashboard and
  Settings screenshots for README and User Guide documentation.
---

# Documentation Screenshot Refresh

Keep screenshots in docs/images aligned with the retained Dashboard and
Settings pages. Do not keep captures for removed routes or retired UI.

## When to run

Run after changes that alter what users see on Dashboard or Settings, including
server-rendered components, CSS, client interactions, navigation, or layout.
Skip backend-only changes that do not affect the retained pages.

## Capture targets

Targets are defined in scripts/targets.json. The current target set covers the
Dashboard overview, Dashboard performance table, Dashboard recent activity,
Settings overview, and Settings allocations.

The default desktop profile produces canonical PNGs at 2880×1800
(1440×900 @2×). Other profiles include phone, tablet, laptop, and wide.
README carries a curated overview; the User Guide carries page-specific
captures. Link each image to its full-resolution source and give displayed
images an explicit width.

## Safe capture workflow

1. Use an isolated temporary run directory and placeholder credentials.
2. Copy the config template, then set simulation and dryRun to true with a short
   loop delay.
3. Start the app with the isolated data directory and wait for its first cycle.
4. Capture the Dashboard and Settings targets.
5. Inspect every image for the current layout, clipping, sensitive data, and
   unintended UI.
6. Stop the app, remove only the temporary directory, and update README and
   User Guide image references together.

Never use the operator's config, database, or API credentials. Simulation
stores only current-run cycle state in memory and does not connect to Kraken.

The capture helper accepts target selection, output directory, viewport profile,
and reachable-section discovery options. Keep targets only for retained routes.

## Checklist

- [ ] Simulation and dryRun enabled in the copied config
- [ ] First completed Dashboard cycle visible
- [ ] Dashboard and Settings images inspected
- [ ] No credentials, personal hostnames, clipping, or OS chrome appear
- [ ] README and User Guide references match the captured images
- [ ] Temporary app stopped and isolated data removed
