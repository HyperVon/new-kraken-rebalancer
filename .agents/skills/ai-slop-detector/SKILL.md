---
name: ai-slop-detector
description: >-
  Audit and, when explicitly requested, clean up artifact-level AI slop in
  code, tests, documentation, and diffs. Finds evidence-backed quality defects
  such as needless complexity, architecture drift, invented integrations,
  misleading docs, and tests that do not protect required behavior. Never
  attributes authorship or intent to a contributor. Use for "AI slop",
  "AI-ish code", de-slopping, plausible-but-invented artifacts, mirror tests,
  or an artifact-level code-quality audit.
---

# Evidence-Based AI Slop Audit & Cleanup

**AI slop is not code written with AI.** It is an artifact that appears
plausible but imposes avoidable review, maintenance, correctness, or safety
cost because it lacks the judgment required by this repository's contracts.

This skill evaluates **artifacts and their effects**, never a contributor's
tool use, competence, or intent. There is no reliable way to prove that code
was AI-generated. Emoji, Unicode, verbosity, formulaic prose, PR size, author
history, or a contributor's answer to a question are, at most, prompts to read
more closely. They are not evidence of slop, authorship, severity, or bad
faith.

Default to an **audit and report**. Modify code only when the user explicitly
asks to clean up, eliminate, or fix findings.

## Scope and boundaries

| Skill | Use it for |
| :--- | :--- |
| **ai-slop-detector** (this) | Evidence-backed audit of needless complexity, invented behavior, misleading tests/docs, and architecture drift |
| [code-review](../code-review/SKILL.md) | Convention/safety checklist; this skill audits evidence. Run either, not both, unless the user asks for both |
| [reduce-code-size](../reduce-code-size/SKILL.md) | Behavior-preserving simplification after a validated finding |
| [write-kotest](../write-kotest/SKILL.md) | Adding or correcting JVM/JS/evaluation tests |
| [documentation-review](../documentation-review/SKILL.md) | Full factual documentation audit against source |
| [adversarial-pr-review](../adversarial-pr-review/SKILL.md) | Mandatory dual-model loop for a PR being opened or updated |
| [gradle-quality-gates](../gradle-quality-gates/SKILL.md) | Project build, formatting, coverage, and lint verification |
| [autonomous-code-optimizer](../autonomous-code-optimizer/SKILL.md) | Unattended multi-pass refactor-to-zero; prefer for broad cleanup requests without a bounded audit |

This skill does not replace an applicable owner skill, mandatory PR workflow,
or quality gate. Load domain skills for touched code, especially
`portfolio-rebalancing-math`, `kraken-api-integration`,
`dry-run-and-simulation`, `common-kmp-module`, `exposed-repository`,
`coroutines-flows-sse`, `ktor-html-views`, `frontend-js-development`,
`trade-history-sync`, and `koin-di-and-config`.

## Evidence standard

Call something slop only when an observable artifact-level deficit is present.
Use the strongest evidence available:

1. **Reproduction or failing check**: compiler/type error, failing test,
   unsafe runtime behavior, security exposure, or broken user flow.
2. **Explicit contract conflict**: an invariant in source, public API,
   configuration schema, or protocol; or in tests, `AGENTS.md`, and owning
   skills after verifying they match source (source is the truth, not older
   docs).
3. **Local inconsistency with a cost**: duplicate mechanism, bypassed boundary,
   or needless abstraction that demonstrably complicates maintenance or changes
   behavior.
4. **Review prompt only**: unusual style, size, generated-looking prose, or a
   pattern with insufficient context. Investigate; do not report it as a defect.

Every finding needs all of the following:

- A source, contract, diff, test, or reproduction anchor.
- The actual or credible failure/maintenance outcome.
- The smallest safe correction, or a reason to defer.
- A severity based on impact, never on suspected AI involvement.

### Severity rubric

| Severity | Evidence-backed outcome |
| :--- | :--- |
| **P0** | Can lose money, expose secrets/security, perform destructive action, invent an external API/config/dependency that causes bad operation, or conceal broken required behavior with test changes |
| **P1** | Breaks the build, a contract, architectural boundary, lifecycle/cancellation rule, persistence invariant, or required user/API behavior |
| **P2** | Demonstrably duplicates logic, demonstrably adds unneeded complexity, demonstrably weakens meaningful tests, or leaves inaccurate/misleading documentation |
| **P3** | Reviewability or style issue with no demonstrated correctness/maintenance impact; normally suggest rather than change |

## Audit workflow

Copy this list and track it for non-trivial audits:

```text
- [ ] Step 0: Establish scope, contracts, and mode
- [ ] Step 1: Gather diff and high-risk evidence
- [ ] Step 2: Run validity checks
- [ ] Step 3: Inspect implementation and architecture fit
- [ ] Step 4: Inspect test independence and coverage intent
- [ ] Step 5: Inspect documentation and integration claims
- [ ] Step 6: Classify and report evidence-backed findings
- [ ] Step 7: Apply minimal cleanup (only when requested)
- [ ] Step 8: Verify corrections and quality gates
```

### Step 0: Establish scope, contracts, and mode

1. Determine whether this is a file, diff, PR, subsystem, or full-repository
   audit. Prefer changed files and their direct contracts before expanding.
2. Read `.agents/AGENTS.md`, then the owner skills and neighboring code that
   establish the intended pattern.
3. State the mode: **audit** by default; **cleanup** only with explicit user
   direction. An audit does not silently refactor code.
4. Identify high-risk paths first: money/order execution, mode selection,
   credentials, CORS, persistence, concurrency, public routes, configuration,
   and tests changed alongside those paths.

For a large or broad diff, increase review depth or request a walkthrough of
the architecture and verification strategy. Diff size is a review-budget
signal, not evidence of slop. A walkthrough asks any contributor to explain
the submitted design and contracts; it is not an AI-authorship interrogation.

### Step 1: Gather diff and high-risk evidence

For a PR or branch, inspect the diff before searching broadly:

- Production, test, build/dependency, configuration, route/API, and document
  changes together.
- Added imports, dependencies, generated code, feature flags, settings, and
  configuration templates.
- Assertions weakened or removed, tolerances widened, mocks broadened, and
  error/edge cases deleted.
- Related source contracts and previous behavior when a test change is unclear.

Use `git diff` and history to establish the expected behavior of a changed
test. Do not infer motive from whether the production or test code was written
first.

### Step 2: Run validity checks

Run the smallest relevant checks early. Compilation and static analysis catch
invented imports, methods, APIs, configuration, and invalid examples better
than prose inspection does.

- Compile or test the affected module when feasible.
- Run targeted tests for changed behavior.
- Inspect dependency declarations before accepting a new library/API claim.
- Check routes, `Settings`, shared contracts, and configuration templates when
  code or docs refer to them.
- Use the owning quality/documentation skills for full gates when the scope
  requires them.

Failure to run a costly check is not proof of a defect. Record the gap and
avoid overstating confidence.

### Step 3: Inspect implementation and architecture fit

#### Meaningful code-level signals

Investigate these only when there is an observable cost or contract conflict:

| Signal | Establish the finding by showing |
| :--- | :--- |
| Delegate-only wrapper or duplicate helper | No policy, transformation, error boundary, or reuse value; a direct existing call is clearer |
| Generic/factory/DSL/sealed abstraction | It hides a simple stable case, adds change points, or duplicates an existing local pattern |
| New subsystem-specific pattern | Neighboring code or owner skill already provides the helper/boundary being bypassed |
| Invented integration | The dependency, route, method, config key, schema field, or external API does not exist or is incompatible |
| Shortcut around types/errors/lifecycle | A failure is swallowed, a type/persistence/money invariant is bypassed, or cancellation is broken |
| Unnecessary volume | Multiple blocks encode the same behavior without a separate contract, not merely a long but essential workflow |

#### Context-dependent constructs

None of these is slop by itself. Inspect the surrounding contract before
reporting it:

| Construct | Valid examples | Report only when |
| :--- | :--- | :--- |
| `@Suppress` | File-local, narrowly scoped, with an evidence-based reason | It conceals a demonstrated type/warning issue without explanation or safer design |
| `catch (Exception)` | Application error boundary with logging/mapping/recovery | It swallows a needed failure or fails to rethrow `CancellationException` in coroutine code |
| `runBlocking` | Application startup/shutdown or a controlled blocking bridge | It blocks a request, coroutine worker, or latency-sensitive path |
| `Thread.sleep` | Deliberate isolated test timing where virtual time cannot model the dependency | It blocks production coroutine work or makes tests flaky when virtual time is available |
| `as` cast / `!!` | Proven internal invariant with a constrained input boundary | Untrusted/nullable data can violate it and there is no safe/error path |
| `object` / singleton | Immutable stateless utility, such as shared calculations | It introduces mutable global state or bypasses necessary lifecycle/DI ownership |
| `call.respondText` | Ktor response boundary for CSS, HTML, or JSON | It bypasses a required content type, escaping, or established view/route contract |
| Raw database transaction | Schema/bootstrap or an intentional local boundary | A suspend repository path bypasses `safeTransactionIO`/`readTransactionIO` or breaks transaction safety |
| One implementation of an interface | Useful boundary, test seam, or intended external contract | It is speculative indirection with no current seam, policy, or use |

#### Repository-specific anchors

Check the actual owner skill/source instead of inventing a fourth pattern:

| Area | Contract to verify |
| :--- | :--- |
| `:common` | Pure KMP only: no JVM/JS-only imports; shared routes/DTOs/constants belong here when they serve both targets |
| Money and orders | `BigDecimal`; precision rules; dry-run differs from simulation; sell-first, 99% cash budget, durable live-order journal, and `cl_ord_id` safety |
| Kraken I/O | `RateLimiter`, `Mutex`, retry/backoff, symbol mapping, and real API surface per `kraken-api-integration` |
| Repositories | Existing Exposed helpers: `safeTransactionIO` for writes and `readTransactionIO` for reads when applicable |
| Coroutines/Flows | No `GlobalScope`; preserve cancellation; choose replay/buffer semantics from the flow's consumer contract, not a universal `SharedFlow` recipe |
| Ktor views/routes | `DashboardRoutes`/`DashboardController` and `view/component/*` own the established boundary; raw response text can still be valid at that boundary |
| JSON | Jackson is the established backend serializer. Match the neighboring route/service and verify dependencies before introducing another serializer |
| Tests | Kotest `StringSpec` with `init { }`; `shouldBeEqualComparingTo` for `BigDecimal`; `FakeKrakenService` for controllable unit/evaluation cases and `SimulatedKrakenService` for emulator integration behavior |

### Step 4: Inspect test independence and coverage intent

Tests are slop only when they fail to protect a stated behavior, actively hide a
required behavior, or add needless maintenance with no distinct defect class.
Do not use test count, LOC ratio, parameter count, or mocking alone as proof.

#### Test independence checklist

- Derive the expected result from a contract, invariant, external protocol, or
  independently calculated oracle, not by duplicating the implementation's
  branch/formula.
- Ask whether a plausible wrong variant would fail: reversed comparison, wrong
  rounding, missing boundary, omitted error mapping, or incorrect collaborator
  order.
- Assert mock interactions when the interaction itself is the contract, such as
  protocol sequence, idempotency key, ordering, retry, or boundary delegation.
- Exercise null only for nullable/untrusted input; exercise concurrency only
  for shared/concurrent behavior; test data-class equality only when custom
  semantics or an external contract makes it meaningful.
- Treat a large table or test harness as a prompt to identify defect classes,
  not a defect. Evaluation, fuzzing, and integration fixtures legitimately add
  volume.

Paraphrased from a contract-based test in this repository. The expected `100`
comes from the documented zero-target behavior, not from copying the division
branch:

```kotlin
class PortfolioCalculationsTest : StringSpec() {

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        "returns 100 percent deviation for a valued zero-target holding" {
            val deviationPercent = PortfolioCalculations.calculateDeviationPercent(
                deviationUSD = BigDecimal("50.00"),
                targetValueUSD = BigDecimal.ZERO,
                currentValueUSD = BigDecimal("50.00"),
            )

            deviationPercent.shouldBeEqualComparingTo(BigDecimal("100"))
        }
    }
}
```

#### Test-provenance delta

When tests change alongside implementation, specifically inspect a delta that:

- Relaxes/removes assertions or edge cases.
- Widens tolerances or turns exact checks into broad matches.
- Broadens mocks/stubs so an incorrect interaction passes.
- Replaces behavioral assertions with implementation-shaped checks.

Use source contracts, adjacent tests, `git diff`, and history to determine the
required behavior. Report a P0 only if the change demonstrably conceals broken
required behavior; otherwise state the uncertainty and request the contract.

### Step 5: Inspect documentation and integration claims

Documentation slop is factual or operational harm, not simply a terse style:

- Commands, imports, class/method names, routes, config keys, and examples must
  agree with current source/build files.
- Setup and safety instructions must match real modes, required permissions,
  local-trust assumptions, and defaults.
- Remove narrative comments/KDoc that restate code only when they add no domain
  rationale; preserve or improve explanations of non-obvious safety invariants.
- Send broad docs audits to `documentation-review`; use
  `complex-code-comments` for targeted comment hygiene.

### Step 6: Classify and report

Use neutral, concrete language: “the assertion no longer verifies the documented
dust boundary,” not “the author generated a fake test.” Keep unproven concerns
in a questions/deferred section rather than converting them into findings.

```markdown
# Artifact Quality Audit — {scope}

## Verdict
{N} findings: {P0} P0, {P1} P1, {P2} P2, {P3} P3.

## Findings
### [P1] {specific outcome} — `path:Lx-Ly`
- **Category:** contract / architecture / integration / test / docs
- **Evidence:** {reproduction, source contract, diff, or local comparison}
- **Impact:** {what can break or become harder to maintain}
- **Smallest safe correction:** {concrete patch or owner-skill direction}
- **Verification:** {targeted test/check}

## Review prompts / deferred
- {uncertain item, missing evidence, and the question needed to resolve it}

## Checks run
- {command or manual verification}: pass / fail / not run and why
```

### Step 7: Apply minimal cleanup only when requested

Keep corrections narrow and behavior-preserving unless the finding is a proven
behavioral bug. Never delete functionality, meaningful edge cases, or correct
tests because they look generated or verbose.

| Validated issue | Minimal correction |
| :--- | :--- |
| Redundant wrapper/abstraction | Inline/remove it or reuse the established helper, then prove callers retain behavior |
| Architecture/pattern drift | Move/delegate through the owning boundary and add a focused regression test |
| Invented or incorrect integration | Replace with the verified API/dependency/config, or remove the unsupported claim |
| Unsafe error/cancellation behavior | Preserve cancellation, map/propagate meaningful failures, and test the affected path |
| Mirror/weak test | Replace with a contract/invariant/oracle assertion that fails a plausible wrong variant |
| Concealed test regression | Restore the required contract or fix production behavior first; document intentional contract changes |
| Misleading docs/comments | Correct against source or delete only redundant text; retain the why behind safety-critical logic |

Use small cohesive patches and run preservation tests after each relevant
change. Do not require artificial commit splitting or alter PR metadata. If
intent is genuinely ambiguous, report it and ask instead of deleting it. For
substantive simplification or test rewrites beyond a narrow patch, hand off to
`reduce-code-size` / `write-kotest` and verify their gates.

### Step 8: Verify corrections and quality gates

After cleanup:

- Run focused compilation/tests that cover the corrected contract.
- Run the owner skill's required checks for the touched module.
- Run `./gradlew build` when the scope warrants full JVM/JS/coverage gates.
- Run markdownlint for changed Markdown agent/product docs.
- For UI, mode, trading, persistence, security, or live-order changes, run the
  corresponding domain/manual verification before declaring success.
- When opening/updating a PR, complete the applicable PR workflow and
  `adversarial-pr-review`; this audit is not a substitute.

## Anti-patterns

- Calling stylistic cues, contributor behavior, or PR size proof of AI use or
  slop.
- Labeling a familiar construct as bad without inspecting its boundary and
  contract.
- Replacing broad catches without preserving error recovery and coroutine
  cancellation.
- Deleting tests merely to reduce LOC or improve a ratio.
- Treating an ownership walkthrough as a test of who used AI.
- Refactoring uncertain code during an audit-only request.
- Declaring a documentation/API claim false without checking current source,
  build declarations, routes, and configuration.
- Skipping owner skills or quality gates after a validated cleanup.

## Trigger phrases

- "Audit this PR/codebase for AI slop"
- "This looks AI-ish; identify real quality problems"
- "De-slop this file while preserving behavior"
- "Are these tests mirroring the implementation?"
- "Find plausible-but-invented artifacts or invented APIs"
- "Review this change for needless complexity and architecture drift"
