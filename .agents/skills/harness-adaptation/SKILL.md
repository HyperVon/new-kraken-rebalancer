---
name: harness-adaptation
description: >-
  Make a repository's canonical agent guidance discoverable by the active coding
  harness without creating a parallel source of truth. Use when adopting skills
  into a project, adding or reviewing a harness entrypoint, or diagnosing why
  guidance is not being loaded. Prefer native discovery and thin pointers;
  propose by default and change only after approval.
---

# Adapt Guidance to an Agent Harness

Integrate by capability, not by a permanent product allowlist. The active harness
and its model decide what they can discover; the repository's guidance files stay
the durable source of truth.

Read [the capability contract](references/capability-contract.md) for the full
harness profile, projection matrix, and the unknown-harness fallback before
designing an adapter.

## Contract

- **Inputs:** repository root, active harness, current guidance, intended support level.
- **Default output:** a capability profile, discovery gaps, the smallest adapter plan, verification steps, approval gate.
- **Side effects:** none by default (read-only). Apply only approved thin entrypoints, pointers, or projections.
- **Stop before mutation** when the canonical owner, instruction precedence, supported skill format, or compatibility level is materially unclear.

## Workflow

### 1. Identify the active harness

Use in-session evidence first: harness-provided context, project conventions, version
output when safe, and existing repository markers. The user or harness may state which
harness is running — do not add a classifier or separate model to infer it. Treat
environment variables and filenames as clues, not proof, and do not inspect
credentials, caches, or personal directories. If the harness cannot be identified,
use the [unknown-harness fallback](references/capability-contract.md) in the reference.

### 2. Prefer native discovery

If the harness already discovers repository guidance (e.g. `AGENTS.md` and a
`skills/` directory), adopt skills directly and add at most a thin pointer. No
duplication or adapter file is needed. Collect discovery evidence at the level
defined in step 5 before adding anything; a harmless task alone does not prove
which instruction source caused the behavior.

### 3. Add the smallest projection only if needed

When native discovery is missing or unreliable, choose the first applicable strategy:

1. **Thin pointer** — add the harness's instruction entrypoint and point it at the
   canonical `AGENTS.md` / skill files (e.g. `@AGENTS.md`).
2. **Narrow projection** — repeat only essential rules when links are not followed.
3. **Native skill registration** — expose the existing `skills/` directory through a
   supported project-local path/metadata file without forking content. When the
   harness supports an extra-locations setting (e.g. a `skills` array in the
   harness's project settings file), register the existing `skills/` directory
   there before considering a generated projection. This is native registration
   with zero file duplication; record the settings path and the verification
   command that confirms discovery.
4. **Manual entrypoint** — for harnesses without persistent instructions, provide a
   reusable prompt telling the harness exactly what to read.

See the [projection matrix and size budget](references/capability-contract.md) in the
reference for per-harness detail and truncation guardrails.

### 4. Keep one canonical source

Every adapter must delegate to the same canonical guidance; never define
harness-specific policy that contradicts it. Point to the canonical body rather than
copying it; a pointer must not silently become a second source of truth. Record the
projection's expected round-trip behavior.

### 5. Present and apply

Report a capability table (evidence, existing owner, proposed projection, confidence),
list every file to create/edit, and stop for approval before changing the target.
Apply only the approved changes, then verify discovery from the harness boundary:
validate links and follow the documented reload behavior. Classify each capability
as:

- `RUNTIME_VERIFIED` — a clean positive/negative control or harness trace proves
  the canonical guidance affected routing or behavior;
- `DOCUMENTED` — authoritative harness documentation promises discovery, but it
  was not observed;
- `BEST_EFFORT` — observed behavior is compatible with the skill, but causality
  could not be isolated; or
- `UNSUPPORTED` — discovery is absent or contradicted.

A harmless task can support `BEST_EFFORT`, but it cannot by itself establish
automatic discovery. Do not report `RUNTIME_VERIFIED` when the same result could
come from model defaults, copied context, or another instruction source.

This skill is opt-in; a target repository may mandate it via its own `AGENTS.md`
policy.
