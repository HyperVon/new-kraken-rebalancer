---
name: code-review
description: >-
  Structured code review covering SRP architecture, BigDecimal safety, dryRun vs
  simulation, Kraken rate limits, exchange-claim verification (cl_ord_id vs
  userref), :common purity, coverage gates, and project conventions. Use when
  reviewing PRs, diffs, or auditing code quality.
---

# Code Review Skill

## Review dimensions

### 1. Code quality

- SRP: `PortfolioManagerImpl` / `PortfolioAnalyzerImpl` / `OrderExecutorImpl` /
  `PortfolioCalculations` boundaries respected.
- No FQNs; magic UI/domain strings live in `:common`.
- `commonMain` stays pure KMP (no JVM/JS-only imports).
- No absolute user paths or machine hostnames.
- Comments: only non-obvious complexity; flag wrong/stale/noisy comments (see
  [complex-code-comments](../complex-code-comments/SKILL.md)).

### 2. Financial math & execution

- BigDecimal scales 8/2; tests use **`shouldBeEqualComparingTo`**.
- ATH → drawdown deployment, fiat correction, dust threshold.
- Sell-first; USD poll up to 3× with 250ms exponential backoff; best positive /
  95% settle; abort buys if none; cycle 99% buy budget (`withStableBackend`).
- See portfolio-rebalancing-math + `docs/ALGORITHM.md`.

### 3. Kraken & modes

- Symbol mapping; `RateLimiter` (12 / 0.33) + Mutex on private calls.
- Lockout backoff 10s→15m via `retryWithFlow`.
- **`dryRun` ≠ `simulation`** — `DynamicKrakenService` routing correct.
- No secret logging.
- AddOrder order identity: live path uses deterministic `cl_ord_id`
  (`OrderExecutorImpl.clientOrderId`); **`userref` is not uniqueness**.
- **Exchange-claim gate:** any PR claim about exchange semantics (idempotency,
  uniqueness, retries, fee fields, order identity) must match current official
  Kraken docs — not memory, changelog prose, or skill text alone. Diffs that
  change AddOrder params are high-risk; ask “does this field do what the PR
  says?” and verify before approving.

### 4. Security

- Dashboard is **no-auth**; CORS via `isLocalOrPrivateOrigin` must not widen to
  public origins casually.
- Validate config/API inputs; no credential leaks.

### 5. Frontend / Flows

- Chart.js deep-clone; **re-attach function callbacks after clone** (e.g.
  `onZoomComplete`).
- History zoom: pan via `chart.zoomScale`, not `options.scales.x` + `update()`
  after the zoom plugin owns the axis; scrubber must enable after drag/wheel
  zoom as well as toolbar Zoom buttons.
- History timeframe updates **all 6** summary cards.
- Hot SharedFlow vs cold Flow usage matches `docs/FLOWS.md`.

### 6. Persistence & tests

- Exposed: transactions, PK updates, cascade deletes.
- Tests: `:memory:`, FakeKraken, `IsolationMode`, evaluation suite awareness.
- README tree + JaCoCo exclusions synced; markdown lint paths use
  `.agents/AGENTS.md`.

### 7. Quality gates

- JVM: 95% line/method/instruction, 90% branch.
- JS: 90% statements/functions/lines, 75% branches.
- Spotless 120-char; `allWarningsAsErrors`.
- CodeQL currently disabled — do not claim it runs on `main`.

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
      order-identity / retry behavior changes
- [ ] `:common` purity; no-auth/CORS local-trust noted if relevant
- [ ] Docs/JaCoCo/markdown paths correct
