# GitHub Copilot instructions — Kraken Rebalancer

Follow the repository agent guidance (portable; not Cursor-only):

- [`.agents/AGENTS.md`](../.agents/AGENTS.md) — tech stack, invariants, skill index
- [`.agents/OPERATING.md`](../.agents/OPERATING.md) — always-on operating norms
- [`.agents/skills/`](../.agents/skills/) — task-specific workflows (commit, PR, UI QA, etc.)

When a user request matches a skill in the AGENTS.md index, read that skill and
follow it. Prefer project skills over inventing ad-hoc workflows.

Key norms from OPERATING.md: fan out independent workstreams in parallel;
never block on long-lived servers in the foreground; after UI changes verify
laptop (~1280–1440) density and CSS cache-bust (`/static/style.css?v=`).
