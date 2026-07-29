---
name: reduce-code-size
description: >-
  Reduce production and test code through behavior-preserving deletion, reuse,
  Kotlin language features, focused helpers, and cohesive file decomposition.
  Use for repository-wide or targeted requests to shrink code, remove
  duplication, simplify verbose implementations, split oversized Kotlin,
  Kotlin/JS, CSS DSL, or Kotest files, and reduce merge-conflict hotspots while
  keeping readability, architecture, safety invariants, and coverage intact.
---

# Reduce Code Size

Make the smallest clear implementation of the existing behavior. Optimize for
maintainability and reviewability, not minimum character count.

## Workflow

1. Read repository rules and architecture skills before editing.
2. Record the clean worktree state and baseline line counts.
3. Run `scripts/measure_code_size.sh` to locate large files and construction
   hotspots.
4. Read each candidate before choosing a refactor. Confirm that duplicated code
   represents one concept rather than coincidentally similar behavior.
5. Refactor in cohesive slices and run targeted tests after each slice.
6. Run formatter, compiler, full tests, and coverage gates.
7. Re-run the measurement script and report before/after counts plus any large
   files intentionally retained.

## Reduction Priority

Apply techniques in this order:

1. Delete dead, duplicate, redundant, or generated-by-hand code.
2. Reuse existing domain types, constants, extensions, fixtures, and DSLs.
3. Replace repeated object construction with typed builders or data-class
   `copy`, keeping behavior-changing values explicit.
4. Extract a helper when the same concept appears at least three times.
5. Use Kotlin collection operations, scope functions, default/named arguments,
   sealed types, and extension functions when they improve clarity.
6. Introduce a dependency only when its total code and maintenance cost is
   lower than the code it replaces.

Do not create generic base classes, one-implementation factories, broad utility
bags, or parameter-heavy helpers merely to reduce line count.

## Splitting Large Files

Treat size as an investigation trigger, not an automatic defect. Prefer files
below roughly 800 lines and investigate files above 1,000 lines.

Split by reason to change:

- Production: protocol/configuration, calculation, persistence mapping,
  rendering, DOM lifecycle, chart construction, and formatting.
- Kotest: fixture/context, one behavior family per spec, reusable builders, and
  assertions. Keep test names and isolation semantics unchanged.
- Kotlin/JS: chart state, chart builders, trade-table rendering, remote loading,
  and preferences. Preserve callback reattachment and DOM cleanup.
- CSS DSL: theme/tokens, layout, components, forms, navigation, tables, and
  media queries.

Prefer package-private or `internal` collaborators over inheritance. A split
must reduce merge overlap and make ownership clearer; moving arbitrary line
ranges without a cohesive name is not an improvement.

## Safety Rules

- Preserve public behavior and wire formats unless the user requests a change.
- Preserve financial fail-closed behavior, `BigDecimal` precision, mode
  orthogonality, backend pinning, cancellation propagation, and durable order
  identity.
- Do not collapse architecture boundaries to save lines.
- Do not reduce tests by deleting distinct cases or weakening assertions.
- Do not widen coverage exclusions.
- Keep fixtures safe by default. Make live-like `dryRun = false` values explicit.
- Use `apply_patch` for edits and preserve unrelated user changes.

## Verification

Run the repository's targeted tests after each extraction. Before completion,
run its formatter, build, frontend tests when applicable, and coverage gates.
Afterward, require:

- no new warnings or test failures;
- no architecture or layer violations;
- no accidental behavior changes;
- fewer duplicated lines or a smaller conflict surface;
- every remaining oversized file justified in the final report.

If total lines grow, explain why the split still materially improves ownership
and merge safety. Do not claim code-size success from file movement alone.
