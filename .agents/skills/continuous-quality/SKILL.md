---
name: continuous-quality
description: >-
  Orchestrate a continuous-quality (QA) cycle: run full tests and coverage
  gates, review code for bugs, invent uncovered edge cases and use cases, add
  Kotest/evaluation/UI regressions, fix S/M defects with tests first, pause for
  approval on L/trading-path fixes, track work in .agents/quality-backlog.md
  (GitHub issues for L/deferred), then commit and open a PR. Use when the user
  asks for continuous quality, continuous QA, QA loop, test hardening, coverage
  hunt, edge-case testing, or “just run QA.”
---

# Continuous quality (QA loop)

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **continuous-quality** (this) | Recurring QA-hardening cycle: full gates → bug hunt → new coverage → PR, with a persistent backlog |
| [continuous-improvement](../continuous-improvement/SKILL.md) | Product / docs / deps enhancement cycle (polish, not correctness hunting) |
| [comprehensive-quality-overhaul](../comprehensive-quality-overhaul/SKILL.md) | One-shot full-repo audit sweep producing triaged candidate PRs for approval |
| [write-kotest](../write-kotest/SKILL.md) | Authoring individual Kotest specs (child skill this cycle sequences) |

Orchestrator skill. It **does not replace** individual skills — it sequences
them. Prefer this when the user wants an end-to-end test → bug-hunt → cover →
PR loop with light supervision; run child skills alone for a narrow pass
(e.g. only `ui-manual-qa` or only new Kotest).

Sibling: [continuous-improvement](../continuous-improvement/SKILL.md) enhances
product/docs/deps. **This skill hardens correctness** — tests, regressions,
evaluation scenarios, and fixes for defects found while doing so. Hand product
polish / redesign ideas to the improvement backlog instead of shipping them
here.

Related always-on norms: [OPERATING.md](../../OPERATING.md),
[parallel-multi-agent](../parallel-multi-agent/SKILL.md).

**Persistent backlog:** [quality-backlog.md](../../quality-backlog.md) is the
source of truth for open / done / deferred QA items across cycles.

## Bounded discovery and implementation delegation

When a cycle fans out, the parent chooses an adaptive number of tracks from the
actual QA gaps and file ownership. Do not send every child agent the whole
repository or run a fixed two-agent review. Use one track per independent area
(for example bug review, algorithm edges, coverage, evaluation, history, flows,
Kraken, or UI), normally 2–6 and at most 8, with one owner for shared tests and
production files.

Each Task prompt names the absolute repo/branch, already-done context, exact
allowed paths, acceptance criteria, iteration cap, and stop condition. Workers
return compact findings or test summaries (at most 12 lines and 5 findings), do
not edit outside their assigned files, run overlapping Gradle builds, start
servers, inspect secrets/runtime data, or load unrelated skills. Keep delegated
requests well below the roughly 256K practical context boundary; target below
128K and split before 180K. If a worker approaches its limit, it returns a
partial report and the parent starts a narrower follow-up. Manual compaction is
not a continuation strategy. The parent owns integration, backlog updates,
serial final gates, and PR verification.

When running under Google Antigravity (AGY), launch discovery subagents natively using built-in `invoke_subagent` tool calls; do NOT execute a Kilo-specific workflow launcher. Discovery workers are read-only; test fixes, Gradle, browser tests, coverage, and final gates remain parent-owned and serial.

For optional Kilo CLI sessions, launch the bounded discovery fan-out through the host's native Task surface with a selected route.

---

## Modes

| Mode | Trigger phrases (examples) | Behavior |
| :--- | :--- | :--- |
| **Cycle** (default) | “continuous quality”, “QA cycle”, “harden tests once”, “just run QA” | One full loop → PR → stop |
| **Loop** | “keep QA’ing”, “continuous quality loop”, “run N QA cycles” | Repeat Cycle until stop condition |
| **Discover-only** | “what tests are missing?”, “QA backlog”, “coverage gaps” | Produce backlog + sizes; **no** code edits (still update backlog file + issues) |

Default to **Cycle** unless the user asks for Loop or Discover-only.

### Loop stop conditions (any)

1. User says stop / pause / enough
2. A full Cycle finds **no** actionable items (or only deferred Large items)
3. User-requested cycle count reached
4. A Large item is waiting on approval and nothing else is shippable

---

## Size classes (mandatory classification)

Before implementing, classify every candidate:

| Size | Examples | Action |
| :--- | :--- | :--- |
| **S** — Small | Single Kotest case for an uncovered branch; assert `shouldBeEqualComparingTo`; fix flaky sleep → `advanceUntilIdle`; tiny obvious null/empty guard + regression | Auto-apply in-cycle |
| **M** — Medium | New evaluation scenario (or multi-subcase); module-level FakeKraken suite; UI `REGRESSION-*` / `STYLE-*` failure with test or harness fix; coverage lift for one package without changing public API | Auto-apply if gates stay green; mention in cycle brief |
| **L** — Large / high-impact | Changing trading math / order path / rate-limit / credential handling to “fix” a bug; rewriting evaluation harness; new product surface “so we can test it”; weakening JaCoCo/Karma thresholds; broad UI redesign driven by QA notes | **Stop and ask** — do not start until the user approves a short proposal |

**Impact override:** anything that can change live order behavior, `dryRun` /
`simulation` semantics, or credentials handling is **L** even if the fix looks
small. See [dry-run-and-simulation](../dry-run-and-simulation/SKILL.md).

**Test-only override:** pure tests / FakeKraken scenarios that **do not** change
production code stay **S/M** even when they exercise trading math — unless they
require changing assertions to match wrong production behavior (then file an **L**
product fix + keep a failing or `@Ignored` test linked to the issue).

When in doubt between M and L → treat as **L** and ask.

---

## Backlog tracking (mandatory)

Track work in **two places** with different roles:

| Store | What goes there | Why |
| :--- | :--- | :--- |
| [`.agents/quality-backlog.md`](../../quality-backlog.md) | **All** findings (S/M/L) with status `open` / `in_progress` / `done` / `deferred` / `dropped` | Readable remaining-vs-done history in-repo; survives chat compaction |
| GitHub issues | Every **L** item + any item **deferred** past the current cycle | Discussion, approval, cross-session visibility; link from the backlog `Issue` column |

### File rules

1. **Read** the backlog at Step 0 (avoid rediscovering `done` / already-`deferred`
   rows). Also skim [improvement-backlog.md](../../improvement-backlog.md) so QA
   does not re-open polish items already tracked there.
2. After Step 1 discovery, **upsert** new rows (`Status=open`, IDs like `CQ-3-1`
   = cycle 3 item 1). Update existing rows instead of duplicating summaries.
3. When implementing: set `in_progress` + branch name in Notes.
4. When shipping: move rows to the **Done** section with PR number; set
   `Status=done`.
5. Keep long-lived deferred **L** rows in the open table until the user drops
   them (`dropped`) or they ship.
6. Commit backlog updates on the cycle branch with the rest of the cycle (or in
   Discover-only as the only change if no code shipped).
7. Product enhancements found during QA → add to
   [improvement-backlog.md](../../improvement-backlog.md) (or hand off), **not**
   as silent scope creep in the quality PR.

### GitHub issue rules

1. Ensure tracking labels exist (`continuous-quality`, `size/S`, `size/M`, `size/L`):

   ```bash
   for l in "continuous-quality:1D76DB" "size/S:C2E0C6" "size/M:FEF2C0" "size/L:F9D0C4"; do
     IFS=: read -r name color <<< "$l"
     gh label create "$name" --color "$color" 2>/dev/null || true
   done
   ```

2. Before creating, dedupe against all related tracking surfaces:

   ```bash
   gh issue list --state open --label continuous-quality --limit 50
   gh issue list --state open --search "keyword from finding"
   # also check continuous-improvement issues for the same bug or finding
   gh issue list --state open --label continuous-improvement --limit 50
   ```

3. Create an issue for each **L** or cross-cycle **deferred** item without an issue link:

   ```bash
   gh issue create \
     --title "[CQ-3-2] Short summary" \
     --label "continuous-quality,size/L" \
     --body "$(cat <<'EOF'
   ## Summary
   …

   ## Size / risk
   L — …

   ## Evidence
   Failing idea / path / uncovered branch …

   ## Proposed approach
   Test first (…); then fix (…); or defer product change

   ## Cycle
   Discovered in quality cycle 3 on branch `quality/…`
   EOF
   )"
   ```

4. Record the issue number in the backlog `Issue` column (`#NN`).
5. **Do not** open issues for S/M items shipping in the same cycle PR — the backlog file suffices.
6. When shipping, distinguish resolution status:
   - Fully resolved: reference with `Closes #NN` and close via `gh issue close N`.
   - Partially resolved: comment what shipped, update the remaining scope, and keep the issue open.

### Discover-only mode

Still update `quality-backlog.md` and create/link GitHub issues for L /
deferred items. Do not implement tests/fixes or open a quality PR unless asked.

---

## One Cycle — workflow

```text
- [ ] Step 0: Branch & safety (+ read quality-backlog.md)
- [ ] Step 1: Baseline gates + discover gaps (classify S/M/L; upsert; issues)
- [ ] Step 2: Gate Large items (user feedback)
- [ ] Step 3: Implement — tests first, then S/M fixes (+ approved L)
- [ ] Step 4: Verify (full gates + targeted UI QA if UI touched)
- [ ] Step 5: Docs / CHANGELOG / EVALUATION / backlog Done rows
- [ ] Step 6: Commit, push, open PR (Closes #… where applicable)
- [ ] Step 7: Cycle report → stop or Loop
```

### Step 0 — Branch & safety

1. Start from an up-to-date `main` (or user-named base).
2. Create a dedicated branch, e.g. `quality/cycle-YYYYMMDD-HHMM` or
   `quality/<theme>`.
3. Read [quality-backlog.md](../../quality-backlog.md); skip rediscovery of
   `done` / already-tracked `deferred` unless verifying they still apply.
4. Never use the user’s production `rebalancer-config.json` / DB for UI boots —
   isolated simulation only.
5. Do **not** flip live trading flags.

### Step 1 — Baseline gates + discover backlog

**Always start by running gates** (failures become high-priority backlog items):

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

If that is too heavy for a first probe, at minimum:

```bash
./gradlew build jacocoTestCoverageVerification
./gradlew :frontend-js:jsBrowserTest
```

After the native model-selection gate, fan out discovery with
[parallel-multi-agent](../parallel-multi-agent/SKILL.md) when tracks are
disjoint. Suggested discovery tracks (pick what fits timebox):

| Track | Child skill / focus |
| :--- | :--- |
| Bug review | [code-review](../code-review/SKILL.md) — CRITICAL/MAJOR only for this skill; size each finding |
| Algorithm edges | [portfolio-rebalancing-math](../portfolio-rebalancing-math/SKILL.md) + `docs/ALGORITHM.md` vs existing tests / `EvaluationScenariosTest` |
| Modes / safety | [dry-run-and-simulation](../dry-run-and-simulation/SKILL.md) — confusion, dry-run stats, sim vs live paths |
| Coverage gaps | [gradle-quality-gates](../gradle-quality-gates/SKILL.md) — JaCoCo/Karma reports; packages near thresholds; missed branches |
| Evaluation gaps | `docs/EVALUATION.md` + [write-kotest](../write-kotest/SKILL.md) — scenarios ALGORITHM mentions that have no case |
| History / sync | [trade-history-sync](../trade-history-sync/SKILL.md) — dedupe windows, pair aliases, dry-run vs API |
| Kraken / limits | [kraken-api-integration](../kraken-api-integration/SKILL.md) — lockout backoff, mutex, public vs private |
| Flows / SSE | [coroutines-flows-sse](../coroutines-flows-sse/SKILL.md) — restart, multi-subscriber, cancellation |
| UI interactions | [ui-manual-qa](../ui-manual-qa/SKILL.md) — full checklist when time allows; failures → backlog (`REGRESSION-*` / `STYLE-*`) |
| Known debt | `@Ignored` / `@Disabled` / `TODO(test)` / flaky retries; open `continuous-quality` issues |

**Invent cases deliberately.** For each hot module, ask:

1. What happens at **boundaries** (0, dust threshold, exactly `deviationTriggerPercent`, 100% drawdown, empty book)?
2. What happens on **failure** (partial sell, USD poll timeout, bad signature, corrupt JSON)?
3. What happens on **mode mix-ups** (`simulation` + `dryRun`, live + dryRun)?
4. What **use case** would a careful operator hit that no scenario names?
5. **Interrupted-state & retry probes:** Probe multi-step transitions at intermediate failure points (e.g. durable live-order intent recorded but AddOrder network timeout; retry after partial fill; repeated delivery; rollback/compensation).

**Mock contract fidelity & timing rules:**

- Prefer testing against real public seams and in-memory fakes (`FakeKrakenService`) over deep mock hierarchies.
- Do not assert only that a mock method was called; assert the observable state, returned value, or protocol side effect.
- Never resolve a flaky test by adding arbitrary `Thread.sleep()` or bumping timeouts; use `advanceUntilIdle()` for coroutines or condition-based polling with bounded timeouts.

Prefer **one sharp assertion** over snapshot soup. Prefer `FakeKrakenService` over
brittle MockK for exchange behavior ([write-kotest](../write-kotest/SKILL.md)).

Produce a **Cycle backlog** table (chat + file):

| ID | Area | Size | Summary | Kind | Child skill | Ship this cycle? |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| CQ-3-1 | … | S/M/L | … | gap / bug / flake / ui | … | yes / defer / ask |

`Kind` values: `gap` (missing coverage), `bug` (defect + regression), `flake`,
`ui` (manual QA), `harness` (test infra).

Then:

1. Upsert rows into [quality-backlog.md](../../quality-backlog.md).
2. Create/link GitHub issues for **L** and cross-cycle **deferred** items.
3. Show the user the table **with issue links** for anything needing approval.

Timebox discovery (~15–30 min of agent work unless user expands). Prefer a
**shippable** slice (green gates + meaningful new coverage) over boiling the
ocean.

### Step 2 — Gate Large items (user feedback)

If any **L** items exist (or M items that risk trading/safety):

1. Present a short proposal: problem, failing-test plan, production fix (if any),
   files touched, risk, rollback, and GitHub issue URL/number.
2. Ask which IDs to **approve / defer / drop**.
3. **Wait** for the user before implementing those IDs.
4. Continue with S/M **test-only** work in parallel only if file ownership does
   not overlap the pending L production fix.
5. Reflect the decision in the backlog (`deferred` / `dropped` / `in_progress`)
   and comment on the GitHub issue.

If the user said “just run QA” / “continuous quality” **and** there are **no** L
items → proceed without pausing.

If the only items are L → do **not** invent busywork tests; stop at the proposal
(optionally still land Discover-only backlog updates).

### Step 3 — Implement

Follow the **child skill** for each approved item. Default order:

1. **Red** — add failing Kotest / evaluation / JS test that names the gap or bug
   ([write-kotest](../write-kotest/SKILL.md))
2. **Green** — minimal production fix for S/M bugs (or approved L)
3. **Refactor** only if needed for clarity inside the touched tests
4. UI findings → prefer regression coverage in JS/SSR tests; use
   [ui-manual-qa](../ui-manual-qa/SKILL.md) to re-verify interactions
5. Update `docs/EVALUATION.md` when adding/changing evaluation scenarios

Use the bounded delegation rules above when ownership is disjoint (e.g. history
dedupe tests vs frontend zoom tests). Keep a single owner for
`EvaluationScenariosTest.kt` and for any shared production file under fix.

**Do not** weaken coverage thresholds or delete tests to go green. **Do not**
“fix” by matching buggy behavior in assertions.

### Step 4 — Verify

Always:

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

**Force re-execution for the final pass.** Gradle's build cache happily replays a
subagent's earlier run, so an all-`UP-TO-DATE` / `FROM-CACHE` "PASSED" in a few
seconds proves nothing about tests you just added or re-enabled:

```bash
./gradlew test jacocoTestCoverageVerification spotlessCheck :frontend-js:jsBrowserTest --rerun-tasks
```

Confirm the count actually moved (JUnit XML under `build/test-results/test/`) —
`tests`, `failures`, and `skipped` should all match what you expect.

Also when UI changed or UI findings were in the backlog:

- Prefer [ui-manual-qa](../ui-manual-qa/SKILL.md) scoped or full (include
  `STYLE-*`, `REGRESSION-*`, `HIST-ZOOM-*` if History touched)
- Or at least [post-deploy-ui-smoke](../post-deploy-ui-smoke/SKILL.md) against
  local sim after hard-refresh / `?v=`

Trading / algorithm touches → re-run evaluation awareness:

```bash
./gradlew test --tests "com.gemini.krakenbot.EvaluationScenariosTest"
```

Fix failures before commit. Do not open a red PR.

Complete **all** PR Test plan / Verification items **before** `gh pr create`
(including UI/sim spot-checks). Never defer checks to after merge — see
[open-pr](../open-pr/SKILL.md).

### Step 5 — Docs

- `CHANGELOG.md` dated SemVer heading (never `[Unreleased]`) for user-visible
  fixes (tests-only cycles: note under Changed/Fixed only if behavior changed;
  pure coverage can be a short Added “tests: …” line) — see
  [changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md)
- Update [quality-backlog.md](../../quality-backlog.md) Done / deferred rows
- `docs/EVALUATION.md` when scenarios change
- Skills / ALGORITHM only if a fix revealed wrong docs
  ([changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md))

### Step 6 — Ship

1. [commit-and-push](../commit-and-push/SKILL.md) on the cycle branch
2. [open-pr](../open-pr/SKILL.md) against `main` — body lists backlog IDs and
   `Closes #NN` for finished issues
3. Return the PR URL

Conventional title examples: `test: …`, `fix: …`, `test(eval): …`.
PR body: gaps closed, bugs fixed (with test names), L deferred (issue links),
gate results, test plan.

### Step 7 — Cycle report

```markdown
# Continuous quality — cycle report
- Branch / PR: …
- Backlog file: .agents/quality-backlog.md (updated)
- Baseline gates (Step 1): …
- Shipped: CQ-… (S/M) — Done in backlog; issues closed if any
- Deferred / awaiting approval: CQ-… (L) — issue #…
- New tests / scenarios: …
- Gates (Step 4): pre_commit_check …
- Handed to improvement backlog: … (if any)
- Next: merge / start Loop cycle N+1 / stop
```

---

## What “just run QA” means here

| Auto without asking | Always ask first |
| :--- | :--- |
| S/M new tests, evaluation cases, flake fixes | L production fixes on trading / credentials / modes |
| S/M bugfixes proven by a new regression test | Weakening coverage gates or deleting coverage |
| Backlog file upserts; GitHub issues for L/deferred | Merging the PR; deploying; live config edits |
| Opening a `quality/*` PR after green gates | Continuing after an L proposal with no reply |
| Another Loop cycle after a clean ship (if Loop mode) | Closing backlog rows as `dropped` without user say-so |
| Handing polish ideas to improvement-backlog | Shipping redesigns / features under the QA banner |

---

## Anti-patterns

- Shipping product polish via this skill (use
  [continuous-improvement](../continuous-improvement/SKILL.md))
- Asserting buggy production behavior so tests stay green
- Giant unfocused “add coverage” PRs with no named risk
- Skipping `pre_commit_check.sh` because “it’s just tests”
- Silent live-trading or production-config use
- Parallel edits to the same hot file (`EvaluationScenariosTest.kt`,
  `PortfolioCalculations`, one CSS module)
- Filing a GitHub issue for every trivial S test that ships same-day (noise)
- Leaving discoveries only in chat — always persist to `quality-backlog.md`
- Rediscovering and re-adding items already marked `done` or `deferred`
- MockK spaghetti when `FakeKrakenService` would do
- UI QA that only checks mobile viewport (laptop widths matter —
  [OPERATING.md](../../OPERATING.md))

---

## Checklist

- [ ] Mode chosen (Cycle / Loop / Discover-only)
- [ ] Quality branch from fresh base; backlog file read
- [ ] Baseline gates run; failures triaged into backlog
- [ ] Backlog classified S/M/L + Kind; file upserted; issues for L/deferred
- [ ] L gated with user when present
- [ ] Tests first for bugs; child skills followed
- [ ] `pre_commit_check.sh` green; UI QA if UI touched; eval if algorithm touched
- [ ] CHANGELOG / EVALUATION / backlog Done rows updated as needed
- [ ] Commit + PR (with `Closes #…`); cycle report delivered
- [ ] Individual skills still usable alone (this skill only orchestrates)
