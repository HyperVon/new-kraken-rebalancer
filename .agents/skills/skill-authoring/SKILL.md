---
name: skill-authoring
description: >-
  Create or modify a project agent skill after explicit approval, keeping its
  trigger, boundaries, safety contract, routing, validation, and documentation
  coherent. Use when authoring, extending, or restructuring a skill under
  `.agents/skills`.
---

# Skill Authoring

Create or modify a project skill only after the user has approved the change.
This is an implementation workflow for the agent playbook, not a review report.

## Boundaries

| Skill | Role |
| :--- | :--- |
| **skill-authoring** (this) | Create or modify an approved project skill |
| [skill-reviewer](../skill-reviewer/SKILL.md) | Recommend skill-content improvements; do not edit by default |
| [rules-and-skills-audit](../rules-and-skills-audit/SKILL.md) | Audit structure, overlap, routing, and drift |
| [documentation-review](../documentation-review/SKILL.md) | Correct product and agent docs against source/build truth |
| [changelog-and-docs-sync](../changelog-and-docs-sync/SKILL.md) | Synchronize docs and the dated changelog after an approved change |

Do not turn this into a generic coding workflow, a vendor-specific adapter, or a
replacement for the project’s domain skills.

## Canonical ownership

- The canonical skill is `.agents/skills/<name>/SKILL.md`.
- `.agents/AGENTS.md` owns the thin task-to-skill index and non-negotiable
  invariants; do not copy a full procedure into it.
- `.agents/OPERATING.md` owns portable always-on norms. Change it only for a
  cross-skill rule, and keep its committed `.cursor/rules/*.mdc` projections in
  sync.
- `docs/AGENTIC_DEVELOPMENT.md` is the human-facing catalog and should list a
  newly added skill.
- `.kilo/` is optional Kilo Code integration, not the application workflow.
  Do not make a skill depend on Kilo-only behavior unless the skill is clearly
  marked as optional and retains a portable path.
- Repository guidance has precedence over external or user-level skills.
  External material may fill a verified gap but must not override project
  invariants.

## Step 0 — Define the contract

Before editing, write down the smallest useful contract:

| Contract item | Required question |
| :--- | :--- |
| Trigger | What user intent should route here, and what nearby intent should not? |
| Non-goals | Which adjacent skills or actions remain out of scope? |
| Inputs | Which files, tools, approvals, and current-state facts are required? |
| Outputs | What report, edits, validation evidence, or handoff does it produce? |
| Side effects | Which files, processes, external services, or persistent state can change? |
| Stop condition | What proves the task is complete, and when must it stop for approval? |

If the trigger, output, and ownership are not distinct from an existing skill,
extend the existing skill instead of creating another one.

## Step 1 — Choose the smallest home

1. Inventory the relevant entries in `.agents/AGENTS.md`, the routing table in
   `.agents/OPERATING.md`, and neighboring skills before designing new text.
2. Extend an existing skill when the new guidance serves the same user intent,
   owns the same decision boundary, and can remain readable.
3. Create a new skill only when it has a distinct trigger, owner, and completion
   contract. A new name is not justified by synonyms or a small checklist.
4. Keep `SKILL.md` below roughly 500 lines. Move substantial examples or
   reference material into a sibling file and link it from the skill.
5. Prefer one authoritative rule plus links over repeating the same policy in
   several skills. Repeat a short safety reminder only when omission at that
   boundary would create material risk.

## Step 2 — Write for progressive disclosure

Keep the frontmatter short and routeable:

```yaml
---
name: exact-directory-name
description: >-
  One concrete capability and its trigger. Mention important boundaries.
---
```

Use this body order unless the workflow has a strong reason not to:

1. Purpose and boundary with nearby skills.
2. Non-negotiable project constraints.
3. Numbered workflow with decision points and stop conditions.
4. Project-specific checks, commands, or examples.
5. Verification checklist and concise anti-patterns.

Teach decisions that an agent would otherwise get wrong in this repository:
prefer concrete paths, class names, commands, thresholds, and failure modes
over generic advice. Keep source-of-truth facts in the owning skill or document;
link to long algorithm, Flow, UI, and quality references instead of copying them.

## Step 3 — Preserve safety and portability

- Inspect the current repository and applicable rules before editing. Do not
  assume an external skill’s paths, hooks, metadata, or tool behavior apply.
- Never include credentials, tokens, private keys, account data, live database
  paths, or copied runtime output in a skill or example.
- Use repository-relative paths. Do not hardcode `/Users/...`, home-directory
  layouts, machine names, or personal global configuration paths.
- Do not claim universal support for a harness, model, hook, provider, or CLI
  unless this repository configures and validates it. Keep vendor metadata out
  of portable `SKILL.md` files.
- Prefer noninteractive, bounded commands. Never introduce destructive cleanup,
  force pushes, shared stash operations, or remote side effects as defaults.
- For this project, preserve SQLite-only inspection safety, the distinction
  between `simulation` and `dryRun`, and the rule that live money, credentials,
  network exposure, and durable order safety need explicit approval.
- Keep application servers, watchers, and other long-lived processes out of a
  foreground authoring workflow. If a skill must run one, define readiness and
  cleanup behavior.

## Step 4 — Validate the skill and its routing

Before declaring the edit complete:

- Check that the directory name, frontmatter `name`, and index link agree.
- Read the description as a routing test: verify one matching prompt, one
  neighboring prompt that belongs elsewhere, and one ambiguous prompt with a
  stated tie-breaker.
- Check every relative link and every referenced script, option, path, and
  output against the current repository. Remove claims that cannot be verified.
- If a portable norm changed, update `.agents/OPERATING.md` and its relevant
  `.cursor/rules/*.mdc` projection together. Do not update projections for a
  skill-local change.
- Update `.agents/AGENTS.md` and `docs/AGENTIC_DEVELOPMENT.md` when a skill is
  added, renamed, or removed. Keep both indexes thin.
- Add a dated `CHANGELOG.md` entry for the approved playbook change; never use
  an `[Unreleased]` heading.
- Run `git diff --check` and Markdown lint on the touched guidance. Run the
  repository quality checks required by the owning skill, serially; do not
  launch concurrent Gradle builds in one clone.
- Inspect `git diff --stat`, the complete diff, and `git status`. Confirm that
  only the approved skill/rule/docs paths changed and that no secret or runtime
  file was introduced.

## Completion checklist

- [ ] Contract, non-goals, owner, and stop condition are explicit.
- [ ] Existing skills were considered before adding a new one.
- [ ] Frontmatter, links, commands, examples, and routing are verified.
- [ ] Indexes and Cursor projections are synchronized when applicable.
- [ ] Safety, portability, and side effects are documented.
- [ ] Dated changelog and Markdown validation are complete.
- [ ] Final diff contains only the approved playbook changes.

Do not commit or open a pull request unless the user separately requests that
release workflow.
