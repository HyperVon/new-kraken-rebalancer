---
name: kotlin-refactoring-and-cleanup
description: >-
  Refactor Kotlin JVM/JS to project standards — eliminate FQNs, move magic
  strings into :common, clear warnings, DRY helpers, env-agnostic paths, and
  sync README/JaCoCo. Use when cleaning code, reducing duplication, or fixing
  style/warning debt (not for greenfield features).
---

# Kotlin Refactoring & Code Cleanup

## 1. No FQNs

Use imports unless resolving a true name collision.

## 2. Magic strings → `:common`

Move UI labels, HTML IDs/attrs, CSS classes, routes, and shared enums into
`common/src/commonMain/` (`ViewText`, `HtmlIds`, `CssClass`, `Routes`,
`TimeRange`, `OrderSide`, `PrecisionConstants`, …). See common-kmp-module skill.

## 3. Warnings & null safety

Remove unused imports/casts; migrate deprecations; prefer `?.` / `?:` over `!!`.
`allWarningsAsErrors` means warnings fail the build.

## 4. Environment agnosticism

No `/Users/...`, `C:\Users\...`, or hostnames like `my-macbook` / `charles-pc`.
Use relative paths, temp dirs, `localhost`, `app-server.local`.

## 5. DRY & SRP

Extract layout/component helpers; keep manager/analyzer/executor boundaries.
CSS stays modular under `view/css`.

## 6. Docs & exclusions sync

Package moves → update README tree + JaCoCo exclusions (report and verification).
See changelog-and-docs-sync and gradle-quality-gates skills.

## Scanner

```bash
./.agents/skills/kotlin-refactoring-and-cleanup/scripts/find_anti_patterns.sh
```

## Checklist

- [ ] FQNs gone; magic strings in `:common`
- [ ] No absolute paths; warnings clean
- [ ] README + JaCoCo synced; tests pass
- [ ] Markdown lint includes `.agents/AGENTS.md`
