---
name: code-review
description: >-
  Structured code review covering SRP architecture, BigDecimal safety, dryRun vs
  simulation, Kraken rate limits, exchange-claim verification (cl_ord_id vs
  userref), :common purity, coverage gates, and project conventions. Use when
  reviewing diffs, auditing code quality, or as a pre-pass before opening a PR.
  Opening or updating a GitHub PR still requires open-pr / commit-and-push with
  mandatory adversarial-pr-review — this skill alone is not enough for PR
  merge readiness.
---

# Code Review Skill

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **code-review** (this) | Convention hunt-list on a diff / audit |
| [adversarial-pr-review](../adversarial-pr-review/SKILL.md) | Mandatory adaptive bounded multi-agent loop before `gh pr create` or push to an open PR |
| [open-pr](../open-pr/SKILL.md) | Create the PR (runs adversarial first) |
| [commit-and-push](../commit-and-push/SKILL.md) | Commit/push; adversarial when updating an open PR |
| [architecture-review](../architecture-review/SKILL.md) | Fresh-eyes redesign brainstorm (not PR gates) |
| [ai-slop-detector](../ai-slop-detector/SKILL.md) | Evidence-ladder/severity audit of slop-like artifacts; run either this or code-review, not both, unless asked |

**PR handoff:** If the user is opening or updating a pull request, follow
[open-pr](../open-pr/SKILL.md) or [commit-and-push](../commit-and-push/SKILL.md)
— both require [adversarial-pr-review](../adversarial-pr-review/SKILL.md). Do
not treat this skill’s checklist as a substitute.

## Review dimensions

### 1. Code quality

- SRP: `PortfolioManagerImpl` / `PortfolioAnalyzerImpl` / `OrderExecutorImpl` /
  `PortfolioCalculations` boundaries respected.
- No FQNs; magic UI/domain strings live in `:common`.
- **Constant catalogs in CodeGen**: groups of static string/scalar constants, UI copy,
  routes, or exchange aliases must be declared in `resources/codegen/*.yaml` via
  `@GenerateStringConstants` (not handwritten Kotlin `object Foo { const val ... }`).
- `commonMain` stays pure KMP (no JVM/JS-only imports).
- No absolute user paths or machine hostnames.
- Comments: only non-obvious complexity; flag wrong/stale/noisy comments (see
  [complex-code-comments](../complex-code-comments/SKILL.md)).
- Lean code: no dead guards for contractually impossible states, no duplicated
  validation below the owning boundary, no speculative abstractions without a
  current seam.
- Tests: each kills a distinct defect class; flag impossible-case tests,
  cosmetic duplicates, and coverage padding (see
  [ai-slop-detector](../ai-slop-detector/SKILL.md)).

#### SRP do-nots (flag any violation)

| Type | Must NOT | May call |
| :--- | :--- | :--- |
| `RebalancerEngine` / `PortfolioCalculations` | Network, SQLite, Koin, Ktor, kotlinx.html | Pure `BigDecimal` + `Settings` / `Allocation` inputs |
| `PortfolioAnalyzerImpl` | Place orders; write trades | Kraken reads, ATH I/O, `RebalancerEngine`, emit analysis |
| `OrderExecutorImpl` | Target math, allocation %, ATH | `executeOrder`, USD settle polls, `withStableBackend` |
| `PortfolioManagerImpl` | Inline rebalance math or raw AddOrder | Orchestrate analyzer → executor → `addSnapshot` |
| `TradeHistoryServiceImpl` façade | Reimplement sync/query in controllers | Delegate to Sync / SnapshotStore / Query / Reconstruction |
| `view/component/*` | Business rules, Kraken calls | Render DTOs + `:common` constants; receive `Settings` |
| `:common` `commonMain` | JVM/JS-only imports, SLF4J, `java.math.*` | Routes, IDs, CSS tokens, wire DTOs, KMP-safe models |

### 2. Financial math & execution

- BigDecimal scales 8/2; tests use **`shouldBeEqualComparingTo`**.
- Flag raw `setScale(2)` / `doubleValue()` in money paths; prefer
  `toUsdScale()` / `toCryptoScale()`.
- ATH → drawdown deployment, fiat correction, dust threshold.
- Sell-first; prefer fill-confirmed sell proceeds, else USD poll; 3× / 250ms
  backoff; best positive / 95% settle; abort buys if none; cycle 99% buy budget
  (`withStableBackend`).
- See portfolio-rebalancing-math + `docs/ALGORITHM.md`.

### 3. Kraken & modes

- Symbol mapping; `RateLimiter` (12 / 0.33) + Mutex on private calls.
- Lockout backoff 10s→15m via `retryWithFlow`.
- **`dryRun` ≠ `simulation`** — `DynamicKrakenService` routing correct.
- No secret logging.
- AddOrder order identity: live path uses deterministic `cl_ord_id`
  (`OrderExecutorImpl.clientOrderId`); **`userref` is not uniqueness**.
- Real live placement persists `PENDING` before AddOrder; AddOrder is attempted
  once. Ambiguous responses become `UNCERTAIN`, abort the batch, and block
  later live submissions. Unresolved rows must survive sync/dedupe/pruning.
- **Exchange-claim gate:** any PR claim about exchange semantics (idempotency,
  uniqueness, retries, fee fields, order identity) must match current official
  Kraken docs — not memory, changelog prose, or skill text alone. Canonical
  refs live in [kraken-api-integration](../kraken-api-integration/SKILL.md)
  (AddOrder + `cl_ord_id` guide). Diffs that change AddOrder params are
  high-risk; ask “does this field do what the PR says?” and verify before
  approving.

#### Backend pinning & modes (money path)

- [ ] Normal cycle-wide session wraps in-cycle sync plus the full rebalance in
      `krakenService.withStableBackend { … }`
- [ ] Trade sync during a cycle uses the same pin
- [ ] Nested `withStableBackend` reuses the outer pin — never re-resolves
- [ ] `simulation` selects the backend in `DynamicKrakenService`; `dryRun` is
      enforced inside the active backend's `executeOrder` — not in routing
- [ ] Unpinned reads (dashboard ticker/OHLC) are fine outside cycles; never mix
      pinned and unpinned Kraken calls inside one settle/placement sequence
- [ ] Live AddOrder includes deterministic `cl_ord_id` when `cycleId` is
      non-blank; blank `cycleId` omits it
- [ ] Live AddOrder journal is write-ahead; ambiguous outcomes are never retried
      or automatically reconciled/removed
- [ ] Flag any PR setting `dryRun = false` in templates, examples, or tests
      without explicit live-trading justification

### 4. Security

- Dashboard is **no-auth**; CORS via `isLocalOrPrivateOrigin` must not widen to
  public origins casually.
- Validate config/API inputs; no credential leaks.

### 5. Frontend / Flows

- Flag newly introduced ARIA attributes, roles, accessibility-only copy, or
  accessibility-specific scope unless the user explicitly requested it;
  preserve unrelated existing metadata.
- Chart.js deep-clone; **re-attach function callbacks after clone** (e.g.
  `onZoomComplete`).
- History zoom: pan via `chart.zoomScale`, not `options.scales.x` + `update()`
  after the zoom plugin owns the axis; scrubber must enable after drag/wheel
  zoom as well as toolbar Zoom buttons.
- History timeframe updates **all 6** summary cards.
- Hot SharedFlow vs cold Flow usage matches `docs/FLOWS.md`.

#### Flow / SSE diff checks

- [ ] Config watch uses `collectLatest`; active execution sessions defer
      publication until the cycle exits
- [ ] `CancellationException` always rethrown in loop/SSE handlers
- [ ] Hot SharedFlow producers use `tryEmit` with documented overflow
      (config replay=1 DROP_OLDEST; snapshots buffer 16)
- [ ] SSE handler sends `getLatestSnapshot()` before collecting the flow
- [ ] Non-cancellation SSE errors stay isolated per client session
- [ ] Cold flows (paginated sync, balance poll) are not broadcast as hot flows

#### UI safety chrome (SSR + JS)

- [ ] Every page header uses `brandWithMode(settings)`; `Settings` threaded through
- [ ] Mode precedence `simulation` > `dryRun` > live — never inferred in client JS
- [ ] Stream chip uses `ViewText.STREAM` / `STREAM_STALE` only — never "LIVE"
- [ ] Dashboard fragment updates `#header-status` via `hx-swap-oob`
- [ ] Static assets cache-busted via `commonMetadataAndStyles()` / `rebalancerJsSrc()`
- [ ] History timeframe changes update all 6 summary cards

### 6. Persistence & tests

- Exposed: transactions, PK updates, cascade deletes.
- Tests: `:memory:`, FakeKraken, `IsolationMode`, evaluation suite awareness.
- README tree + JaCoCo exclusions synced; markdown lint paths use
  `.agents/AGENTS.md`.

### 7. Quality gates

- JVM: 95% line/method/instruction, 90% branch.
- JS: 90% statements/lines, 80% functions, 75% branches.
- Spotless 120-char; `allWarningsAsErrors`.
- CodeQL Java/Kotlin analysis runs on `main`; verify the workflow's Action/bundle
  pin and Kotlin compiler support before changing it.

### High-risk defect categories

- *Concurrency & atomicity:* unlocked mutexes/locks on early return or exception paths; check-then-act (TOCTOU) races; coroutine/thread leaks without lifecycle termination; unhandled async task failures.
- *State transitions & persistence:* partial multi-step persistence writes lacking transaction rollback; missing database connection/file handle release in `finally` blocks; idempotency failures during retries.
- *Input & boundary validation:* missing bounds, size, or type checks on untrusted payloads; unescaped inputs reaching regex/SQL/shell parsers; sensitive data leaked into log lines.
- *Error propagation:* swallowed exceptions returning synthetic default values that masquerade as success; missing error wrapping that loses operational root cause.
- *Effective fix / behavior change:* trace the reported root cause to changed lines and confirm the diff actually alters the behavior that produces it; adding only logging, formatting, or comments is an incomplete fix.

### Reviewer anti-patterns to avoid

- **Style nitpicking:** Do not report formatting, identifier casing, or subjective syntax preferences if automated linters (`spotlessCheck`) pass and code matches local conventions.
- **Speculative vulnerabilities:** Do not report security flaws without demonstrating a concrete untrusted data flow, unverified input, or reachable abuse path.
- **Scope creep & unsolicited redesign:** Do not demand an architectural rewrite when reviewing a localized bug fix or narrow feature addition.
- **Phantom verification:** Never claim tests passed or code is verified without running the exact test command and inspecting output.

## Output template

````markdown
# Code Review Summary

## Highlights & Strengths

## Issues & Recommendations

### [CRITICAL / MAJOR / MINOR / SUGGESTION] Title

- **Category**: …
- **Location**: `path:Lstart-Lend`
- **Issue** / **Impact** / **Suggested Fix**
````

## Checklist

- [ ] Matcher name `shouldBeEqualComparingTo`; coverage gates precise
- [ ] dryRun/simulation distinct; sell-then-buy + fail-closed settle + cycle 99% budget
- [ ] Exchange semantic claims verified against official docs when AddOrder /
      order-identity / retry behavior changes **or** those claims appear in the
      diff (including skill/docs-only PRs) — see kraken-api-integration links
- [ ] `:common` purity; no-auth/CORS local-trust noted if relevant
- [ ] Docs/JaCoCo/markdown paths correct
