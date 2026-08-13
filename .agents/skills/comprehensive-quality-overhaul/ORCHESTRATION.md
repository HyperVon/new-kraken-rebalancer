# Comprehensive Quality Overhaul — orchestration reference

Load this file after SKILL.md when executing the overhaul. The core skill
defines the trigger, safety contract, track ownership, and output; this file
contains mechanics needed only during a live run.

## Worktree topology

Create five isolated worktrees so each track has its own build/, lock, and
disposable runtime state. The parent owns integration, app boot, and final
gates.

| Worktree | Track | Skills |
| :--- | :--- | :--- |
| wt-code | Code quality | code-review, autonomous-code-optimizer Pass 1+3 survey, kotlin-refactoring-and-cleanup, reduce-code-size, complex-code-comments, todo-resolution |
| wt-docs | Documentation | documentation-review, changelog-and-docs-sync, user-guide |
| wt-skills | Rules and skills | rules-and-skills-audit, skill-reviewer, ai-slop-detector on skills/rules/docs |
| wt-tests | Tests, QA, security, dependencies | continuous-quality, write-kotest, dependency-upgrade, ai-slop-detector on tests/build/security |
| wt-arch | Architecture and product | architecture-review, product-opportunity-review (recommendations only) |

Use the parent coordination surface:

    .worktrees/.coordination/
      agent-status/  findings/  topics/  questions/  requests/  results/

Each worker writes only to its own worktree and the parent-absolute coordination
directory. It must not run Gradle, boot the app, inspect secrets, create issues,
commit, push, or open PRs. The parent performs app-boot skills serially after
parallel work is integrated:
ui-visual-review, ui-visual-implement, ui-manual-qa,
post-deploy-ui-smoke, and docs-screenshot-refresh.

## Coordination protocol

Every worker writes a heartbeat at least every 60 seconds to
`agent-status/<track>.json`. The payload includes track, status
(running/blocked/done/error), current_skill, progress, findings_count,
blockers, warnings, and questions.

Append findings as soon as evidence exists; do not batch them at the end.
Check `topics/<track>.txt` at each heartbeat and acknowledge parent guidance.
The parent polls status, warnings, blockers, questions, and cross-track topics.

During active discovery, the parent emits a compact status update at least
every 30 seconds, one line per track, under 120 characters. Surface blocked or
errored tracks immediately; do not paste raw file contents or findings into the
heartbeat.

Topics are advisory and never a reason for a worker to block. Share finding
titles, severities, paths, evidence anchors, questions, warnings, and progress.
Do not share full files/diffs, secrets, credentials, live account data, or
unrelated repository context. Keep coordination artifacts small.

For shared topics/, questions/, requests/, and other shared state, use
`<target>.lockfile` containing agent, timestamp, and pid. Wait 1–2 seconds when
held; after roughly 10 seconds treat the holder as stalled. Release immediately
after writing. The parent removes a lock held over 60 seconds and reports the
stale holder.

When a worker needs an application boot, it writes a request such as:

    {
      "track": "wt-docs",
      "request": "capture screenshot",
      "details": "Settings page at ~1280 for docs/images/settings.png",
      "ret_id": "wt-docs-1"
    }

The parent handles requests serially and writes `results/<track>-<n>.json` with
done/failed, a short summary, and an artifact path. Workers do not block on the
result.

## Launcher and routing mechanics

In Kilo CLI sessions use one launcher invocation for the entire fan-out. The
launcher has no --free-only flag; enforce free-only through a custom manifest
and config override. The override replaces the tracked config, so copy the
full blacklist section verbatim. Disable opencode-go and openai explicitly;
policy.allowPaid=false alone does not disable account-priced providers. Keep
allowFree=true and denyFreeForSensitive=false.

The kilo provider may be enabled only when targeting a `kilo/*:free` route; then
widen its include to `*`. Otherwise disable it. Verify the printed route plan
before launch: every route must be free and no route may match the blacklist.
Record the exact route, effort, fallback, and availability evidence.

Example override shape (replace the blacklist placeholder with the exact
tracked section):

    {
      "providers": {
        "kilo": {"enabled": false},
        "opencode-go": {"enabled": false},
        "openai": {"enabled": false}
      },
      "policy": {"allowPaid": false, "allowFree": true, "denyFreeForSensitive": false},
      "blacklist": "copy verbatim from the target-owned ARR provider-policy.json"
    }

For a `kilo/*:free` route, set `kilo.enabled=true` and `kilo.include=["*"]`. Keep
the manifest and override in the gitignored coordination directory.

Use a script because Kilo's shell wrapper can mangle long inline task strings:

    #!/usr/bin/env bash
    set -euo pipefail
    cd <parent-repo-root>
    exec ./.agents/runtime-router/adapters/kilo/route_subagents.py \
      --manifest .worktrees/.coordination/manifest.json \
      --config .worktrees/.coordination/free-only-config.json \
      --max-workers 5 \
      --timeout 1800 \
      --allow-edits \
      --approve \
      --auto

allow-edits forces workers to the parent root. Every manifest track must set
read_only: false, scope source files to `.worktrees/wt-<track>/...`, scope
coordination files to the parent absolute coordination directory, and state
that working-directory rule at the top of its task. Verify parent
git status --porcelain after every wave for stray edits.

## Step 0 — clean and prepare

Clean only worktrees owned by this skill and its coordination directory. If a
leftover worktree contains work that matters, preserve it on its branch before
removal; removal discards uncommitted changes.

    shopt -s nullglob
    for wt in .worktrees/wt-*; do git worktree remove --force "$wt"; done
    shopt -u nullglob
    git worktree prune
    rm -rf .worktrees/.coordination
    rmdir .worktrees 2>/dev/null || true

Start from an up-to-date main, then create a same-day unique branch
improve/overhaul-YYYYMMDD (append -2, etc. on reruns). Create the five
worktrees from that base:

    git worktree add .worktrees/wt-code -b improve/overhaul-YYYYMMDD-wt-code main
    git worktree add .worktrees/wt-docs -b improve/overhaul-YYYYMMDD-wt-docs main
    git worktree add .worktrees/wt-skills -b improve/overhaul-YYYYMMDD-wt-skills main
    git worktree add .worktrees/wt-tests -b improve/overhaul-YYYYMMDD-wt-tests main
    git worktree add .worktrees/wt-arch -b improve/overhaul-YYYYMMDD-wt-arch main

Copy tracked .kilo/ and .agents/ into each worktree only when the host needs
root-local resolution. Never copy .env, rebalancer-config.json, *.db, or
.gradle. Then create:

    mkdir -p .worktrees/.coordination/{agent-status,findings,topics,questions,requests,results}

Workers must receive the parent absolute path to this directory; a relative
path resolves inside the worker worktree and is wrong.

## Step 1 — fan out

Launch all five tracks together; never start them sequentially. Each track
uses its assigned skills and returns at most 12 report lines and 5 findings per
skill. Its prompt must include: implementer effect, worktree-only write scope,
free-route rule, heartbeat/findings contract, topic checks, no app boot, and no
commit/push/PR.

Track A reports [P0-P3] title with path/line, category, evidence, impact, and
smallest safe correction. Track B reports [WRONG|STALE|MISSING|ORPHAN] with
path/section, evidence, and fix. Track C reports finding type, paths, evidence,
and action. Track D reports defect/dependency, current versus latest, breaking
changes, security alert, and risk. Track E recommends only and all items are
L-class until separately approved.

App-boot skills go to the parent request channel. Do not run the full
autonomous-code-optimizer four-pass convergence loop here; survey Pass 1 and
Pass 3 only.

### Retry and finalize-only policy

If a track has no heartbeat for about three minutes, mark it stalled and retry
once from scratch. If the retry fails, continue with remaining tracks and mark
the final run partial. A substantial partial diff gets a finalize-only retry
instead: pass its real status and finding IDs, forbid new work, require a
self-consistency check and final heartbeat, and record implemented: false if it
cannot be completed. Every retry retains --timeout 1800 and --allow-edits.

## Step 2 — collect and triage

The parent reads compact reports, incremental findings, and coordination
results, then deduplicates into one table:

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

Report branch, tracks, worktrees, candidate PR count, ready count, adversarial
review count, approval-required count, blocked/drop candidates, recommended
merge sequence, and the next user decision. State partial runs and failed
retries explicitly.

## Step 6 — teardown

Keep worktrees until the user has reviewed candidate PRs. Then remove only the
skill-owned worktrees and coordination state, prune, and report cleanup. Do not
delete overhaul branches while PRs still refer to them.

    for wt in .worktrees/wt-*; do git worktree remove --force "$wt"; done
    git worktree prune
    rm -rf .worktrees

## Safety anti-patterns

Never run concurrent Gradle builds in one clone; claim no convergence when
coverage exclusions were widened instead of tests added; use shared stash or
copy credentials/databases/logs; boot servers in parallel worktrees; silently
change live-trading or production config; use paid worker routes; omit the
blacklist from a replacement router config; rely on launcher defaults of 900s;
batch findings until the end; let workers resolve overlap; open unchecked PRs;
merge without user review; or leave .worktrees/.coordination/ behind.
