---
name: complex-code-comments
description: >-
  Audit and update inline/KDoc comments — add explanations only where code is
  non-obvious or complex, fix inaccurate or stale comments, and remove noise that
  restates the code. Use when the user asks to comment complex code, review
  comments, fix stale/wrong comments, add missing why-comments, or run a
  comment hygiene / complexity-comment pass.
---

# Complex-code comments

Prefer **readable code without comments**. Add or keep comments only where the
code is complex enough that a future reader would otherwise miss intent,
invariants, trade-offs, or non-local consequences.

Always-on writing norms: [OPERATING.md](../../OPERATING.md) § Complex-code
comments. This skill is the **full audit / update pass**.

For narrative docs (`README`, `docs/*`), use
[documentation-review](../documentation-review/SKILL.md) instead.

---

## Principles

1. **Default: no comment** if names, structure, and types make the behavior
   obvious.
2. **Comment the why / invariant / trap**, not the what. Prefer a short `//`
   above the surprising block over a KDoc that paraphrases the signature.
3. **Prefer clarity over commentary** — rename, extract, or simplify first when
   that removes the need for a comment.
4. **Comments are code** — they must stay true. Wrong comments are worse than
   none; delete or rewrite when behavior changes.
5. **Do not comment everything** — a sparse, high-signal pass beats wallpaper.

### Comment when (examples)

| Situation | Comment about |
| :--- | :--- |
| Non-obvious algorithm / formula | Intent, formula, caps, units |
| Order / timing that looks wrong | Why sell-first, poll counts, early-exit |
| Invariant other code relies on | What must remain true |
| Surprising edge case | What breaks if skipped |
| Deliberate deviation from “obvious” | Why not the naive approach |
| Cross-cutting mode semantics | e.g. dryRun ≠ simulation pinning |
| Magic thresholds with domain meaning | What 95% / 99% / dust mean here |

### Do not comment when

- Restating the next line (`// increment counter`)
- Narrating getters, DI wiring, trivial CRUD, standard loops
- Duplicating `docs/ALGORITHM.md` / skill docs at length — link or point briefly
- Apologizing / TODOs that belong in issues (unless a short, actionable TODO)

### Style

- Kotlin: `//` for local why; KDoc only when the **public contract** is not
  obvious from the signature (units, preconditions, financial meaning).
- Keep comments tight (usually 1–3 lines). Explain the hard part, not the file.
- Match nearby tone; no emoji; no changelog voice inside source.
- Do not invent fake history (“legacy”, “temporary”) unless verified.

**Good** (from this repo):

```kotlin
// Pin live vs simulation for the whole sell→buy sequence; pass settings.dryRun into
// each placement so a mid-cycle config flip cannot change backend or dry-run mode.
```

**Bad**:

```kotlin
// Apply decay to counter
counter = applyDecay(counter)
```

---

## Scope

| Include | Exclude |
| :--- | :--- |
| `backend/src/main/kotlin/**`, `frontend-js/src/**`, `common/src/**` | Generated / build output |
| Complex test helpers / evaluation harnesses | Specs that only assert obvious behavior |
| Non-trivial Gradle/Kotlin build logic | Boilerplate plugin blocks |

**Priority hotspots** (scan first):

- `PortfolioCalculations`, `PortfolioAnalyzerImpl`, `OrderExecutorImpl`
- `RateLimiter`, `KrakenServiceImpl` (signing, retry/lockout)
- `TradeDeduplicator`, `TradeHistoryServiceImpl`, `SnapshotHistoryCalculator`
- Config validation / atomic write paths
- `:frontend-js` chart zoom/scrubber, SSE / STREAM·STALE

---

## Workflow

Copy and track:

```text
- [ ] Step 0: Choose scope (full repo vs paths)
- [ ] Step 1: Find complex / surprising code without adequate comments
- [ ] Step 2: Audit existing comments (wrong / stale / noisy / missing)
- [ ] Step 3: Apply edits (add / fix / remove)
- [ ] Step 4: Spot-check hotspots against ALGORITHM / skills if math/modes touched
- [ ] Step 5: Report summary
```

### Step 0: Scope

- Default: full production Kotlin/JS + shared `:common`.
- If the user names packages/files, stay in that scope.
- For “everywhere”, fan out bounded, read-only comment-audit tracks through the
  host's native parallel task surface on **disjoint** packages; one owner per
  hot file. The parent owns integration and must not give every worker the full
  repository or use manual compaction to continue an oversized task.

### Step 1: Complexity scan

Walk code looking for blocks a competent Kotlin reader would not grasp in one
pass: multi-step formulas, mode pinning, retries/backoff, dedupe windows,
precision/rounding traps, “looks redundant but isn’t” guards.

For each candidate, decide:

| Verdict | Action |
| :--- | :--- |
| Obvious after rename/extract | Refactor lightly **or** leave; no comment |
| Non-obvious, no comment | Add a short why-comment |
| Non-obvious, weak comment | Rewrite comment |
| Already well explained | Leave |

Do **not** add comments to satisfy a quota.

### Step 2: Existing-comment audit

Classify every comment in scope:

| Category | Meaning | Action |
| :--- | :--- | :--- |
| **Wrong** | Contradicts current code | Fix or delete |
| **Stale** | Refers to removed flags, old thresholds, old names | Fix or delete |
| **Noisy** | Restates obvious code | Delete |
| **Missing** | Complex block with no guidance | Add |
| **OK** | Accurate, necessary | Keep |

Evidence: read the surrounding code. For algorithm comments, cross-check
`docs/ALGORITHM.md` and domain skills — comments must match **code**, and if
docs disagree with code, fix docs via documentation-review (do not “fix”
comments to match stale docs).

### Step 3: Apply

- Edit only comments (and tiny renames that remove the need for a comment)
  unless the user also asked for refactors.
- Keep diffs reviewable; avoid rewriting entire files.
- Do not change trading behavior under the guise of commenting.

### Step 4: Verify

- Re-read edited regions: would the comment still help? Still true?
- If algorithm/mode comments changed, skim
  [portfolio-rebalancing-math](../portfolio-rebalancing-math/SKILL.md) /
  [dry-run-and-simulation](../dry-run-and-simulation/SKILL.md) for consistency.
- Run Spotless on touched files if formatting drifts (`./gradlew spotlessApply`
  or project habit). Full `build` only if logic (not just comments) changed.

### Step 5: Report

```markdown
# Complex-code comments

## Scope
…

## Added (missing why)
- `path` — …

## Fixed (wrong / stale)
- `path` — …

## Removed (noisy)
- `path` — …

## Left alone (already clear / intentionally uncommented)
- …
```

---

## Phrases that should trigger this skill

- “Comment the complex parts”
- “Go through the code and add comments where it’s not obvious”
- “Review / fix stale comments”
- “Comment hygiene”
- “Explain the non-trivial logic in place”

---

## Anti-patterns

- Commenting every function with “Calculate X” KDoc
- Essay comments that belong in `docs/ALGORITHM.md`
- Leaving known-wrong comments “for later”
- Parallel agents editing the same file’s comments
- Changing thresholds or control flow while “just commenting”
