---
name: repository-guidance-authoring
description: >-
  Create or improve canonical agent guidance files (AGENTS.md, repository
  instructions, onboarding docs) generated from repository evidence. Use when asked
  to write or update a project's agent instructions. Do not use to author reusable
  skills (use skill-authoring), expose guidance to tools (use harness-adaptation),
  or check doc factual correctness (use documentation-review); this skill writes
  guidance, it does not build skills or verify prose.
---

# Repository Guidance Authoring

## Authority and boundary

This skill writes the **canonical, project-local instructions** an agent reads to
work in a repository — `AGENTS.md`, contribution/agent onboarding docs, and similar
instruction files. It is distinct from its neighbors:

| Skill | Owns |
| :--- | :--- |
| **repository-guidance-authoring** | Writing canonical agent guidance files from repository reality |
| [skill-authoring](../skill-authoring/SKILL.md) | Authoring reusable, portable skills under `skills/` |
| [harness-adaptation](../harness-adaptation/SKILL.md) | Exposing existing guidance to an agent harness's discovery |
| [documentation-review](../documentation-review/SKILL.md) | Checking a document's factual correctness against source truth |

Use this skill when the deliverable is a guidance file itself, not a skill and not a
doc-accuracy audit.

## Generate from evidence

Build the guidance from what the repository actually shows, not from a template of
generic advice. Include sections as the evidence supports them:

- **project purpose** — what the software does, in user terms.
- **setup commands** — how to install and bootstrap, read from package/build files.
- **development workflow** — branching, building, and running locally.
- **testing commands** — how to run the relevant test suite.
- **conventions** — naming, layout, and contribution norms observed in the repo.
- **build/deployment information** — how the project builds and ships, when present.
- **agent-specific rules** — directives the project wants an agent to follow.

## Scope and precedence

Inventory every applicable guidance file from the repository root to the target
path before authoring. Put repository-wide rules at the root and narrower commands
or constraints nearest the subtree that owns them. Nested guidance may refine its
parent within that subtree, but it must not silently contradict higher-authority
instructions. Do not restate system, user, or harness policy as repository policy.
When two applicable sources conflict and precedence cannot be established, show
both claims and stop for a decision.

## Critical rule: facts versus policy

Separate what is **discovered** from what **requires a human decision**:

- **FACTS** — discovered from code, configuration, tests, and build behavior.
  State these as observations. Example allowed: *"The project uses pytest, based on
  `pyproject.toml`."*
- **POLICY** — requires a human or team decision (merge strategy, review
  requirements, branch protection, coding mandates). Never invent team policy.

Example of a disallowed claim: *"All PRs require squash merge"* — only state this if
repository evidence (a contributing guide, CI config, or branch rule) says so.
When a policy is unknown, mark it as a placeholder for the team to confirm rather
than asserting it.

## Quality bar

- Keep claims verifiable; cite the file or configuration that supports each fact.
- Prefer a thin, accurate file over a long one padded with guesses.
- Do not duplicate a skill's content; if a reusable procedure belongs in a skill,
  point to it rather than copying it here (per `harness-adaptation`'s single-source
  rule).

## Avoid skill duplication

Repository guidance should contain project-specific rules and navigation, not copies
of reusable skill procedures. Prefer linking to skills over embedding their
workflows. A generated `AGENTS.md` or onboarding doc points at the canonical skill;
it does not become a second copy of it.

## Output

Return the drafted guidance file (or the proposed edits to an existing one) with a
short note listing which statements are facts (and their evidence) and which are
policy placeholders awaiting team confirmation. Do not begin adopting the file into
a harness; that hand-off belongs to `harness-adaptation`.
