---
type: reference
description: Remember adversarial review mandatory
---

# Adversarial PR Review — Always-On

- **When mandatory**: before finishing `open-pr` (after gates, before `gh pr create`) and before finishing `commit-and-push` when the branch already has an open PR (push to open PR). Also when user explicitly asks for adversarial/multi-agent review.
- **Skill**: `.agents/skills/adversarial-pr-review/SKILL.md` — parent-orchestrated, N bounded read-only tracks (2–6), must use `muse.subagent_spawn` (Muse) with explicit file sets, 8 iterations, 12-line/5-finding cap, then triage → fix → re-review only affected tracks until convergence (max 5 rounds).
- **Checklist**: create N-track matrix, launch parallel, verify findings, fix critical/warning, re-run affected tracks, converge or defer. Never skip because branch already has PR.
- **Recent gap 2026-08-07**: PR #222 updates (rename dustThresholdUSD → minimumOrderSizeUSD, $2 floor) were pushed without review; user flagged. Fixed 2026-08-08 by running 4-track review (A runtime, B trading, C UI, D docs/tests) and fixing alias + floor tests.