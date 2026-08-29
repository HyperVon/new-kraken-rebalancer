---
name: codebase-orientation
description: >-
  Help an agent understand an unfamiliar repository from evidence: map its
  structure, explain how it works, and onboard a newcomer. Use for "explain this
  codebase", "map this repo", or "onboard me" requests. Do not use to redesign the
  architecture (use architecture-review), repair docs (use documentation-review), or
  plan changes (use implementation-planning); this skill describes, it does not alter.
---

# Codebase Orientation

## Authority and boundary

This skill produces an **evidence-backed understanding** of a repository an agent
(or a human) does not yet know. It is descriptive only:

| Skill | Owns |
| :--- | :--- |
| **codebase-orientation** | Mapping and explaining an unfamiliar repository from evidence |
| [architecture-review](../architecture-review/SKILL.md) | Judging or redesigning the design |
| [documentation-review](../documentation-review/SKILL.md) | Fixing factual doc drift |
| [implementation-planning](../implementation-planning/SKILL.md) | Planning a change to the code |

Use it for requests like "Map this repository", "Explain this codebase", "Onboard
me", or "Help me understand this project". It does not edit code, docs, or design.

## Required overview

Produce an overview grounded in repository evidence covering:

- **project purpose** — what the software is for, in user terms.
- **technology stack** — languages, frameworks, runtimes, and key dependencies.
- **repository structure** — top-level layout and what each area holds.
- **entry points** — where execution, requests, or builds begin.
- **major workflows** — the important end-to-end paths through the code.
- **important modules** — the components that carry the core behavior.
- **integrations** — external systems, APIs, datastores, and boundaries.
- **configuration** — where settings live and what they control.
- **testing strategy** — how the project tests itself and how to run them.
- **conventions** — naming, layout, branching, and contribution norms.
- **operational concerns** — deployment, observability, failure modes.
- **unknowns** — what could not be confirmed from the available evidence.

## Orientation workflow

1. Inventory applicable repository guidance, tracked top-level areas, workspace
   or multi-package manifests, runtime and toolchain pins, package-manager
   configuration, build configuration, and CI entrypoints. Identify generated or
   vendored areas that should be excluded.
2. When multiple packages or projects exist, map the workspace root and member
   boundaries, which member owns each relevant dependency, build artifact, and
   test suite, and whether commands run from the root with a selector or from a
   member directory. Do not assume globally installed toolchains when the
   repository declares a managed runtime or container environment.
3. Identify the primary entrypoint or consumer journey and trace at least one
   representative end-to-end flow through its state, integration, and output
   boundaries.
4. Sample additional ownership areas according to scope and risk—not alphabetical
   order, line count, or language-specific filename patterns.
5. Record paths inspected, commands used, execution roots, excluded areas,
   sampling limits, and untraced workflows.

A partial inventory must be described as partial; a manifest or filename search
is not proof of architectural or workspace coverage.

## Rules

- **Verify claims from repository evidence.** Read source, configuration, tests,
  build files, and docs before asserting anything. Prefer current code over
  narrative documentation or memory.
- **Separate the three layers:**
  - *observed facts* — directly confirmed from the repository;
  - *inferred behavior* — reasoned from evidence but not directly observed;
  - *unknowns* — not confirmed; state them rather than guessing.
- **Never invent architecture.** Do not assert modules, flows, or integrations
  that the evidence does not support. An unknown is a valid and honest answer.
- **Treat READMEs and narrative docs as claims to verify against source, not
  as the source of truth.** If a README describes a feature or flow the code
  does not implement, report the mismatch as an unknown rather than repeating
  it.

## Output

Return the overview using the structure above, with each claim tagged as observed,
inferred, or unknown where the distinction matters. Stop when the repository has
been mapped to the depth the request needs; do not expand into redesign or
implementation planning.
