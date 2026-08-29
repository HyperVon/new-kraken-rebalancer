---
name: implementation-planning
description: >-
  Convert approved requirements or design into an execution-ready implementation
  plan that another agent can carry out without rediscovering the design. Use
  after design decisions are settled. Do not use to define requirements (use
  requirements-and-design) or review an existing design (use architecture-review);
  this skill plans the build, it does not decide what to build or implement it.
---

# Implementation Planning

## Authority and boundary

This skill turns an **already-approved** design into a plan an implementation agent
can execute. It sits after the design decision and before the implementation work:

| Skill | Owns |
| :--- | :--- |
| [requirements-and-design](../requirements-and-design/SKILL.md) | Clarifying behavior and selecting an approach up front |
| [architecture-review](../architecture-review/SKILL.md) | Evaluating an existing design (Keep/Evolve/Replace/Greenfield) |
| **implementation-planning** | Producing the execution-ready plan from the approved design |
| implementation work | Executing the plan and writing the code |

If the requirements or the chosen approach are still undecided, route to
`requirements-and-design` first. Do not use this skill to relitigate the design or
to start coding.

## Inputs

Take the approved requirements/design artifact (or a recorded decision), the
relevant repository context, and any constraints the decision imposed. If the
design leaves a load-bearing choice unmade, mark it as an unresolved assumption
rather than guessing the answer.

Before planning, verify that the referenced requirements/design still represent the
intended state. If repository state, interfaces, dependencies, or constraints have
materially changed since the decision, identify the drift before producing the
plan. This is a freshness check, not a full architecture review: report the drift
and its planning impact, then continue or route the redesign question elsewhere.

## Required plan contents

Produce a plan that contains:

- **implementation objective** — the single outcome the plan delivers.
- **affected components/files** — concrete modules, files, or services, not vague
  areas.
- **dependency ordering** — which tasks must finish before others can start.
- **interfaces/contracts affected** — public APIs, schemas, protocols, or shared
  types the change touches.
- **migration considerations** — data, schema, or consumer migrations and their
  rollback story.
- **task breakdown** — discrete tasks, each with a clear owner boundary.
- **verification strategy** — how each task is verified (tests, checks, manual
  steps).
- **risks** — failure modes, blast radius, and mitigations.
- **unresolved assumptions** — anything the plan depends on that is not yet fixed.

## Quality bar

The plan must be detailed enough that an implementation agent does **not** need to
rediscover the design. Each task should state its concrete boundaries, ownership,
and verification so the implementer does not have to infer intent.

Prefer:

- concrete boundaries and file-level specificity;
- clear ownership per task;
- a verification step attached to every task.

Avoid:

- vague task lists ("improve the module", "refactor as needed");
- arbitrary file guesses not grounded in the actual repository;
- unnecessary decomposition that splits one coherent change into busywork.

## Output

Return the plan in the structure above. Stop when the approved design is fully
expressed as executable tasks with verification; hand execution to the
implementation step. Do not begin editing code as part of planning.
