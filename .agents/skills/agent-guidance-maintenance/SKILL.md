---
name: agent-guidance-maintenance
description: >-
  Adopt, add, audit, refresh, or update Agent Guidance Kit content in an
  existing target repository. Use after initial adoption so the user does not
  need to remember the kit checkout path. Resolve the source portably, inspect
  receipts and local guidance, plan first, obtain explicit approval, and never
  overwrite locally modified adopted content.
---

# Agent Guidance Maintenance

Maintain guidance previously adopted from Agent Guidance Kit. The active
harness performs semantic selection and reconciliation; deterministic source
and installer scripts resolve paths, calculate dependency closure, compare
receipts, plan exact changes, and apply approved content.

## Contract

- **Input:** target repository, requested adopt/add/audit/refresh/update action,
  current target guidance, installed receipts, and a resolvable kit checkout.
- **Output:** source-resolution evidence, current adoption state, dependency-
  closed proposal, source-canonical guidance comparison, exact plan and
  conflicts, approval gate, receipt, target routing, and verification evidence.
- **Owner:** ongoing Agent Guidance Kit adoption lifecycle in a target project.
- **Non-goals:** silently updating content, choosing providers or models,
  fetching a missing kit checkout, silently refreshing a source checkout, or
  replacing project-local guidance.
- **Side effects:** none before approval; afterward, only the unchanged approved
  plan and separately disclosed target-local adaptations.

## Resolve the source

From the target root, run the bundled resolver:

```text
python .agents/skills/agent-guidance-maintenance/scripts/resolve_source.py \
  resolve --target .
```

Resolution order is:

1. an explicit `--kit-root` for the current invocation;
2. `AGENT_GUIDANCE_KIT_ROOT` in the current environment;
3. the ignored target-local locator file;
4. a validated adjacent `agent-guidance-kit` sibling;
5. otherwise stop and ask for the checkout path.

Never search unrelated directories, fetch a replacement, or write a personal
path into tracked guidance. Initial adoption records an ignored locator when no
higher-priority portable source already resolves the kit and a Git worktree is
available; an already valid environment, locator, or adjacent-sibling result is
preserved as the source-resolution method without redundant locator state.

## Optionally refresh the source checkout

When the user explicitly asks for the latest kit version, “update the kit,” or
equivalent wording, refresh the already resolved local checkout before planning
target changes. “Latest” means the checkout's intended `origin/main`; it does
not mean searching for or fetching an arbitrary replacement checkout.

First inspect and report:

- the resolved source path and current commit;
- whether it is a real Git worktree on the `main` branch;
- the configured `origin` URL and whether it is the intended Agent Guidance
  Kit source;
- whether the worktree is clean and whether a rebase, merge, cherry-pick, or
  other Git operation is in progress;
- whether local `main` and `origin/main` have diverged or local `main` is ahead.

Refresh only when the source is a clean `main` worktree with the intended
remote and no divergent local commits. The only permitted source-refresh
operations are equivalent to:

```text
git fetch origin main
git pull --ff-only origin main
```

Never switch branches, reset, stash, merge, rebase, force-update, change the
remote, or discard source-checkout work automatically. If the source is dirty,
detached, not on `main`, missing the expected remote, or diverged, stop and ask
the user what to do. Do not treat a source-refresh request as approval to apply
the resulting changes to the target.

After a successful refresh, record the old and new source revisions in the
maintenance report and approval context; the generated plan records the new
source revision. Re-read the current source `bootstrap-project` skill and
continue with the normal receipt-aware plan. The target still requires explicit
approval of that exact plan before any adopted content or routing changes are
applied.

## Workflow

1. **Working tree pre-flight.** Inspect target working tree status
   (`git status --porcelain`). If the target repository has uncommitted
   modifications in `.agents/` or root guidance files, warn the user and
   recommend committing or stashing WIP before applying maintenance updates.
2. **Read local policy and receipts.** Read target-local guidance and the latest
   adoption receipts under `.agents/.agent-guidance-kit/receipts/`. Treat local
   policy as authoritative.
3. Resolve and validate the kit source. If the user requested the latest source,
   run the optional source-checkout refresh procedure first. Record how the
   source was resolved and its Git revision; do not assume an old locator still
   points to a valid kit.
4. Compare source-owned canonical guidance as a separate maintenance surface,
   not only the receipt-managed skill directories. At minimum inspect the
   source and target `.agents/AGENTS.md` and `.agents/OPERATING.md`, plus any
   harness projections reported by the kit's recommender. Run:

   ```text
   python <kit-root>/scripts/harness_recommendations.py \
     --kit-root <kit-root> --target <target-root> --json --diff
   ```

   Record each `REVIEW` or `RECOMMEND` finding as a required plan item with its
   canonical owner, source/target evidence, and disposition. A changed
   source-owned always-on section (for example, a new `OPERATING.md` quality
   baseline) is never an informational note: compare the exact change, keep
   target-specific invariants, and propose `ADAPT`, `KEEP_LOCAL`, or `DEFER`
   before approval. For an audit, also compare installed receipt digests,
   current target manifests, source manifests, dependency declarations, and
   managed AGENTS routes. Do not mutate anything.
5. For add, refresh, or update, read the current source `bootstrap-project`
   skill, inspect candidate skills, and choose the smallest useful set. A
   refresh may select all receipt-owned skills; adding a skill selects it plus
   its required dependency closure. Optional related skills remain suggestions.
6. Generate a new plan with the resolved source installer. Review requested and
   automatically added skills, create/update/unchanged statuses, source-owned
   canonical-guidance findings, conflicts, managed routing, source revision,
   and content digests. The mechanical plan does not apply canonical guidance
   adaptations; those edits must be listed separately and approved explicitly.
7. Stop for explicit approval of that exact plan and every canonical-guidance
   disposition. A locally modified adopted skill, routing conflict, source
   drift, target drift, or unresolved canonical-guidance finding is a stop
   condition.

   **Resolving target divergence:**
   When an adopted skill's digest differs from upstream kit source and previous receipts:
   1. *Report the exact diff:* Display a unified diff comparing upstream kit version, previous receipt digest, and current target-modified content.
   2. *Present explicit resolution options to the user:*
      - **Option A (Keep Local / Unmanage):** Retain target modifications permanently. Remove the skill from future receipt updates and move its route outside the managed AGK block into the project-owned index table.
      - **Option B (Upstream Contribution):** If the local modification is generic and reusable, propose upstream contribution via `upstream-contribution`.
      - **Option C (Manual 3-Way Merge):** Synthesize upstream improvements into the target-local skill while preserving project-specific customizations, then update the local receipt with explicit approval.
      - **Option D (Overwrite / Reset):** Discard local divergence and reset to upstream canonical version (requires explicit user confirmation).

   **Upstream deprecation and retirement:**
   When an upstream kit release renames, consolidates, or removes an adopted skill:
   - The maintenance plan must explicitly list the retired skill as `RETIRE` rather than silently deleting it.
   - If the skill directory contains target-created files (e.g., custom evals, scripts, or notes), preserve the directory and warn the user.
   - Upon approved application, remove the retired skill from `.agents/.agent-guidance-kit/receipts/` and update the managed AGENTS route block.
8. Apply the unchanged plan with `--approve`. Receipt-backed unmodified content
   may be refreshed atomically; new content is create-only; local divergence is
   never overwritten.
9. Run the bundled target validator plus the target repository's relevant
   guidance and project gates. Report every pass, failure, skip, and conflict.

## Stop condition

Stop after a read-only audit or after the approved plan, receipt, managed route,
and target checks agree. Ask the user when no source can be resolved, the target
has local divergence, optional related skills require a product decision, or a
semantic integration edit falls outside the approved plan.
