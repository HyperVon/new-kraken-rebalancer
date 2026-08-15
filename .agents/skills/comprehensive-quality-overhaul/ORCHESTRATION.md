# Comprehensive Quality Overhaul — orchestration reference

Load this file after SKILL.md when executing the overhaul. The core skill
defines the trigger, safety contract, track ownership, and output; this file
contains mechanics needed only during a live run.

## Default ARR/Kilo delivery mode

The registered `comprehensive-quality-overhaul` preset contains five **read-only**
tracks. In the default ARR/Kilo path, ARR gives each worker an isolated
temporary snapshot; it does not run workers in `.worktrees/wt-*`. The parent
owns integration, app boot, final gates, and any durable findings summary.

| Track ID | Track | Skills |
| :--- | :--- | :--- |
| wt-code | Code quality | code-review, autonomous-code-optimizer Pass 1+3 survey, kotlin-refactoring-and-cleanup, reduce-code-size, complex-code-comments, todo-resolution |
| wt-docs | Documentation | documentation-review, changelog-and-docs-sync, user-guide |
| wt-skills | Rules and skills | rules-and-skills-audit, skill-reviewer, ai-slop-detector on skills/rules/docs |
| wt-tests | Tests, QA, security, dependencies | continuous-quality, write-kotest, dependency-upgrade, ai-slop-detector on tests/build/security |
| wt-arch | Architecture and product | architecture-review, product-opportunity-review (recommendations only) |

Read-only workers return their compact audit report through the supervised
launch result. They must not write source files, worktree files, heartbeats,
findings, or coordination artifacts. The parent records any summary only after
the terminal results arrive. Workers must not run Gradle, boot the app, inspect
secrets, create issues, commit, push, or open PRs. The parent performs app-boot
skills serially after parallel work is integrated:
ui-visual-review, ui-visual-implement, ui-manual-qa,
post-deploy-ui-smoke, and docs-screenshot-refresh.

### Waiting and terminal results

The launcher can return no intermediate worker text while Kilo is running.
Silence, a missing heartbeat, or the absence of a coordination file is **not** a
failure signal for this mode. Do not start duplicate tracks, search unrelated
directories, change routing policy, or blacklist a model while the approved
wave is still active. Wait for the launcher’s terminal structured result,
allowing the configured 900-second cap for each track plus startup/failover
time. The parent then records each final report, deduplicates its findings, and
decides whether a new, separately approved wave is needed.

### Write-capable extension (not this registered preset)

A future harness/workflow may explicitly authorize writable isolated worktrees
and an external parent coordination directory. Only that workflow may create
`.worktrees/wt-*`, heartbeat files, or findings artifacts; its manifest and
launch contract must say so. Do not borrow that protocol for the read-only
ARR/Kilo preset merely to obtain progress updates.

## Launcher and routing mechanics

In Kilo CLI sessions use the receipt-managed runtime and the registered
workflow. The launcher owns the route plan and `--free-only` rejects paid and
unknown-cost candidates without requiring a copied policy/config override.
Named workflows default to distinct model-family routes; this prevents a
five-track review from silently becoming five copies of the same model. Use
`--allow-route-reuse` only when reuse is intentional and documented. Run plan
mode first and inspect every track's route, effort, billing, fallback, and
evidence. A missing or stale catalog is `INCOMPLETE`, not permission to use
Kilo's native role-only Task tool.

For this registered preset, do not reconstruct the adapter by grepping its
Python files, invoke `kilo auto`, or create a hand-written per-track command.
The one supported path is the receipt-managed command below; its terminal JSON
and result files are the contract.

Use a script because Kilo's shell wrapper can mangle long inline task strings:

    #!/usr/bin/env bash
    set -euo pipefail
    cd <parent-repo-root>
    exec ./.agents/.agent-runtime-router/run.py --python \
      .agents/runtime-router/adapters/kilo/route_subagents.py \
      --workflow comprehensive-quality-overhaul \
      --free-only \
      --distinct-routes \
      --task "$TASK" \
      --approve

The command above is the launch form after the plan has been reviewed. Omit
`--approve` for plan mode. The registered preset is read-only; the parent owns
integration and final verification. Verify parent `git status --porcelain`
after the wave for stray edits. On a successful launch, the JSON result includes
`result_directory` and one `report_path` per track under
`.agents/runtime-router/harnesses/kilo/workflows/`; read those redacted reports
for findings. Do not run a second native `kilo run` fan-out to recover output.

### Evidence and approval sequence

Do not add `--refresh` to the ordinary plan command. Plan mode consumes the
existing target-owned catalog only. If the plan reports `INCOMPLETE`,
`NEEDS_REFRESH`, a missing cache, or an unusable cache, stop and show that
evidence; do not remove the launcher approval gate, edit the adapter, or fall
back to Kilo's native role-only Task tool.

Refreshing discovery is a separate live metadata approval. After the user
explicitly approves that operation, run the bounded target adapter discovery
with an explicit cache path, for example:

    ./.agents/.agent-runtime-router/run.py harness discover \
      --target . \
      --config .agents/runtime-router/adapters/kilo/discovery.json \
      --cache-output .agents/runtime-router/harnesses/kilo/catalog-cache.json \
      --cache-ttl 7200 \
      --pretty

For Kilo, this listing can legitimately take several minutes during a cold
start or while a provider backend responds slowly. Do not wrap the command in
a shorter shell timeout, use `head`/a pipeline that interrupts it, or run
15-second per-provider spot checks and classify the provider as unavailable.
The generated discovery contract has a finite multi-minute deadline and the
Kilo adapter applies its own per-provider bound plus one overall deadline;
let those boundaries produce the structured timeout result.

**Kilo Code only:** when its shell tool runs that command in the background,
start exactly one valid single-line command and use Kilo's job/status result to
observe it. A 40-second sleep or an active background handle means *still
running*, not complete, failed, or safe to rerun. Do not begin a second
discovery, inspect a cache while the first job is active, or replace the
bounded discovery with `kilo auto` or the native role-only Task tool. Wait for
the terminal structured result (or the generated 900-second cap) before
deciding whether the cache is usable.

If discovery is failed, unknown, or unusable, remove the generated cache and
report the redacted diagnostic; it is not a route. If it succeeds, run the
route plan again **without** `--refresh`. If it is still `NO_ROUTE` because
free TPS or tool-readiness evidence is missing, request a third, evidence-only
approval. That command may send bounded free Kilo probes but never launches a
workflow worker:

    ./.agents/.agent-runtime-router/run.py --python \
      .agents/runtime-router/adapters/kilo/route_subagents.py \
      --workflow comprehensive-quality-overhaul --free-only --distinct-routes \
      --prepare-evidence --approve --task "$TASK"

`--prepare-evidence` rejects `--refresh`, so model discovery stays a separate
approval. It returns `EVIDENCE_READY` only after the required target-owned
TPS/readiness evidence is cached. Then run the ordinary plan again **without**
`--approve`, inspect every track's candidate, effort, billing, quota,
TPS/readiness evidence, and rejection reasons, and only then ask for the
fourth, worker-launch approval. Discovery approval and evidence preparation do
not authorize workers; worker approval does not authorize an unplanned refresh.

Kilo labels need careful interpretation. `kilo-auto/small` may be an internal
Kilo title/helper session and is not evidence that ARR selected a worker. Use
the exact candidate IDs in the ARR cache (often `kilo/kilo-auto/<tier>`) and
report their provider, billing, quota, freshness, capability, and rejection
evidence. A single bounded `NO_ROUTE` diagnostic is enough; do not loop on the
label, silently waive `--free-only`, or enable unknown quota/cost.

### Do not bypass the routed result

The launcher may be quiet while discovery, readiness, or workers are running;
that is expected. Wait for its terminal JSON result and allow the configured
multi-minute bounds. The result is the source of truth for route/effort and
the `report_path` files are the source of truth for worker findings. Never
replace a slow ARR operation with `kilo auto`, a native role-only task, or a
hand-written batch of `kilo run` commands: those bypass ARR's per-track routing,
effort binding, free-model gates, and report provenance.

## Step 0 — prepare the read-only wave

Start from the intended clean target and keep its working tree unchanged. Do
not create `.worktrees/`, a coordination directory, or per-track branches for
this registered read-only preset. Record the target revision and the exact
approved route plan instead. If a prior *write-capable* workflow left worktrees
behind, preserve any work that matters and clean them only under that workflow’s
separate lifecycle rules.

## Step 1 — fan out

Launch all five tracks together; never start them sequentially. Each track uses
its assigned skills and returns at most 12 report lines and 5 findings per
skill. Its prompt must include the audit-only effect, snapshot-only scope,
free-route rule, final-report contract, no app boot, and no commit/push/PR.

Track A reports [P0-P3] title with path/line, category, evidence, impact, and
smallest safe correction. Track B reports [WRONG|STALE|MISSING|ORPHAN] with
path/section, evidence, and fix. Track C reports finding type, paths, evidence,
and action. Track D reports defect/dependency, current versus latest, breaking
changes, security alert, and risk. Track E recommends only and all items are
L-class until separately approved.

App-boot skills go to the parent request channel. Do not run the full
autonomous-code-optimizer four-pass convergence loop here; survey Pass 1 and
Pass 3 only.

### Retry policy

Do not infer a stall from missing live output: ARR/Kilo read-only fan-out may
buffer every worker’s report until the wave reaches a terminal state. Retry at
most once, only after a terminal track result reports a real failure or timeout.
The retry starts from the same unchanged target and retains the reviewed
workflow, `--free-only` constraint, route plan, and read-only contract. If it
also fails, report a partial run; do not turn it into a write-capable worktree
operation or native same-model delegation.

## Step 2 — collect and triage

The parent reads the compact terminal reports returned by the launcher, then
deduplicates their findings into one table:

| Size | Criteria | Action |
| :--- | :--- | :--- |
| S | Local formatting, dead import, typo, broken link, one-test correction | Apply after triage approval and verify |
| M | Localized refactor, checklist sync, non-breaking API tidy | Apply after approval if gates remain green |
| L | Multi-package redesign, trading/order path, live safety, credential handling, major dependency, product/architecture change | Stop and ask; present proposal |

Any change touching live order behavior, dryRun/simulation, or credentials is L
regardless of diff size. Do not resolve overlapping findings inside the
parallel wave; consolidate in the parent.

## Step 3 — PR triage

Group findings into reviewable candidate PRs: one finding or cohesive theme per
PR; hotfix/security/fail-closed items are high priority; identify parallel-safe
groups; mark each Ready to merge, Needs adversarial review, Needs approval,
Blocked, or Drop candidate. The report must show title, domain, files, size,
status, recommendation, dependencies, counts by status, and merge order.

Do not open a PR until the user approves the candidate plan. High-risk PRs
touching trading math, Kraken I/O, CORS, live-order journal, or credentials
require adversarial-pr-review before merge.

## Step 4 — commit, push, and PRs

For each approved candidate, create a dedicated branch from current main:
`improve/overhaul-YYYYMMDD-<slug>`. Do not reuse discovery worktree branches.
Verify branch base, commit only that candidate, push, and use open-pr with the
required adversarial review. Parallel-ready candidates may be opened
concurrently, but obey dependency and user-review stops.

## Step 5 — report

Report target revision, tracks, candidate PR count, ready count, adversarial
review count, approval-required count, blocked/drop candidates, recommended
merge sequence, and the next user decision. State partial runs and failed
retries explicitly.

## Step 6 — teardown

The default read-only ARR/Kilo wave creates no worktrees or coordination state.
Do not remove unrelated worktrees. A future write-capable extension owns and
documents its separate cleanup lifecycle; do not delete branches while PRs
refer to them.

## Safety anti-patterns

Never run concurrent Gradle builds in one clone; claim no convergence when
coverage exclusions were widened instead of tests added; use shared stash or
copy credentials/databases/logs; boot servers in parallel worktrees; silently
change live-trading or production config; use paid worker routes; omit the
blacklist from a replacement router config; replace the launcher's bounded
deadline with a short ad-hoc timeout; open unchecked PRs; or merge without user
review.
