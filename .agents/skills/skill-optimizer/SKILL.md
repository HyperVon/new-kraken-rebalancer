---
name: skill-optimizer
description: >-
  Audit repository agent rules, skills, harness adapters, and related guidance
  for context waste, duplication, drift, conflicts, and progressive-disclosure
  opportunities. Use when asked to compress, shrink, rationalize, deduplicate,
  or optimize an agent playbook while preserving routing, correctness, safety,
  and verification behavior. Report by default; apply edits only after explicit
  approval of the proposed changes.
---

# Skill Optimizer

Reduce the context cost of an agent playbook without weakening what agents must
know or do. Treat this as a semantic optimization problem, not a word-count
exercise: a shorter rule that loses a safety gate, trigger, exception, or
validation step is a regression.

## Contract and boundaries

- **Input:** the current repository and its checked-in agent guidance.
- **Output:** an evidence-backed inventory, approximate context-cost baseline,
  ranked compression candidates, estimated savings, risk, and a reversible
  apply plan.
- **Default side effect:** none. Do not rewrite, delete, merge, or move existing
  guidance during an audit.
- **Apply mode:** require explicit approval for each finding or an explicitly
  bounded group of findings. Use `skill-authoring` for skill-file edits and keep
  the relevant indexes, projections, and dated changelog entry synchronized.
- **Nearby skills:** use `rules-and-skills-audit` for structural findings,
  `skill-reviewer` for missing content, and `documentation-review` for factual
  doc-to-source drift. This skill owns context reduction and preservation of
  effective behavior; it must not turn an optimization pass into content
  enrichment or an application-code refactor.

Never compress away live-trading safeguards, credential/CORS boundaries,
`dryRun` versus `simulation`, durable-order-journal behavior, quality gates,
model-selection approval rules, UI verification requirements, or the
source-of-truth hierarchy. Repeated safety text is intentional when removing
it would make a dangerous boundary harder to discover.

## Source-of-truth model

Resolve disagreements in this order, while using source, tests, and build files
as factual evidence:

1. The current user request and explicit approval boundaries.
2. `.agents/AGENTS.md` for project invariants and the canonical skill index.
3. `.agents/OPERATING.md` for portable always-on norms.
4. The owning skill for task-specific procedure and domain constraints.
5. Harness files such as `.cursor/rules/`, `.clinerules/`, `CLAUDE.md`, and
   `.github/copilot-instructions.md` as thin projections or entrypoints.

Do not resolve a conflict by deleting one side. First identify the canonical
owner, verify the fact, and either repair the conflict or stop with a finding.

## Cache-aware authoring

Caching is a compatibility and measurement constraint, not a promise this
skill can guarantee: the host, model, prompt assembler, routing key, and cache
policy remain outside a Markdown file's control. Optimize for both lower
context cost and a stable reusable prefix.

When a harness or API supports prompt caching:

- Keep the byte-for-byte stable material first and in deterministic order:
  global invariants, stable skill instructions, stable examples/tool schemas,
  then the current request, repository state, timestamps, IDs, diffs, and tool
  output. A change anywhere before a cache boundary can invalidate the shared
  prefix.
- Keep canonical guidance free of run-specific data such as current dates,
  branch names, absolute worktree paths, generated IDs, inventory counts,
  transient test output, and user-specific facts. Put those in the run report
  or dynamic suffix instead of rewriting the reusable skill.
- Preserve stable section order, link order, serialization, line endings, and
  tool/image/schema definitions. Do not reflow unrelated text in a cacheable
  prefix during an optimization edit.
- If a stable core is split from references, keep the core first and make
  reference selection and ordering deterministic. Do not interleave per-run
  findings into the core, and do not move a safety boundary behind optional
  loading merely to improve a cache metric.
- Check for a threshold cliff before compressing. If the target API requires a
  minimum cacheable prefix, report when a proposed edit would take the stable
  prefix below that threshold; never add padding just to cross it. The current
  OpenAI API guide documents a strict 1,024-token minimum for GPT-5.6 and later
  and model-dependent minimums for older models; verify the target model rather
  than hard-coding this assumption.
- For an OpenAI API adapter, use the current prompt-caching controls only when
  that adapter supports them: reuse a stable `prompt_cache_key`, place an
  explicit breakpoint after stable content when appropriate, and use explicit
  mode when changing suffixes should not be written as cache entries. Keep
  provider-specific fields out of repository-agnostic skill instructions.

Measure cache behavior separately from context reduction. When runtime usage
is available, record the model, cache key/boundary, eligible prefix tokens,
`cached_tokens`, `cache_write_tokens`, and request-level hit rate. Report
`cached_tokens / input_tokens` as cached-token coverage, not as a hit rate, and
do not infer actual cache hits from Markdown bytes or the `characters / 4`
proxy. If runtime usage is unavailable, report only prefix stability and
tokenizer-based estimates, explicitly labeled as unverified.

See the [OpenAI prompt caching guide](https://developers.openai.com/api/docs/guides/prompt-caching)
for provider-specific behavior; do not treat it as evidence about a Codex
loader's internal cache.

## Workflow

### 1. Establish scope and baseline

1. Interpret an absent mode as **report-only**. Treat `apply`, `implement`, or
   equivalent wording as permission to propose edits, not blanket permission to
   rewrite every candidate.
2. Record the repository root, current branch, and `git status --short`. Do not
   overwrite unrelated worktree changes.
3. Discover nested guidance rather than assuming that root files are the whole
   playbook. Record candidates skipped and why.

Run the read-only inventory helper:

```bash
python3 .agents/skills/skill-optimizer/scripts/guidance_inventory.py \
  --root . --format markdown
```

The helper reports lines, words, bytes, a deliberately rough `characters / 4`
token proxy, scope, headings, and exact repeated prose candidates. It defaults
to active guidance; add `--scope all` when archive/backlog material is part of
the question. It is a measurement aid, not proof that text is semantically
interchangeable.

### 2. Build the guidance inventory

Review, as applicable:

- every nested `AGENTS.md`, `OPERATING.md`, `CLAUDE.md`, and `.cursorrules`;
- every `.agents/skills/*/SKILL.md` and one-level sibling reference file;
- `.cursor/rules/**/*.mdc`, `.clinerules/**`, and Copilot instructions;
- nearby `.codex/`, `.kilo/`, `.opencode/`, or other harness guidance;
- indexes, human-facing agent-playbook docs, and changelog entries that route to
  or describe the guidance.

For each file, record its role, scope, trigger, dependencies, source-of-truth
claims, safety rules, workflow/checklist content, and validation instructions.
Read the actual files behind links; do not infer redundancy from filenames.

### 3. Find real context waste

Classify each candidate as one or more of:

- **Exact duplicate:** the same substantive block appears in multiple loaded
  files and has one clear canonical owner.
- **Near duplicate:** two blocks say the same thing with wording differences;
  compare exceptions and audience before proposing a link or rewrite.
- **Projection bloat:** a harness adapter repeats canonical guidance instead of
  pointing to it, while retaining enough text for that harness to discover the
  canonical file.
- **Progressive-disclosure miss:** examples, background, or rare variants load
  on every invocation but can move to a directly linked sibling reference.
- **Routing waste:** descriptions, indexes, or broad always-on rules cause an
  unrelated skill to load, or duplicate entries make routing ambiguous.
- **Drift/conflict:** stale, unreachable, or contradictory guidance. Fixing a
  false statement may improve both correctness and context cost, but label it as
  drift rather than claiming it is merely compression.

Treat deliberate safety reinforcement, distinct audiences, short routing
indexes, and thin harness pointers as **keep separate** unless evidence shows
that they materially load duplicate content.

Estimate savings conservatively. Count only removable substantive content,
avoid summing overlapping candidates, and label estimates as ranges because
the host's tokenizer and skill-loading behavior are not known from Markdown
bytes alone.

### 4. Test whether compression preserves effectiveness

For every proposed change, specify:

1. the current owner(s) and the replacement canonical owner;
2. the exact text or section to remove, shorten, link, or defer;
3. the invariant, trigger, exception, and validation behavior that must remain;
4. one prompt that should route to the skill, one neighboring prompt that
   should route elsewhere, and one ambiguous prompt with a tie-breaker;
5. link, frontmatter, index, Markdown, and projection checks required after the
   change.

Reject a candidate when it relies on a link the target harness cannot resolve,
when it moves high-risk instructions behind an unlikely reference load, when it
weakens a thin adapter's discoverability, or when equivalence cannot be
verified from repository evidence.

### 5. Report before editing

Use this compact report shape:

```markdown
# Agent guidance optimization — YYYY-MM-DD

## Verdict
...

## Inventory
...

## Findings
- **[P1] Short title** — `path` § heading
  - Evidence: ...
  - Current cost: ...
  - Proposed change: ...
  - Estimated reduction: ...
  - Risk and preservation checks: ...

## Keep separate
...

## Apply order
1. ...
```

Include a no-change conclusion when no safe reduction is supported. State
which candidates were skipped, the files actually read, and whether the result
is a measured estimate or a tokenizer-verified count.

### 6. Apply only approved candidates

Use `apply_patch` for focused edits. After each approved group:

1. rerun the inventory and compare before/after lines, words, and proxy tokens;
2. re-read the complete changed guidance and all affected links/projections;
3. run `git diff --check`, Markdown lint for touched guidance, and the skill
   validator for any changed/new skill;
4. re-run the route and invariant probes from Step 4;
5. inspect the complete diff and `git status --short`; leave commits, pushes,
   PRs, servers, and external messages to separately authorized workflows.

If `.agents/OPERATING.md` changes, synchronize its `.cursor/rules/*.mdc` and
`.clinerules/` projections. If a skill is added, renamed, or removed, update
`.agents/AGENTS.md`, `docs/AGENTIC_DEVELOPMENT.md`, and `CHANGELOG.md` as
required by `skill-authoring`.

## Safe compression patterns

- Keep one canonical long explanation and replace other copies with a precise
  link plus the smallest boundary-specific reminder.
- Keep `AGENTS.md` as an index/invariants file, `OPERATING.md` as the portable
  always-on source, and skills as conditional procedures.
- Move rare examples and deep reference material to one-level sibling files;
  leave the decision to load them in `SKILL.md`.
- Collapse repeated checklists only when the remaining owner still names the
  command, threshold, failure mode, and stop condition.
- Shorten prose around a rule only after extracting its actors, action,
  exception, and verification step.

## Anti-patterns

- Optimizing line count while increasing ambiguity or weakening a safety rule.
- Replacing canonical guidance with a link that is not portable or not
  discoverable to the target harness.
- Deleting a repeated warning solely because it appears in more than one file.
- Treating a word-frequency report as semantic equivalence.
- Applying a broad mechanical rewrite, committing, or opening a PR without the
  user's explicit approval for that next action.
