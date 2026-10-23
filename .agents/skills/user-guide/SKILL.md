---
name: user-guide
description: >-
  Maintain docs/USER_GUIDE.md as an end-user walkthrough of Dashboard and
  Settings.
---

# User Guide Maintenance

The User Guide is operator documentation, not developer internals. Keep the
current page content and screenshots aligned. Explain what operators see and
what to do; link to ALGORITHM, FLOWS, README, and SECURITY for deeper detail.

## Structure

1. Safety modes
2. Navigation overview
3. Dashboard: latest cycle snapshot, allocation, performance, activity, and
   operational safety
4. Settings: active controls, safety modes, allocations, and score-based target
   preview
5. Suggested workflows
6. Links to README, ALGORITHM, FLOWS, and SECURITY

Use the retained Dashboard and Settings screenshots. Captions must match the
current UI; never retain references to removed routes or controls. Explain
simulation as the offline emulator and dryRun as suppressing orders. Live
trading requires both disabled with real credentials.

## Checklist

- [ ] Guide sections match Dashboard and Settings
- [ ] Major sections use current screenshots from docs/images
- [ ] Screenshot links retain full-resolution images and explicit display widths
- [ ] Simulation and dryRun are clearly distinguished
- [ ] README and guide cross-link correctly
