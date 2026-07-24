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

- BigDecimal 8/2; matcher **`shouldBeEqualComparingTo`**.
- Sell-first, 95% settle, 99% buy cap; RateLimiter Mutex + lockout backoff.
- `Dispatchers.IO` for DB/network; dryRun ≠ simulation.

## Pass 3 — Architecture

- SRP: manager / analyzer / calculations / executor.
- Exposed: `safeTransaction`, PK updates, cascades.
- Views in `view/component/*`; CSS modules; Chart.js deep-clone + DOM cleanup.

## Pass 4 — Verify

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

Also expect JaCoCo 95%/90% and Karma 90%/75% gates. Loop to Pass 1 until clean.

## Checklist

- [ ] Full 4-pass cycle with zero remaining issues
- [ ] Matcher/coverage/AGENTS paths consistent with project rules
- [ ] Backend + frontend tests green
