---
name: changelog-and-docs-sync
description: >-
  Keep documentation synchronized — Keep a Changelog entries, README package
  tree, ALGORITHM.md / FLOWS.md / EVALUATION.md, JaCoCo exclusions, and
  .agents/AGENTS.md stack versions. Use when shipping features, refactors, or
  dependency bumps that affect public behavior or layout. For a full audit of
  all docs against source code, use documentation-review instead. For README
  screenshot PNGs after UI changes, use docs-screenshot-refresh.
---

# Changelog & Docs Sync

Incremental sync after a change set. For a whole-repo docs audit (missing,
wrong, stale), use [documentation-review](../documentation-review/SKILL.md).
For refreshing `docs/images/*.png`, use
[docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md).
For the end-user walkthrough, maintain
[docs/USER_GUIDE.md](../../../docs/USER_GUIDE.md) via
[user-guide](../user-guide/SKILL.md).

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
| Dashboard / Settings / History visuals | [docs-screenshot-refresh](../docs-screenshot-refresh/SKILL.md) (overwrite `docs/images/*.png`) |
| User-facing UI / settings meaning | [docs/USER_GUIDE.md](../../../docs/USER_GUIDE.md) + [user-guide](../user-guide/SKILL.md) |

Agent rules path is **`.agents/AGENTS.md`** — never assume a root `AGENTS.md`.

## Markdown hygiene

```bash
npx markdownlint-cli .agents/AGENTS.md CHANGELOG.md README.md docs/*.md .agents/skills/**/SKILL.md
```

Blank lines around lists; consistent heading hierarchy; no trailing whitespace.

## Mermaid diagrams (ALGORITHM / FLOWS / README)

When adding or editing a ```mermaid fence, **parse it under Mermaid 8.x** before
finishing — IDE preview panes often lag GitHub's Mermaid and fail on unquoted
non-ASCII labels (`≥`) and the sequenceDiagram `actor` keyword.

```bash
python3 -m venv /tmp/kraken-screenshots
/tmp/kraken-screenshots/bin/pip install -q playwright
/tmp/kraken-screenshots/bin/python \
  .agents/skills/documentation-review/scripts/validate_mermaid.py
# Or only the files you touched:
#   .../validate_mermaid.py docs/ALGORITHM.md docs/FLOWS.md
```

Syntax rules and the full audit path live in
[documentation-review](../documentation-review/SKILL.md) (Mermaid compatibility).

## Checklist

- [ ] CHANGELOG entry present for user-visible work
- [ ] README tree / ALGORITHM / FLOWS / EVALUATION touched when relevant
- [ ] JaCoCo exclusions synced with package layout
- [ ] UI visual changes → docs-screenshot-refresh for `docs/images/*.png`
- [ ] User-facing behavior → `docs/USER_GUIDE.md` (with current screenshots)
- [ ] Markdown lint clean on `.agents/AGENTS.md` and skills
- [ ] Mermaid edits → `validate_mermaid.py` exit 0 (Mermaid 8.x / IDE baseline)
