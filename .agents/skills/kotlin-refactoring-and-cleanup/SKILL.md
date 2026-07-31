---
name: kotlin-refactoring-and-cleanup
description: >-
  Refactor Kotlin JVM/JS to project standards — eliminate FQNs, move magic
  strings into :common, clear warnings, DRY helpers, env-agnostic paths, and
  sync README/JaCoCo. Use when cleaning code, reducing duplication, or fixing
  style/warning debt (not for greenfield features).
---

# Kotlin Refactoring & Code Cleanup

Scoped cleanup for FQNs, `:common` string moves, warnings, and DRY helpers.
For **exhaustive** repo-wide multi-pass convergence until a clean cycle, use
[autonomous-code-optimizer](../autonomous-code-optimizer/SKILL.md) instead.

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **kotlin-refactoring-and-cleanup** (this) | Targeted / incremental cleanup |
| [autonomous-code-optimizer](../autonomous-code-optimizer/SKILL.md) | Exhaustive 4-pass loop until zero issues |
| [common-kmp-module](../common-kmp-module/SKILL.md) | Where shared symbols belong |
| [gradle-quality-gates](../gradle-quality-gates/SKILL.md) | Verify commands after cleanup |
| [ai-slop-detector](../ai-slop-detector/SKILL.md) | Evidence audit that may hand cleanup here |

## 1. No FQNs

Use imports unless resolving a true name collision.

## 2. Magic strings → `:common`

Move UI labels, HTML IDs/attrs, CSS classes, routes, and shared enums into
`common/src/commonMain/` (`ViewText`, `HtmlIds`, `CssClass`, `Routes`,
`TimeRange`, `OrderSide`, `PrecisionConstants`, …). See common-kmp-module skill.

## 3. Warnings & null safety

Remove unused imports/casts; migrate deprecations; prefer `?.` / `?:` over `!!`.
`allWarningsAsErrors` means warnings fail the build.

## 4. BigDecimal hygiene

- Production money code uses `BigDecimal` with `toUsdScale()`,
  `toCryptoScale()`, `toPercentScale()` from `BigDecimalExtensions.kt` plus
  `PrecisionConstants` — not ad-hoc scales or `doubleValue()`.
- Volume division uses an explicit scale with `RoundingMode.HALF_UP`.
- Repeated scales shared with JS belong in `PrecisionConstants` in `:common`.

## 5. Environment agnosticism

No `/Users/...`, `C:\Users\...`, or hostnames like `my-macbook` / `charles-pc`.
Use relative paths, temp dirs, `localhost`, `app-server.local`.

## 6. DRY & SRP

Extract layout/component helpers; keep manager/analyzer/executor boundaries.
CSS stays modular under `view/css`.

## 7. Docs & exclusions sync

Package moves → update README tree + JaCoCo exclusions (report and verification).
See changelog-and-docs-sync and gradle-quality-gates skills.

## 8. Repository I/O

Repository I/O from suspend callers uses
`database.safeTransactionIO(log, "…") { }` from `RepositoryUtils.kt`; do not
call blocking `safeTransaction` from coroutine paths without
`withContext(Dispatchers.IO)`.

## 9. Lean code (no padding)

Refactors must not add guards for impossible states, duplicated validation
below the owning boundary, or speculative abstractions without a current seam.
Validation lives at trust boundaries (external API, user input, config,
persistence, money); inside them, code stays lean and fails hard. See
[ai-slop-detector](../ai-slop-detector/SKILL.md) § Step 3.

## Scanner

```bash
./.agents/skills/kotlin-refactoring-and-cleanup/scripts/find_anti_patterns.sh
```

### Additional scans (run during cleanup)

```bash
rg '\!\!' src/main/ common/src/commonMain/ --glob '*.kt'
rg 'GlobalScope' src/ common/ frontend-js/ --glob '*.kt'
rg '\.toDouble\(\)' src/main/ common/src/commonMain/ --glob '*.kt'
rg 'shouldBeEqualByComparingTo' src/test/ --glob '*.kt'
rg 'dryRun\s*=\s*false' src/test/ src/main/ --glob '*.kt'
```

## Checklist

- [ ] FQNs gone; magic strings in `:common`
- [ ] No absolute paths; warnings clean
- [ ] No dead guards, duplicated validation, or speculative abstractions added
- [ ] README + JaCoCo synced; tests pass
- [ ] Markdown lint includes `.agents/AGENTS.md`
