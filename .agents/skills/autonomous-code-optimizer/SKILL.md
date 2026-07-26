---
name: autonomous-code-optimizer
description: >-
  Autonomous multi-pass audit/refactor loop across Kotlin JVM, Kotlin/JS,
  Exposed, HTML DSL, and Kotest until a full cycle finds zero issues. Use when
  the user asks for exhaustive cleanup, /goal optimization, or repo-wide
  convergence — not for small single-file edits.
---

# Autonomous Code Optimizer

Run only for exhaustive, end-to-end optimization. Terminate **only** when a
complete 4-pass cycle yields zero new fixes, zero warnings, and zero test
failures.

For **targeted** FQN / `:common` / warning cleanup on a few files, use
[kotlin-refactoring-and-cleanup](../kotlin-refactoring-and-cleanup/SKILL.md)
instead — this skill is the exhaustive multi-pass mode.

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **autonomous-code-optimizer** (this) | Repo-wide multi-pass until a clean cycle |
| [kotlin-refactoring-and-cleanup](../kotlin-refactoring-and-cleanup/SKILL.md) | Scoped refactor / style debt (default for “clean this up”) |
| [code-review](../code-review/SKILL.md) | Diff/audit hunt-list (not a rewrite loop) |
| [continuous-improvement](../continuous-improvement/SKILL.md) | Product/UI/docs improvement cycle with backlog/PR |

## Pass 1 — Static quality & security

1. Replace inline FQNs with imports.
2. Remove `/Users/...` paths and machine hostnames.
3. Move magic UI/domain strings into `:common`.
4. Ensure no secrets in VCS.

```bash
./.agents/skills/autonomous-code-optimizer/scripts/audit_and_verify.sh
```

Markdown lint in that script targets **`.agents/AGENTS.md`** and skills (not
root `AGENTS.md`).

## Pass 2 — Financial & concurrency

- [ ] No `Double` / `Float` in production money paths (`src/main`,
      `common/commonMain`); use `BigDecimal` + `toUsdScale()` / `toCryptoScale()`
- [ ] Tests use only `shouldBeEqualComparingTo` for `BigDecimal`
- [ ] Sell-first → settle (95% / 3× / 250ms) → 99% buy cap intact
- [ ] `withStableBackend` wraps full cycles/sync; no unpinned
      `executeOrder` / settle in the money path
- [ ] `collectLatest` loops rethrow `CancellationException` — never logged as a
      cycle error
- [ ] Hot SharedFlows keep documented buffer/overflow; SSE sends the latest
      snapshot before collecting
- [ ] `dryRun` ≠ `simulation` — scan tests/examples for casual `dryRun = false`
- [ ] RateLimiter Mutex + lockout backoff unchanged on private calls
- [ ] `Dispatchers.IO` for DB/network

## Pass 3 — Architecture

- [ ] SRP boundaries match the code-review do-not table
- [ ] Repositories use `safeTransaction` / `safeTransactionIO`
- [ ] New UI lives in `view/component/*` + `view/css/*`; strings/IDs in `:common`
- [ ] HTMX SSE shell intact; no new `EventSource` in `frontend-js`
- [ ] Mode plate + `hx-swap-oob` header status preserved
- [ ] Cross-check domain skills (math, flows, exposed, `:common`)
- [ ] Chart.js deep-clone + DOM cleanup

## Pass 4 — Verify

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

Final pass: `./gradlew build jacocoTestCoverageVerification --rerun-tasks`,
plus `:frontend-js:jsBrowserTest` when JS changed.

Also expect JaCoCo 95%/90% and Karma 90%/75% gates. Loop to Pass 1 until clean.

### Do not count as a "clean cycle"

- Widening JaCoCo/Karma exclusions to pass gates without new tests
- Setting `dryRun = false`, or removing the mode plate / cache-bust, to "fix" UI
- Replacing fail-closed settle abort with timeout-and-continue
- Using `GlobalScope`, blocking IO on the Default dispatcher, or swallowing
  `CancellationException`
- Trusting cached `./gradlew` results after parallel agents in one clone
  (re-run with `--rerun-tasks`)

## Checklist

- [ ] Full 4-pass cycle with zero remaining issues
- [ ] Matcher/coverage/AGENTS paths consistent with project rules
- [ ] Backend + frontend tests green
