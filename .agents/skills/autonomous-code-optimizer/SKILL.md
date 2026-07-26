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
| [architecture-review](../architecture-review/SKILL.md) | Recommend-only redesign brainstorm (not this in-place loop) |
| [continuous-improvement](../continuous-improvement/SKILL.md) | Product/UI/docs improvement cycle with backlog/PR |

## Stance (non-negotiable)

This skill **improves code quality inside the current architecture**. It does
**not** redesign module boundaries, replace SSR/HTMX, swap SQLite, or invent
new messaging layers.

| Intent | Skill |
| :--- | :--- |
| Clean / converge / remove debt **in place** | **this skill** |
| “Should we redesign X?” | [architecture-review](../architecture-review/SKILL.md) |
| Scoped style/FQN/`:common` cleanup | [kotlin-refactoring-and-cleanup](../kotlin-refactoring-and-cleanup/SKILL.md) |
| Product/UI/docs improvements + PR | [continuous-improvement](../continuous-improvement/SKILL.md) |

**Allowed:** extract helpers, restore SRP, delete dead code, fix layering
violations, DRY within a layer, tighten types, improve tests.

**Forbidden without explicit user approval:** new top-level packages that bypass
`service` / `repository` / `view` / `controller`; replacing
`DynamicKrakenService` pinning; replacing HTMX SSE with a JS `EventSource` /
WebSocket client; collapsing façade collaborators into one god class “for
simplicity.”

## Design principles (apply on every optimization decision)

1. **Fail closed on money paths** — Missing/zero price aborts the cycle;
   unsettleable USD after sells aborts buys; never “timeout and continue” into
   live buys. Prefer no trade over a wrong trade.
2. **Idempotency & stable identity** — Live AddOrder uses deterministic
   `cl_ord_id` from `cycleId|symbol|side` when `cycleId` is non-blank;
   **`userref` is not uniqueness**. Do not remove client order ids while
   deduplicating code.
3. **Mode orthogonality** — `simulation` (which backend) ⊥ `dryRun` (whether to
   place). Do not collapse into one flag for “simplicity.”
4. **Pin for the unit of work** — One rebalance/sync = one pinned backend + one
   `dryRun` snapshot passed into `executeOrder`. Mid-cycle config flips must not
   fork the unit of work.
5. **Cancellation is control flow** — `CancellationException` always rethrown in
   loops/SSE; never logged as a business error (`collectLatest` restarts depend
   on this).
6. **Least privilege at the edge** — No-auth dashboard ⇒ keep CORS
   `isLocalOrPrivateOrigin`; never widen to `*` while cleaning config.
7. **Pure core, impure shell** — Push decisions that need tests without I/O into
   `RebalancerEngine` / `PortfolioCalculations`; keep I/O at analyzer, executor,
   repositories.
8. **Prefer delete + extract over abstract** — Remove dead code and extract
   duplication *within a layer*. Do not introduce frameworks, new DI scopes, or
   generic “BaseService” hierarchies.
9. **Observability without secrecy** — Keep `cycleId` in MDC for cycle logs;
   never log HMAC, API secrets, or resolved credentials.
10. **Coverage is evidence, not a goal** — New tests for behavior you change;
    never widen JaCoCo/Karma exclusions to declare convergence.

## Pass 1 — Static quality & security

1. Replace inline FQNs with imports.
2. Remove `/Users/...` paths and machine hostnames.
3. Move magic UI/domain strings into `:common`.
4. Ensure no secrets in VCS.
5. Apply the **Kotlin craft standards** in
   [architecture-patterns.md](architecture-patterns.md) (errors, nulls,
   concurrency, money, transactions, comments).

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

## Pass 3 — Architecture & design

Read [architecture-patterns.md](architecture-patterns.md) (pattern catalog,
layer graph, refactor decision rules, DI shape). Then audit:

1. **Pattern integrity** — Ports/adapters, façade, strategy+pin, pure engine,
   orchestrator/brain/brawn, SSR composition, hot/cold flows still intact.
2. **Layering** — Run the dependency-direction rg scans; fix violations by
   moving code to the correct layer (not by widening imports).
3. **SRP do-nots** — Match the code-review table; fix by moving methods, not by
   renaming only.
4. **Fail-closed & modes** — Settle abort, price abort, `dryRun`≠`simulation`,
   `cl_ord_id` path unchanged by cleanup.
5. **Persistence** — `safeTransaction` / `safeTransactionIO`; no controller SQL.
6. **UI shell** — Components under `view/component/*` + `view/css/*`; HTMX SSE
   shell; no new `EventSource`; mode plate + `hx-swap-oob` header status;
   Chart.js deep-clone + callback re-attach + DOM cleanup.
7. **DI shape** — `coreModule`/`webModule`; explicit ctors where required.
8. **Cross-check** domain skills only for mechanics you touch (math, flows,
   exposed, `:common`, kraken) — do not paste their essays into diffs.

**Exit criterion:** zero layering violations, zero SRP do-not breaches, no
pattern erased “for simplicity.”

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

### Architecture anti-patterns (never "done")

- **God-class merge** — folding Sync + SnapshotStore + Query into one type
- **Pin bypass** — unpinned `executeOrder` / settle inside a cycle
- **Flag collapse** — single `isSim` replacing `simulation` + `dryRun`
- **Layer leak** — Kraken or SQL from `view/**`, or math from `controller`
- **Second channel** — JS `EventSource`/WebSocket beside HTMX SSE
- **Pure-core poison** — injecting `Database` / `HttpClient` into
  `RebalancerEngine`
- **Abstract factory theater** — new generic base types with one impl
- **Cancellation swallow** — `catch (Exception)` without rethrowing
  `CancellationException`

## Checklist

- [ ] Full 4-pass cycle with zero remaining issues
- [ ] Pass 3 pattern integrity + layering + SRP verified (no pattern erased)
- [ ] Matcher/coverage/AGENTS paths consistent with project rules
- [ ] Backend + frontend tests green
