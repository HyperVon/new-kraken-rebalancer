---
name: changelog-and-docs-sync
description: >-
  Keep documentation synchronized — Keep a Changelog entries, README package
  tree, ALGORITHM.md / FLOWS.md / EVALUATION.md, JaCoCo exclusions, and
  .agents/AGENTS.md stack versions. Use when shipping features, refactors, or
  dependency bumps that affect public behavior or layout. For a full audit of
  all docs against source code, use documentation-review instead.
---

# Changelog & Docs Sync

Incremental sync after a change set. For a whole-repo docs audit (missing,
wrong, stale), use [documentation-review](../documentation-review/SKILL.md).

## Keep a Changelog

Update `CHANGELOG.md` under the next version with `### Added` / `### Changed` /
`### Fixed` / `### Removed` as appropriate. Prefer user-visible “why” over file
lists.

## When to update which doc

| Change | Update |
| :--- | :--- |
| Features, stack versions, package moves | `README.md` (including directory tree) |
| Rebalance math / execution sequence | `docs/ALGORITHM.md` + portfolio-rebalancing-math skill |
| Flow / SSE / SharedFlow wiring | `docs/FLOWS.md` + coroutines-flows-sse skill |
| Evaluation scenarios / harness | `docs/EVALUATION.md` + write-kotest |
| Non-tested packages added/moved | JaCoCo exclusions in `build.gradle.kts` (report **and** verification) |
| Stack version bumps | `.agents/AGENTS.md` § stack + README |
| Agent workflows / quality paths | `.agents/AGENTS.md` and relevant skills |

Agent rules path is **`.agents/AGENTS.md`** — never assume a root `AGENTS.md`.

## Markdown hygiene

```bash
npx markdownlint-cli .agents/AGENTS.md CHANGELOG.md README.md docs/*.md .agents/skills/**/SKILL.md
```

Blank lines around lists; consistent heading hierarchy; no trailing whitespace.

## Checklist

- [ ] CHANGELOG entry present for user-visible work
- [ ] README tree / ALGORITHM / FLOWS / EVALUATION touched when relevant
- [ ] JaCoCo exclusions synced with package layout
- [ ] Markdown lint clean on `.agents/AGENTS.md` and skills
