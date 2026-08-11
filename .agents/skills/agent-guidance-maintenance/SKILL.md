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
  closed proposal, exact plan and conflicts, approval gate, receipt, target
  routing, and verification evidence.
- **Owner:** ongoing Agent Guidance Kit adoption lifecycle in a target project.
- **Non-goals:** silently updating content, choosing providers or models,
  fetching a missing kit checkout, or replacing project-local guidance.
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
path into tracked guidance. Initial adoption records an ignored locator when a
Git worktree is available.

## Workflow

1. Read target-local guidance and the latest adoption receipts under
   `.agents/.agent-guidance-kit/receipts/`. Treat local policy as authoritative.
2. Resolve and validate the kit source. Record how it was resolved and its Git
   revision; do not assume an old locator still points to a valid kit.
3. For an audit, compare installed receipt digests, current target manifests,
   source manifests, dependency declarations, and managed AGENTS routes. Do not
   mutate anything.
4. For add, refresh, or update, read the current source `bootstrap-project`
   skill, inspect candidate skills, and choose the smallest useful set. A
   refresh may select all receipt-owned skills; adding a skill selects it plus
   its required dependency closure. Optional related skills remain suggestions.
5. Generate a new plan with the resolved source installer. Review requested and
   automatically added skills, create/update/unchanged statuses, conflicts,
   managed routing, source revision, and content digests.
6. Stop for explicit approval of that exact plan. A locally modified adopted
   skill, routing conflict, source drift, or target drift is a stop condition.
7. Apply the unchanged plan with `--approve`. Receipt-backed unmodified content
   may be refreshed atomically; new content is create-only; local divergence is
   never overwritten.
8. Run the bundled target validator plus the target repository's relevant
   guidance and project gates. Report every pass, failure, skip, and conflict.

## Stop condition

Stop after a read-only audit or after the approved plan, receipt, managed route,
and target checks agree. Ask the user when no source can be resolved, the target
has local divergence, optional related skills require a product decision, or a
semantic integration edit falls outside the approved plan.
