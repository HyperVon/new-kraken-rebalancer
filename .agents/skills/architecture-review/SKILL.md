---
name: architecture-review
description: >-
  Third-party full-system architecture review — discovers the product from code
  with no loyalty to the current design, then brainstorms meaningful alternative
  architectures, stacks, module boundaries, and redesigns that improve quality,
  security, readability, or maintainability. Ends with an interactive Cursor
  Canvas so the user picks Keep / Evolve / Replace / Skip per finding before any
  implementation. Use when the user asks for an architecture review, system
  redesign brainstorm, greenfield rethink, tech-stack rethink, or “fresh eyes” /
  outside-architect critique. Recommend only; do not implement.
---

# Architecture Review (third-party)

Act as a **top-tier staff/principal engineer** hired for a clean-sheet review.
Assume **no loyalty** to the current implementation, stack, module layout, UI
approach, persistence model, or docs. Everything is on the table — including
full redesign and reimplementation — **but only when the recommendation is
meaningful and impactful**.

This skill is **recommend-only**. Do not edit application code, open PRs, or
start a rewrite unless the user explicitly asks **after** choosing paths in the
decisions canvas (or an equivalent written acceptance).

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **architecture-review** (this) | Fresh-eyes system design; alternatives up to greenfield; **decisions canvas** |
| [product-opportunity-review](../product-opportunity-review/SKILL.md) | New user capabilities, unmet needs, and feature roadmap; **product strategy** |
| [code-review](../code-review/SKILL.md) | Diff/PR audit **against current project conventions** |
| [autonomous-code-optimizer](../autonomous-code-optimizer/SKILL.md) | Multi-pass cleanup **inside** the existing architecture |
| [continuous-improvement](../continuous-improvement/SKILL.md) | Incremental product/code improvements with backlog/PR |
| [ui-visual-review](../ui-visual-review/SKILL.md) | Visual/UX critique of the live UI only |
| [documentation-review](../documentation-review/SKILL.md) | Docs accuracy vs code (not redesign) |

If the user wants “fix bugs / style in the current shape,” use code-review or
continuous-quality — not this skill.

---

## Stance (non-negotiable)

1. **Discover first.** Infer purpose, constraints, and failure modes from
   **running behavior claims in code**, config, tests, and README — not from
   agent skills or “how we do it here” as sacred.
2. **Docs are claims, not truth.** `docs/ALGORITHM.md`, `docs/FLOWS.md`,
   `.agents/AGENTS.md`, and skills describe intent; verify against source.
3. **No recommendation theater.** Skip taste-only churn, fashionable rewrites,
   and “rewrite in X because X is popular” without a concrete delta in
   quality, security, operability, correctness, or maintainability.
4. **Praise what works.** Explicitly list keep-as-is decisions so the report
   does not read as a demolition.
5. **Money and trust matter.** This product can place live exchange orders and
   exposes a no-auth local dashboard — trading safety and deploy trust model
   outweigh clever abstractions.
6. **Alternatives must be comparable.** For each major recommendation, state
   at least one credible alternative (including “keep current”) with trade-offs.

## Evidence and decision discipline

Use an evidence trail before turning an observation into an architecture
recommendation:

- Prefer code, tests, configuration, and official project/library sources over
  memory, summaries, or vendor claims. Record the path, heading, URL, or command
  that supports a material assertion.
- Label direct observations, reasoned inferences, hypotheses, and unresolved
  assumptions separately. Do not present an inferred scale, demand, or failure
  mode as observed fact.
- When sources disagree, identify the contradiction, prefer the newer and more
  authoritative source when justified, and retain the uncertainty when it
  cannot be resolved. Do not silently average conflicting claims.
- Perform a gap check before recommending a consequential change: identify the
  missing evidence, its impact on the decision, and the smallest safe read,
  test, or observation that would close it. Ask the user only when the gap is a
  real blocker.
- Before each major recommendation, state the decision, constraints, options
  including “keep current,” evidence, reversibility, and validation signal.

This discipline improves the review report; it does not authorize implementation.
The decisions Canvas and recommend-only boundary in Step 6 remain mandatory.

---

## Workflow

```text
- [ ] Step 0: Confirm scope (full system vs focused subsystem)
- [ ] Step 1: Product & constraint discovery (ignore “sacred” stack)
- [ ] Step 2: Map the as-is architecture from code
- [ ] Step 3: Stress-test against review dimensions
- [ ] Step 4: Brainstorm alternatives (including redesign / greenfield)
- [ ] Step 5: Filter to meaningful recommendations only
- [ ] Step 6: Deliver the report **and** a decisions Canvas; stop for picks
```

### Step 0: Scope

Default = **full system**. Narrow only if the user names a subsystem (e.g.
history sync, frontend, persistence, rebalance loop).

Ask once if unclear: full redesign brainstorm vs evolutionary options only.
Default to **both**, ranked.

### Optional parallel discovery

After scope confirmation, fan out bounded, read-only discovery tracks through
the host's native parallel task surface:
backend/domain/trading/persistence; HTTP/SSR/frontend/flows; and
product/security/operations. The parent owns the as-is architecture map,
cross-track synthesis, alternatives, recommendations, and the mandatory
decisions Canvas. Do not parallelize implementation or the final decision step;
if route selection is unavailable, continue the discovery in the parent.

### Step 1: Product & constraint discovery

Answer before proposing changes:

1. **Job to be done** — What does an operator actually need day-to-day?
2. **Hard constraints** — Live money movement? Offline simulation? Single
   operator on a private network? Persistence durability? Latency of the
   rebalance loop?
3. **Risk surface** — Wrong order size, double trade, lost history, leaked
   keys, accidental LIVE mode, public exposure of the dashboard.
4. **Scale reality** — Solo/local tool vs multi-user SaaS. Do not recommend
   enterprise multi-tenant architecture for a single-operator desktop/server
   unless evidence shows that need.

Read lightly: `README.md`, `SECURITY.md`, `rebalancer-config-template.json`,
and entrypoints. Prefer **code + tests** over narrative docs for behavior.

### Step 2: Map as-is (from code)

Build a short mental model (include a mermaid diagram in the report):

| Lens | Where to look (starting points, not sacred) |
| :--- | :--- |
| Process / entry | `KrakenRebalancerApplication`, Koin `AppModule` |
| Domain loop | `service` / `service/impl` — manager, analyzer, calculations, executor |
| Exchange I/O | Kraken client(s), rate limiting, dry-run vs simulation routing |
| Persistence | `repository`, Exposed tables, SQLite usage |
| HTTP / SSR | `controller`, `view/**`, routes in `:common` |
| Client | `:frontend-js` (SSE, charts, HTMX hooks) |
| Shared contracts | `:common` (config models, routes, UI constants) |
| Proof | Kotest specs, evaluation/E2E scenarios |

Record: module boundaries, data ownership, sync/async seams, config schema,
and the **largest coupling clusters**.

Do **not** treat the current SRP names as the only correct split — evaluate
whether the split earns its keep.

#### Flow ownership (record before recommending messaging/SPA rewrites)

| Flow | Type | Owner | Invariant |
| :--- | :--- | :--- | :--- |
| `watchConfigChanges()` | Hot SharedFlow | ConfigServiceImpl | Settings change restarts the loop |
| `snapshotFlow` | Hot SharedFlow | TradeHistorySnapshotStore | `tryEmit`; replay 1, buffer 16 |
| Paginated Kraken fetch | Cold Flow | TradeHistorySyncService | Suspending emit; 300s throttle |
| USD settle poll | Cold Flow | OrderExecutorImpl | Never broadcast to UI |

**Redesign smell:** adding an EventSource/WebSocket client when HTMX SSE +
fragment swap already delivers snapshots.

### Step 3: Review dimensions

Cover each dimension. Skip empty sections in the report; do not invent issues.

1. **Domain model & algorithm placement** — Is rebalancing math isolated,
   testable, and impossible to accidentally bypass? Could a clearer domain
   core (pure functions / hexagonal ports) reduce risk?
2. **Module & package boundaries** — Cycles? God services? UI leaking into
   domain? `:common` earning its KMP cost?
3. **Concurrency & reactive design** — Flows/SSE/SharedFlow: backpressure,
   restart behavior, single-writer assumptions, failure isolation. Would a
   simpler loop + explicit events be safer?
4. **Exchange integration** — Open-order uniqueness (`cl_ord_id`, not
   `userref`), rate limits, retries, dry-run vs simulation clarity, failure
   modes when Kraken is wrong/slow. Treat exchange-doc claims as hypotheses
   until verified against current official Kraken docs (canonical links in
   [kraken-api-integration](../kraken-api-integration/SKILL.md)).
5. **Persistence & history** — SQLite/Exposed fit; reconstruction vs ledger;
   migration story; corruption/recovery.
6. **API & UI architecture** — SSR + HTMX + Kotlin/JS vs SPA vs thinner
   server; chart/SSE coupling; operational clarity of LIVE/DRY/SIM.
7. **Security & trust model** — No-auth local trust: is it documented and
   enforced (CORS/bind address)? Credential handling? Blast radius if exposed?
8. **Operability** — Config atomicity, observability, deploy/run story,
   simulation as a first-class offline path.
9. **Testability & evaluation** — Can critical money paths be proven without
   the full stack? Are evaluation scenarios architectural assets or afterthoughts?
10. **Stack & build** — Kotlin/JVM+JS monolith, Gradle modules, dependency
    weight. Consider alternatives (single JVM, Go, Rust, TS, etc.) **only**
    with a sober migration cost and benefit — see filter below.
11. **Team/change velocity** — What makes the next feature expensive? What
    accidental complexity taxes every change?

#### Money-path stress (mandatory before executor/manager/exchange redesign)

Trace in code, then answer:

1. **Trigger gate** — both `isSignificant` gates (deviation % + USD dust) required?
2. **Price safety** — missing/zero non-USD ticker aborts before any AddOrder?
3. **Sell-first** — sell phase runs before buys; USD settle runs only after
   **≥1 successful** sell (and not dry-run)? (All planned sells failing still
   allows buys from opening USD — do not invent “planned sells block buys”.)
4. **Settle fail-closed** — after settle polls, buys abort only when confirmed
   USD is **≤ 0**? (≥95% of projected is **early-accept**, not the abort
   threshold; best positive below 95% still proceeds.)
5. **Cycle cash cap** — multi-buy batch respects 99% of settled USD?
6. **Submission ambiguity** — real live intent persists before AddOrder,
   AddOrder runs once, ambiguous outcomes block later batches, and unresolved
   rows survive reconciliation/dedupe/pruning? Deterministic `cl_ord_id` is
   open-order uniqueness, **not** full idempotency; `userref` is not uniqueness.
7. **Audit trail** — trades persist `cycleId`, `clientOrderId`, `orderTxid`, and
   unresolved submission state?
8. **Mode clarity** — operator distinguishes SIMULATION / DRY RUN / LIVE
   without reading logs?

If an Evolve/Replace option weakens a yes above, severity is ≥ P0 unless the
compensating control is explicit.

#### Evaluate `withStableBackend` on every exchange redesign

**Current contract:** a CoroutineContext pin is set at cycle/sync entry; nested
calls reuse it; unpinned calls re-read `settings.simulation`.

Ask:

- Does the proposal preserve the **entry-time backend** for the whole
  sell/settle/buy sequence?
- Can a settings save mid-cycle change which Kraken implementation serves an
  in-flight order?
- If services are split, where does the pin live (manager vs executor vs sync)?
  Exactly one owner.

**Keep current** when the pin prevents sim/live flapping. **Replace** only with
an equally explicit session-scoped exchange port (e.g. `ExchangeSession` passed
through the executor) — not ad-hoc `getConfig()` in hot paths.

#### Local-trust dashboard — blast radius

For each recommendation affecting HTTP / CORS / bind / deploy:

1. Who can reach the process (localhost, LAN, tunnel)?
2. Does CORS still require `isLocalOrPrivateOrigin` — any new public origin?
3. Which mutating routes (`POST /settings`, rebalance triggers) stay unauthenticated?
4. What can an attacker on the same network do with SSE + settings?
5. Are secrets env-only and never logged (including HMAC)?

Default stance: no auth is acceptable **only** for a single operator on a
private network. If exposure grows, recommend reverse-proxy auth or
bind-to-localhost — not "add JWT later".

#### Evaluation suite as an architectural asset

Any proposal replacing manager / executor / analyzer must either port
`EvaluationScenariosTest` + `SimulationEvaluationScenariosTest`, or document
kill criteria plus a new proof harness. "Unit tests are sufficient" fails the
filter.

### Step 4: Brainstorm alternatives

For each real pain found, generate 2–4 options spanning:

- **Keep** — current design, maybe clarify docs/tests
- **Evolve** — boundary/refactor inside the repo
- **Replace subsystem** — e.g. different UI approach, different DB, different
  messaging for the loop
- **Greenfield** — new architecture/stack when evolution cannot pay back

Evaluate viable candidates across structural dimensions:

| Dimension | Keep Current | Evolve (Iterative) | Replace (Targeted) | Greenfield (Rewrite) |
| :--- | :--- | :--- | :--- | :--- |
| **Operational Complexity** | Baseline | Low/Medium delta | Medium/High delta | High (new stack/ops) |
| **Migration Risk & Downtime** | None | Low (in-place) | Medium (dual-write/cutover) | High (big-bang/backfill) |
| **Reversibility / Rollback** | N/A | High (feature flag) | Medium (strangler fig) | Low / Hard escape hatch |
| **Blast Radius of Failure** | Known failure modes | Scoped to module | Service boundary | Entire subsystem |
| **State & Data Consistency** | Existing schema | Backward-compatible | Dual-write sync hazards | Complex data migration |
| **Cognitive Load & Churn** | Familiar | Minimal delta | Moderate onboarding | Full team retraining |

Before recommending an **Evolve** or **Replace** path, evaluate:

- *Dual-write split-brain:* Are there race conditions or partial failure scenarios where legacy and new stores diverge during transition?
- *Strangler Fig stall risk:* Can the migration be completed in bounded phases, or does it risk a permanent two-system maintenance burden?
- *Data-at-rest migration:* Does the plan require lossy schema transformation or offline table locking?
- *Network boundary inflation:* Does the proposal convert fast in-process method calls into distributed RPCs without latency/circuit-breaker justification?
- *Avoid solution-anchoring:* Reframe the user's proposed solution into the underlying outcome before comparing options; do not let prompt vocabulary pre-select the alternative set.

### Step 5: Filter (quality bar)

Include a recommendation **only if** it clears most of these:

| Gate | Question |
| :--- | :--- |
| Impact | Materially improves correctness, security, operability, or long-term cost? |
| Evidence | Grounded in specific code/design facts (cite paths), not vibes? |
| Alternatives | Compared fairly to “do nothing”? |
| Cost honesty | Migration/risk sized (S/M/L or person-weeks order-of-magnitude)? |
| Fit | Matches actual product scale (local operator tool), not resume-driven? |

Drop: rename-only churn, micro-optimizations, framework fashion, and
“consistency with industry” without a local payoff.

Severity for included items:

| Sev | Meaning |
| :--- | :--- |
| **P0** | Structural risk to money, security, or data integrity |
| **P1** | High-leverage architecture debt that blocks safety or velocity |
| **P2** | Meaningful redesign slice with clear payoff |
| **P3** | Optional strategic bet (only if unusually high upside) |

Prefer fewer, sharper findings over a long laundry list.

### Step 6: Deliver report + decisions Canvas (mandatory)

1. **Report** — Present the architecture review in chat (and optionally a
   read-only summary canvas). Use the output template below.
2. **Decisions Canvas (required)** — Always write an interactive Cursor Canvas
   so the user can choose a path per finding **before** any implementation.
   If present, read and follow `~/.cursor/skills-cursor/canvas/SKILL.md` when authoring.
3. **Stop** — Do **not** start coding, open PRs, or draft an implementation
   plan until the user returns selections (via the canvas “Send decisions”
   action, a paste of the summary, or an explicit written accept list).

#### Decisions Canvas requirements

Create:

`~/.cursor/projects/<workspace>/canvases/architecture-review-decisions.canvas.tsx`

(Use a timestamped suffix only if that file already exists and must be preserved.)

| Requirement | Detail |
| :--- | :--- |
| Imports | Only `cursor/canvas` (Button, Callout, Card, Select, Pill, Stack, …) |
| Strategic direction | One top-level `Select`: evolve monolith / pure domain+adapters / greenfield / undecided |
| One card per SHORT-ID | Severity pill, title, one-line problem, optional “reviewer lean” |
| Options per finding | At least: **Keep current**, one or more **Evolve/Replace** options from the report, **Defer / skip** |
| Persistence | `useCanvasState` so picks survive reload |
| Convenience | “Apply reviewer leans” + “Clear” |
| Handoff | Button that `dispatch({ type: "newComposerChat", userPrompt })` with a paste-ready summary asking to turn **non-keep / non-skip** items into an implementation plan |
| Chat fallback | Note that the user can reply in the same chat with `SHORT-ID: <choice>, …` |

Populate options from **this review’s** recommendations (do not hard-code a
stale prior review). Each option `label` should match the report’s alternative
wording so the handoff text is unambiguous.

In the chat reply, include a markdown link to the `.canvas.tsx` file and tell
the user to open it beside chat, pick paths, then send decisions back.

---

## Output template

```markdown
# Architecture review (third-party)

## Executive summary
2–4 sentences: what the system is, what is unusually strong, and the single
highest-leverage architectural move (or “no major redesign warranted”).

## Product & constraints (as inferred)
- Job to be done
- Hard constraints / non-goals
- Risk surface
- Scale assumption

## As-is architecture
- Mermaid diagram (modules + key data/control flows)
- Ownership map (who writes what)
- Coupling hotspots

## What to keep
Bullet list of decisions that are sound — with why.

## Recommendations

### [P0|P1|P2|P3] SHORT-ID — Title
- **Theme**: domain | modules | concurrency | exchange | persistence | ui/api |
  security | operability | testability | stack | velocity
- **Evidence**: `path` / behavior observed
- **Problem**: what hurts (impact on quality/security/maintainability/…)
- **Recommendation**: proposed target design (can be ambitious)
- **Alternatives considered**: A / B / keep-current — with trade-offs
- **Expected payoff**: concrete
- **Cost / risk**: S/M/L + main migration hazards
- **Suggested next step**: ADR / spike / evolutionary PR sequence / greenfield
  sketch — not an implementation dump

## Explicit non-issues
Things reviewed and judged fine — prevents thrash.

## Strategic options (only if warranted)
Side-by-side of 2–3 coherent futures (e.g. “evolve monolith”, “extract pure
domain + adapters”, “greenfield rewrite in X”) with recommendation and
kill-criteria.

## Suggested decision order
Ordered SHORT-IDs for the user to accept/reject in the decisions Canvas.
```

---

## After the user chooses

When the user returns selections (canvas handoff or chat):

1. Treat **Keep** and **Defer / skip** as out of scope.
2. Turn accepted **Evolve / Replace / Greenfield** items into an **implementation
   plan** (PR sequence, file ownership, risks) — prefer another canvas for the
   plan when it is multi-PR.
3. Only then implement if the user asks to proceed (or says “do them all”).

---

## Anti-patterns

- Rubber-stamping the current SRP split because skills describe it
- Recommending a rewrite without reading the money path and evaluation tests
- SaaS multi-tenant / Kubernetes / microservices theater for a local tool
- Listing 30 minor cleanups and calling it an architecture review
- Implementing during the review pass
- Delivering recommendations **without** the decisions Canvas
- Treating UI visual polish as architecture (hand to ui-visual-review)
- Ignoring LIVE trading and no-auth dashboard implications

---

## Checklist

- [ ] Discovered product/constraints from code before proposing
- [ ] As-is map + diagram grounded in actual packages
- [ ] Each recommendation passes the impact/evidence/cost filter
- [ ] Keep-as-is and non-issues sections present
- [ ] Alternatives include “keep current” where relevant
- [ ] Decisions Canvas written under managed `canvases/` with Select per SHORT-ID
- [ ] Chat links the canvas and stops for user picks — no drive-by rewrite
