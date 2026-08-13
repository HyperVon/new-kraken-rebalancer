---
name: ai-slop-detector
description: >-
  Audit and, when explicitly requested, clean up artifact-level AI slop across
  all repository assets: source code, tests, documentation, agent skills,
  agent rules, configuration templates, build scripts, and diffs. Finds
  evidence-backed quality defects such as needless complexity, excessive
  defensiveness, architecture drift, invented integrations, hallucinated API/cli
  claims, misleading docs, dead skill instructions, duplicate tests, and tests
  that do not protect required behavior. Never attributes authorship or intent
  to a contributor. Use for "AI slop", "AI-ish code", de-slopping,
  plausible-but-invented artifacts, mirror tests, or an artifact-level code-quality
  audit.
---

# Evidence-Based AI Slop Audit & Cleanup

**AI slop is not code or documentation written with AI.** It is an artifact that
appears plausible but imposes avoidable review, maintenance, correctness, or
safety cost because it lacks the judgment required by this repository's contracts.

This skill evaluates **artifacts and their effects**, never a contributor's
tool use, competence, or intent. There is no reliable way to prove that an asset
was AI-generated. Emoji, unusual Unicode artifacts, verbosity, formulaic
prose, PR size, author history, or a contributor's answer to a question are,
at most, prompts to read more closely. They are not evidence of slop, authorship,
severity, or bad faith.

Default to an **audit and report**. Modify files only when the user explicitly
asks to clean up, eliminate, or fix findings.

An always-on evidence-first quality baseline also lives in
`.agents/OPERATING.md` (Always-on quality baseline). That compact baseline is the
default posture for every task; this skill owns the deeper procedure when a
request explicitly concerns AI slop, evidence-backed artifact quality, or a
broad cross-artifact audit. Do not broaden an ordinary implementation or review
task into a full audit without evidence or a matching request.

## Scope and boundaries

This skill covers **all repository artifacts**, including:

1. **Source code**: Kotlin JVM (`src/main/`), KMP `:common` (`common/src/`), and Kotlin/JS (`frontend-js/src/`).
2. **Tests**: Kotest JVM specs, Karma/Istanbul JS tests, and Evaluation scenarios (`docs/EVALUATION.md`, `EvaluationScenariosTest`).
3. **Documentation**: Technical and end-user documentation (`docs/*`, `README.md`, `CHANGELOG.md`, `SECURITY.md`, `CONTRIBUTING.md`, `docs/USER_GUIDE.md`, `docs/ALGORITHM.md`, `docs/FLOWS.md`).
4. **Agent skills**: Skill instructions and resources (`.agents/skills/*/SKILL.md`, supporting scripts/examples/references).
5. **Agent rules & guidance**: Agent invariants and operating norms (`.agents/AGENTS.md`, `.agents/OPERATING.md`, `.cursor/rules/*.mdc`, root `CLAUDE.md`, `.github/copilot-instructions.md`).
6. **Build & configuration**: Build scripts (`build.gradle.kts`, `settings.gradle.kts`), configuration templates (`rebalancer-config-template.json`), and tool configurations.

| Skill | Use it for |
| :--- | :--- |
| **ai-slop-detector** (this) | Evidence-backed audit of needless complexity, invented behavior, misleading tests/docs/skills/rules, and architecture drift across all repo artifacts |
| [code-review](../code-review/SKILL.md) | Convention/safety checklist for code diffs; this skill audits evidence across code and docs. Run either, not both, unless the user asks for both |
| [reduce-code-size](../reduce-code-size/SKILL.md) | Behavior-preserving simplification after a validated finding |
| [kotlin-refactoring-and-cleanup](../kotlin-refactoring-and-cleanup/SKILL.md) | Convention cleanup (FQNs, magic strings, warning debt) after a validated finding |
| [write-kotest](../write-kotest/SKILL.md) | Adding or correcting JVM/JS/evaluation tests |
| [documentation-review](../documentation-review/SKILL.md) | Full factual documentation audit against source code |
| [rules-and-skills-audit](../rules-and-skills-audit/SKILL.md) | Structural consolidation (redundancy, index ordering, trigger conflicts) of rules and skills |
| [skill-reviewer](../skill-reviewer/SKILL.md) | Content improvements and domain depth for the agent playbook |
| [adversarial-pr-review](../adversarial-pr-review/SKILL.md) | Mandatory adaptive bounded multi-agent loop for a PR being opened or updated |
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
   markdownlint error, unparseable YAML frontmatter, unsafe runtime behavior,
   security exposure, or broken user/agent flow.
2. **Explicit contract conflict**: an invariant in source, public API,
   configuration schema, or protocol; or in tests, `AGENTS.md`, skills, and
   documentation after verifying they match source (source is the truth, not
   older docs).
3. **Local inconsistency with a cost**: duplicate mechanism, bypassed boundary,
   conflicting agent rule, dead skill instruction, or needless abstraction that
   demonstrably complicates maintenance or changes behavior.
4. **Review prompt only**: unusual style, size, formulaic or boilerplate prose,
   or a pattern with insufficient context. Investigate; do not report it as a
   defect.

Every finding needs all of the following:

- A source, contract, diff, test, skill, doc, or reproduction anchor.
- The actual or credible failure/maintenance outcome.
- The smallest safe correction, or a reason to defer.
- A severity based on impact, never on suspected AI involvement.

### Severity rubric

| Severity | Evidence-backed outcome |
| :--- | :--- |
| **P0** | Can lose money, expose secrets/security, perform destructive action, invent an external API/config/dependency/tool that causes bad operation, state misleading security/trading instructions in docs or skills that cause unsafe real-money/secret handling, or conceal broken required behavior with test/doc changes |
| **P1** | Breaks the build, a code contract, architectural boundary, lifecycle/cancellation rule, persistence invariant, skill YAML frontmatter, or required user/API/agent behavior; or creates directly conflicting agent rules that cause execution failure |
| **P2** | Demonstrably duplicates logic/instructions, demonstrably adds unneeded complexity, demonstrably weakens meaningful tests, leaves inaccurate/misleading documentation, or leaves stale/broken skill instructions or file links |
| **P3** | Reviewability or style issue with no demonstrated correctness/maintenance impact (e.g., minor wording polish, non-misleading verbose prose); normally suggest rather than change |

## Audit workflow

Copy this list and track it for non-trivial audits:

```text
- [ ] Step 0: Establish scope, contracts, and mode
- [ ] Step 1: Gather diff and high-risk evidence
- [ ] Step 2: Run validity checks
- [ ] Step 3: Inspect implementation, architecture, skills, rules, and docs fit
- [ ] Step 4: Inspect test independence and coverage intent
- [ ] Step 5: Inspect documentation, skills, agent rules, and integration claims
- [ ] Step 6: Classify and report evidence-backed findings
- [ ] Step 7: Apply minimal cleanup (only when requested)
- [ ] Step 8: Verify corrections and quality gates
```

### Step 0: Establish scope, contracts, and mode

1. Determine whether this is a file, diff, PR, subsystem, or full-repository
   audit. Check for changed code, tests, docs, skills (`.agents/skills/*`), rules
   (`.agents/AGENTS.md`, `OPERATING.md`), configuration templates, and build files.
2. Read `.agents/AGENTS.md`, then the owner skills and neighboring code/docs that
   establish the intended pattern.
3. State the mode: **audit** by default; **cleanup** only with explicit user
   direction. An audit does not silently refactor code or docs.
4. Identify high-risk paths first: money/order execution, mode selection,
   credentials, CORS, persistence, concurrency, public routes, configuration,
   trading/security docs, and skills/rules affecting execution safety.

For a large or broad diff, increase review depth or request a walkthrough of
the architecture, skills, and verification strategy. Diff size is a review-budget
signal, not evidence of slop.

### Optional parallel evidence pass

For a full-repository or broad PR audit, use the `ai-slop-detector` preset from
`.agents/runtime-router/adapters/kilo/route_subagents.py` after Step 0 and the native model-selection
gate. It supplies disjoint production/build, tests/evaluation,
documentation/skills/rules, and UI/assets tracks. Workers return findings only;
the parent owns severity triage, cleanup decisions, edits, and serial quality
gates. If the host cannot expose a usable route, remain parent-owned rather than
using an unverified role. Do not fan out a small or tightly coupled audit.

### Step 1: Gather diff and high-risk evidence

For a PR or branch, inspect the diff before searching broadly:

- Production, test, build/dependency, configuration, route/API, skill, rule, and
  document changes together.
- Added imports, dependencies, generated code, feature flags, settings,
  configuration templates, skill files, or agent rules.
- Assertions weakened or removed, tolerances widened, mocks broadened, error/edge
  cases deleted, or skill/doc safety instructions relaxed.
- Related source contracts and previous behavior when a test, doc, or skill change is unclear.

Use `git diff` and history to establish the expected behavior of a changed
asset. Do not infer motive from author tools.

### Step 2: Run validity checks

Run the smallest relevant checks early. Compilation, static analysis, linter tools,
and schema parsers catch invented imports, methods, APIs, configuration, invalid
skills, and broken markdown links better than prose inspection does.

- Compile or test the affected module when feasible.
- Run `npx markdownlint-cli` on changed or audited markdown files.
- Verify skill YAML frontmatter (`name`, `description`) is valid and parseable.
- Run targeted tests for changed behavior.
- Inspect dependency declarations before accepting a new library/API claim.
- Check routes, `Settings`, shared contracts, configuration templates (`rebalancer-config-template.json`),
  and documentation claims against source code.
- Use the owning quality/documentation skills for full gates when the scope
  requires them.

Failure to run a costly check is not proof of a defect. Record the gap and
avoid overstating confidence.

### Step 3: Inspect implementation, architecture, skills, rules, and docs fit

Judge code against the standard of a strong staff engineer: defensive exactly at
trust boundaries (external APIs, user input, configuration, persistence,
money), lean and confident inside them. Judge skills, rules, and documentation against
the standard of precision and alignment: clear, accurate, non-conflicting, and
verifiable against source code.

#### Meaningful artifact-level signals across repository assets

Investigate these only when there is an observable cost or contract conflict:

| Artifact Type | Signal | Establish the finding by showing |
| :--- | :--- | :--- |
| **Source Code** | Delegate-only wrapper or duplicate helper | No policy, transformation, error boundary, or reuse value; a direct existing call is clearer |
| **Source Code** | Generic/factory/DSL/sealed abstraction | It hides a simple stable case, adds change points, or duplicates an existing local pattern |
| **Source Code** | Invented integration or API | The dependency, route, method, config key, schema field, or external API does not exist or is incompatible |
| **Source Code** | Excessive defensiveness / dead guards | The guarded state is contractually impossible; a fallback silently masks a hard failure; or duplicate validation adds no context |
| **Agent Skills** | Hallucinated tools, flags, or CLI commands | The skill instructs agents to use tools, parameters, flags, or shell commands that do not exist or fail |
| **Agent Skills** | Contradictions with code or rules | The skill instructs agents to violate source invariants or `.agents/AGENTS.md` rules (e.g. flipping `dryRun = false` in tests) |
| **Agent Skills** | Invalid frontmatter or dead links | YAML frontmatter is unparseable or missing required fields (`name`, `description`); or file/skill links are broken |
| **Agent Rules** | Rules drift / conflicting instructions | `.cursor/rules/*.mdc` or `OPERATING.md` conflicts with `.agents/AGENTS.md` or active source contracts |
| **Documentation** | Hallucinated parameters or routes | Docs list CLI flags, config keys, API endpoints, or environment variables not present in source |
| **Documentation** | Inaccurate domain or safety claims | Docs state incorrect rebalancing formulas, ATH math, dust limits, or misleading security assumptions |
| **Documentation** | Pure AI fluff prose | Paragraphs of formulaic filler ("In this comprehensive guide...") that obscure operational facts |
| **Build & Config** | Config template schema drift | `rebalancer-config-template.json` lacks required `Settings` fields or provides invalid default types |

#### Context-dependent constructs

None of these is slop by itself. Inspect the surrounding contract before
reporting it:

| Construct | Valid examples | Report only when |
| :--- | :--- | :--- |
| `@Suppress` | File-local, narrowly scoped, with an evidence-based reason | It conceals a demonstrated type/warning issue without explanation or safer design |
| `catch (Exception)` | Application error boundary with logging/mapping/recovery | It swallows a needed failure or fails to rethrow `CancellationException` in coroutine code |
| `runBlocking` | Application startup/shutdown or a controlled blocking bridge | It blocks a request, coroutine worker, or latency-sensitive path |
| Single-use skill helper / script | Complex multi-step automation fixture isolated in `scripts/` | It duplicates an existing build task or contains hallucinated CLI calls |
| Detailed doc rationale | Explaining non-obvious safety invariants, ATH math, or CORS rules | It restates self-explanatory code line-by-line without domain rationale |

#### Repository-specific anchors

Check the actual owner skill/source instead of inventing a fourth pattern:

| Area | Contract to verify |
| :--- | :--- |
| `:common` | Pure KMP only: no JVM/JS-only imports; shared routes/DTOs/constants belong here when they serve both targets |
| Money and orders | `BigDecimal`; precision rules; dry-run differs from simulation; sell-first, 99% cash budget, durable live-order journal, and `cl_ord_id` safety |
| Kraken I/O | `RateLimiter`, `Mutex`, retry/backoff, symbol mapping, and real API surface per `kraken-api-integration` |
| Repositories | Existing Exposed helpers: `safeTransactionIO` for writes and `readTransactionIO` for reads when applicable |
| Coroutines/Flows | No `GlobalScope`; preserve cancellation; choose replay/buffer semantics from the flow's consumer contract, not a universal `SharedFlow` recipe |
| Ktor views/routes | `DashboardRoutes`/`DashboardController` and `view/component/*` own the established boundary |
| Agent Skills | Under `.agents/skills/*/SKILL.md`; valid YAML frontmatter; accurate trigger phrases; verified file/tool links |
| Agent Rules | Primary rules in `.agents/AGENTS.md`; `.cursor/rules/*.mdc` synced with `OPERATING.md`; no raw FQNs, path defaults, or unvalidated settings |
| Documentation | `docs/*`, `README.md`, `CHANGELOG.md`, `SECURITY.md`, `USER_GUIDE.md` must accurately reflect source code behavior, CLI args, and config schema |
| Config & Build | `rebalancer-config-template.json` matches `:common` `Settings` schema; `build.gradle.kts` uses verified toolchain versions |

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
  for shared/concurrent behavior.

#### Test necessity

Each test should be the cheapest way to kill a distinct defect class:

- Name the defect class the test uniquely covers. Cosmetic input variation
  with the same structure and no new failure mode is duplication, not coverage.
- Distinguish impossible from unlikely. Inputs the type system or caller contract make impossible do not need unit tests.
- Reject coverage padding: assertions that only prove "does not throw", "is not null", or restate a stubbed mock's return value with no production logic in between.

### Step 5: Inspect documentation, skills, agent rules, and integration claims

Documentation, skill, and rule slop is factual, operational, or execution harm, not simply a terse style:

1. **Source Code Agreement**:
   - Commands, flags, imports, class/method names, routes, config keys, and examples in docs, skills, and rules must agree with current source/build files.
2. **Setup and Safety Alignment**:
   - Instructions in README, skills, and rules must match real execution modes (`dryRun` vs `simulation`), required Kraken permissions, local-trust assumptions, and defaults.
3. **Skill & Rule Integrity**:
   - Skills must have parseable YAML frontmatter (`name`, `description`).
   - Tool calls, flags, script paths, and Markdown links (`file:///...`) in skills must exist and work.
   - Rules in `.cursor/rules/*.mdc` must remain in sync with `OPERATING.md` and `.agents/AGENTS.md`.
4. **Fluff Removal & Rationale Retention**:
   - Remove generic AI filler prose ("In this section, we will discuss...") that adds no domain value.
   - Preserve non-obvious domain rationale, ATH drawdown formulas, CORS safety constraints, and live-order journal invariants.
5. **Handoff to Specialized Skills**:
   - Send broad documentation factual audits to `documentation-review`.
   - Send structural rules/skills consolidation to `rules-and-skills-audit`.
   - Send skill content enrichment to `skill-reviewer`.

### Step 6: Classify and report

Use neutral, concrete language: “the skill instructions reference a non-existent `--force` flag on `gradlew build`,” not “the author generated a fake skill.” Keep unproven concerns in a questions/deferred section.

```markdown
# Artifact Quality Audit — {scope}

## Verdict
{N} findings: {P0} P0, {P1} P1, {P2} P2, {P3} P3.

## Findings
### [P1] {specific outcome} — `path:Lx-Ly`
- **Category:** code / test / docs / skill / rule / config
- **Evidence:** {reproduction, source contract, diff, or local comparison}
- **Impact:** {what can break, misinform agents, or become harder to maintain}
- **Smallest safe correction:** {concrete patch or owner-skill direction}
- **Verification:** {targeted test, markdownlint, or check}

## Review prompts / deferred
- {uncertain item, missing evidence, and the question needed to resolve it}

## Checks run
- {command or manual verification}: pass / fail / not run and why
```

### Step 7: Apply minimal cleanup only when requested

Keep corrections narrow and behavior-preserving unless the finding is a proven
bug or broken claim.

| Validated issue | Minimal correction |
| :--- | :--- |
| Redundant wrapper/abstraction | Inline/remove it or reuse the established helper, then prove callers retain behavior |
| Architecture/pattern drift | Move/delegate through the owning boundary and add a focused regression test |
| Invented or incorrect API / config claim | Replace with the verified API/dependency/config in code, docs, or skills; remove unsupported claims |
| Invalid skill frontmatter / broken link | Fix YAML frontmatter or correct/remove broken file/tool links |
| Conflicting agent rule instruction | Sync rule with `.agents/AGENTS.md` and active source code invariants |
| AI fluff prose in documentation | Prune filler words while retaining factual setup and safety rationale |
| Out-of-sync config template | Align `rebalancer-config-template.json` fields and types with `:common` `Settings` DTO |
| Unsafe error/cancellation behavior | Preserve cancellation, map/propagate failures, and test the affected path |
| Mirror/weak test | Replace with a contract assertion that fails a plausible wrong variant |
| Duplicate / impossible-case test | Delete after proving its defect class is covered elsewhere or input is contractually impossible |

### Step 8: Verify corrections and quality gates

After cleanup:

- Run focused compilation/tests covering corrected code/contracts.
- Run `npx markdownlint-cli` when markdown docs, skills, or rules are modified.
- Verify skill YAML frontmatter is valid.
- Run `./gradlew build` when the scope warrants full JVM/JS/coverage gates.
- For UI, mode, trading, persistence, security, or live-order changes, run domain/manual verification before declaring success.

## Anti-patterns

- Calling stylistic cues, contributor behavior, or PR size proof of AI use or slop.
- Labeling a familiar construct as bad without inspecting its boundary and contract.
- Inventing CLI flags, environment variables, or tool parameters in skills or documentation.
- Leaving broken Markdown links or unparseable YAML frontmatter in skill files.
- Deleting tests or doc rationale merely to reduce LOC or word count.
- Refactoring uncertain code or docs during an audit-only request.
- Skipping owner skills or quality gates after a validated cleanup.

## Trigger phrases

- "Audit this PR/codebase for AI slop"
- "Audit our skills and documentation for AI-ish claims or slop"
- "De-slop this file/skill/doc while preserving behavior and rationale"
- "Find hallucinated APIs, flags, config keys, or broken links in docs/skills"
- "Cut over-defensive padding, duplicate tests, and AI fluff prose"
- "Review this change for needless complexity, documentation drift, and rule conflicts"
