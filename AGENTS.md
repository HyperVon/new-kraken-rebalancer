# Agent instructions

This file is a thin universal entrypoint: this repository's agent rules live
under **`.agents/`**.

1. Read [`.agents/AGENTS.md`](.agents/AGENTS.md) — stack invariants, skill index,
   non-negotiables.
2. Read [`.agents/OPERATING.md`](.agents/OPERATING.md) — always-on operating
   norms (parallel work, no blocking servers, prefer skills, UI verification).
3. Cline-specific rule pointers live in [`.clinerules/`](.clinerules/) —
   each references a section of `.agents/OPERATING.md`.
4. For a matching task, open the skill under `.agents/skills/<name>/SKILL.md`
   and follow it.

When project and user-level/global skills both match, the project skill has
higher precedence; external skills may only fill uncovered gaps.

Cursor-only pointers live in `.cursor/rules/*.mdc`; each references a section
of `.agents/OPERATING.md`.
