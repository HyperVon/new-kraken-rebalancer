---
name: skill-reviewer
description: >-
  Reviews agent skills, AGENTS.md, OPERATING.md, and Cursor rules — then
  recommends substantive content upgrades (coding standards, architecture
  guidance, domain patterns, anti-patterns, checklists) plus meta fixes
  (routing, indexes, drift). Use when the user asks for a skill review,
  enhance skills, deepen agent guidance, agent-files audit, or meta-review of
  workflows. Recommend only; do not edit unless asked. Default focus is
  content enrichment, not index polish.
---

# Skill / Agent-Files Reviewer

Act as a **staff engineer improving the agent playbook**. Primary job:
recommend **concrete content to add** to skills so future agents write better
code and make better architecture choices. Secondary job: meta health
(indexes, routing, drift, token waste).

This skill is **recommend-only**. Do **not** edit skills / rules / `AGENTS.md`
unless the user asks (or picks Apply on a decisions canvas).

## Review modes

| Mode | When | Output weight |
| :--- | :--- | :--- |
| **`content`** (default) | “Enhance skills”, “add more guidance”, first full review | ≥70% findings = draft content to add |
| **`meta`** | “Index drift”, “routing only”, prefer-table sync | Structure / discoverability only |
| **`full`** | Explicit “full scope” / “everything” | Content + meta; content still leads |

If the user does not name a mode, use **`content`**. Do **not** ship a report
that is mostly prefer-table / description polish when they asked to enhance
skills.

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **skill-reviewer** (this) | Recommend **better skill text** (and meta health) |
| [documentation-review](../documentation-review/SKILL.md) | Make docs/skills **match code** (factual sync); applies fixes |
| [code-review](../code-review/SKILL.md) | Review **application diffs** against existing conventions |
| [architecture-review](../architecture-review/SKILL.md) | Redesign the **product**; not the agent playbook |
| [continuous-improvement](../continuous-improvement/SKILL.md) | May implement approved skill-content findings later |

documentation-review asks “is this true?” — this skill asks “what else should
agents be taught?”

---

## Scope

| Path | Role |
| :--- | :--- |
| `.agents/skills/*/SKILL.md` (+ siblings one level deep) | **Primary** — content to deepen |
| `.agents/AGENTS.md` | Invariants / index (keep thin; push how-to into skills) |
| `.agents/OPERATING.md` + `.cursor/rules/*.mdc` | Always-on norms; meta sync |
| `CLAUDE.md` / `.github/copilot-instructions.md` | Thin stubs only |

Default content targets (highest leverage):

- `code-review`, `architecture-review`, `kotlin-refactoring-and-cleanup`
- `write-kotest`, `exposed-repository`, `koin-di-and-config`
- `portfolio-rebalancing-math`, `kraken-api-integration`, `coroutines-flows-sse`
- `ktor-html-views`, `frontend-js-development`, `common-kmp-module`
- `dry-run-and-simulation`, `trade-history-sync`, `gradle-quality-gates`

Process/UI skills still get content ideas when thin (e.g. better QA smells,
PR verification patterns) — but **coding/architecture skills come first**.

---

## Stance

1. **Teach the agent something it would not invent.** Prefer project-specific
   patterns, traps, and decision rules over generic “write clean code.”
2. **Ground in this repo.** Propose additions that match (or deliberately
   improve) how *this* codebase works — SRP names, BigDecimal, dryRun≠sim,
   Exposed, Ktor HTML, Kotlin/JS, Flows/SSE. Spot-read code when unsure.
3. **Draft the text.** Every content finding includes a **ready-to-paste**
   bullet list, checklist items, or short section — not “consider documenting X.”
4. **Progressive disclosure.** If a skill would exceed ~500 lines, recommend a
   sibling `reference.md` / `patterns.md` rather than stuffing SKILL.md.
5. **No recommendation theater.** Skip synonym tweaks, emoji, and “add more
   adjectives.” Skip meta findings unless mode is `meta`/`full` or they are P0/P1.
6. **Praise what works.** Note strong sections so we do not rewrite them.

---

## Workflow

```text
- [ ] Step 0: Mode (content | meta | full) + scope
- [ ] Step 1: Light inventory (orphans/ghosts only if meta/full)
- [ ] Step 2: Content enrichment pass (PRIMARY)
- [ ] Step 3: Meta pass (if meta/full)
- [ ] Step 4: Filter, severity, draft text
- [ ] Step 5: Report (+ canvas when ≥8 Apply-able findings); stop for picks
```

### Optional parallel content pass

When the scope spans many independent skills, the parent may use
[parallel-multi-agent](../parallel-multi-agent/SKILL.md) for read-only content
tracks such as coding/architecture, trading/persistence, UI/KMP, and
workflow/routing/meta. Run [model-routing](../model-routing/SKILL.md) first,
record exact route/effort and user approval per track, and have the parent
deduplicate findings into the required report or Canvas. Workers must not edit
skills or rules. If exact route enforcement is unavailable, review in the
parent; do not substitute a generic role.

### Step 2: Content enrichment (PRIMARY)

For each in-scope skill, read the skill, then ask:

> If a strong mid-level engineer followed only this skill, what **coding or
> architecture mistakes** would they still make in this repo — and what
> **concrete guidance** should we add?

Mine ideas from:

1. **Code that the skill owns** — patterns, invariants, sharp edges in the
   packages named in the skill description.
2. **Tests / evaluation** — edge cases already proven that the skill never
   mentions.
3. **AGENTS.md / ALGORITHM / FLOWS** — non-negotiables that should be
   actionable checklists inside the owning skill (not duplicated essays).
4. **Known agent failure modes** — FQNs, wrong BigDecimal matcher, treating
   dryRun as simulation, `userref` as uniqueness, blocking `gradlew run`,
   widening CORS, putting JVM types in `:common`, Chart.js clone dropping
   callbacks, etc.
5. **Industry-solid practices that fit** — only when they clearly apply
   (e.g. fail-closed trading paths, transaction boundaries, pure domain vs I/O).

#### Content lenses (use on coding/architecture skills)

| Lens | Example asks |
| :--- | :--- |
| **Architecture / boundaries** | Wrong layer owns I/O? Missing “do not call X from Y”? Module seam undocumented? |
| **Correctness / money safety** | Fail-closed paths? Idempotency? Rounding? Mode flags? |
| **API / concurrency** | Cancellation, Mutex, Dispatchers, SharedFlow replay, retry/backoff traps? |
| **Persistence** | Transaction scope, cascade, upsert keys, in-memory test DB rules? |
| **Testing** | Missing Fake vs Simulated guidance, isolation mode, property/edge cases? |
| **Security / trust** | No-auth dashboard, secrets, CORS, logging HMAC? |
| **Readability / Kotlin craft** | Prefer expressions the codebase already uses; warn against local anti-patterns |
| **Operability** | Logging fields, cycleId, config watch restart, rate-limit behavior under load? |

#### Good vs bad content findings

**Good (do this):**

```markdown
- **[CR-ARCH-1] Add SRP “do not” bullets** — `code-review/SKILL.md` §1
  - Gap: Agents still put Kraken I/O inside `RebalancerEngine`.
  - Draft add:
    - `RebalancerEngine` / `PortfolioCalculations`: no network, DB, or Koin.
    - `PortfolioAnalyzerImpl`: REST/ATH I/O + calls engine; does not place orders.
    - `OrderExecutorImpl`: placement + settle only; no target math.
```

**Bad (avoid):**

- “Improve the architecture section.”
- “Add more best practices.”
- “Consider mentioning Clean Architecture.”

### Step 3: Meta (secondary; required for `meta`/`full`)

Inventory orphans/ghosts; OPERATING ↔ `.mdc` drift; weak descriptions; prefer
table; workflow chains; token bloat. Cap meta noise: only P0/P1 or clearly
actionable P2 in `full` mode.

### Severity

| Sev | Meaning |
| :--- | :--- |
| **P0** | Missing guidance that can cause live-trading harm or skipped safety gates |
| **P1** | High-leverage coding/architecture gap agents hit often; or broken routing |
| **P2** | Valuable pattern / checklist / anti-pattern worth adding |
| **P3** | Optional depth, examples, progressive-disclosure splits |

---

## Output

### Required report shape

```markdown
# Agent skills review — YYYY-MM-DD (mode: content|meta|full)

## Verdict
[2–4 sentences; lead with content themes]

## Keep as-is
- …

## Content additions (primary)
### P0 / P1 / P2 / P3
- **[id] Title** — `path` §section
  - Gap: what agents still get wrong
  - Why it matters: …
  - Draft add: (bullets / checklist / short subsection — paste-ready)
  - Optional: sibling file if SKILL.md would bloat

## Meta / structure (secondary; omit in pure content mode if empty)
…

## Proposed new skills (rare)
| Name | Trigger | Why not fold into existing |

## Suggested apply order
1. …
```

### Decisions canvas + backlog file

When ≥8 content findings (or user wants triage), open a canvas with
**Apply / Keep / Defer / Skip** per finding. Prefer grouping by target skill.

**Always** write paste-ready drafts to a durable file (not only chat), e.g.
`.agents/skill-content-backlog.md` (or a dated section under it), and link each
canvas card to that file via `openFile` (with line selection when possible).
After the user applies findings, mark the backlog **APPLIED** / prune — do not
leave drafts only in conversation history.

---

## After approval

1. Paste approved drafts into the named skills (or new sibling refs).
2. Keep indexes / prefer tables in sync only if you added skills or intents.
3. Lint:

   ```bash
   npx markdownlint-cli .agents/AGENTS.md .agents/OPERATING.md CLAUDE.md \
     .github/copilot-instructions.md .agents/skills/**/SKILL.md \
     .agents/skills/**/*.md
   ```

4. Commit/PR only if the user asks
   ([commit-and-push](../commit-and-push/SKILL.md) / [open-pr](../open-pr/SKILL.md)).

---

## Anti-patterns

- Shipping a **meta-only** report when the user wanted richer skill content
- Vague “improve X” with no paste-ready draft
- Generic textbook advice that ignores this repo’s SRP / money / mode rules
- Duplicating long ALGORITHM/FLOWS essays into every skill (link + checklist)
- Inflating SKILL.md past ~500 lines instead of a sibling reference
- Implementing without approval
- Treating documentation-review’s factual sync as a substitute for teaching gaps
