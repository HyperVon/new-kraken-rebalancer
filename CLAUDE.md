# Claude Code / Claude agent instructions

This repository’s agent rules are under **`.agents/`** (not a root `AGENTS.md`).

1. Read [`.agents/AGENTS.md`](.agents/AGENTS.md) — stack invariants, skill index,
   non-negotiables.
2. Read [`.agents/OPERATING.md`](.agents/OPERATING.md) — always-on operating
   norms (parallel work, no blocking servers, prefer skills, UI verification).
3. For a matching task, open the skill under `.agents/skills/<name>/SKILL.md`
   and follow it.

Cursor-only projections of OPERATING.md live in `.cursor/rules/*.mdc`; keep them
in sync when changing OPERATING.md.
