---
name: todo-resolution
description: >-
  Find and resolve actionable TODO comments in a codebase. Use when asked to
  audit, clean up, burn down, or address TODOs; search source code for TODO
  markers, implement each feasible item, verify the implementation, and remove
  only the TODO comments whose work is complete.
---

# TODO resolution

Systematically turn TODO comments into completed, tested code. Keep the work
scoped to the repository and preserve TODOs that are not yet safe or actionable.

## Workflow

1. Establish scope and safety.

   - Inspect the worktree before editing; preserve unrelated user changes.
   - Treat source code and code-adjacent configuration as the default scope.
     Include tests when a TODO is test-related. Do not change generated,
     vendored, build, cache, or dependency files unless explicitly requested.
   - Read the repository's local agent guidance and identify the appropriate
     quality gates before making changes.

2. Inventory TODOs.

   - Search tracked source files for comment syntax containing case-sensitive
     markers such as `TODO`, `TODO:`, and `// TODO`. Use a separate, explicit
     pass for code-adjacent configuration when it is in scope.
   - Exclude build output and generated artifacts. Record file, line, exact
     comment text, and enough surrounding code to understand the intent.
   - Avoid counting prose that merely discusses TODOs unless it is itself a
     code comment the user asked to address.

3. Triage every finding.

   Classify each TODO as:

   - **Actionable** — the intended behavior is clear and can be implemented
     safely now.
   - **Needs clarification** — requirements, product behavior, or external
     context are missing.
   - **Deferred/invalid** — obsolete, intentionally left as a marker, or too
     risky for the current request.

   Do not infer a risky design change from a vague TODO. For clarification or
   deferred items, leave the comment in place and report the reason.

   Before implementation, group related actionable TODOs into reviewable
   batches. Present the inventory and ask for direction before changing ten or
   more items, more than one subsystem, or any high-impact area.

   For each batch, define the minimal expected diff before editing: the marked
   sites, required declarations or imports, and any tests that must change.
   Treat that as the default acceptance boundary.

4. Implement actionable items.

   - Read the surrounding module, callers, tests, and relevant documentation
     before editing. Follow existing architecture and naming conventions.
     Reading adjacent code provides context; it does not authorize changing it.
   - Make the smallest complete change that fulfills the TODO. For a mechanical
     TODO such as extracting a literal into a constant, add the required
     declaration and replace only the marked occurrence(s). Do not migrate
     existing adjacent consumers merely for consistency unless compilation or
     the TODO's stated behavior requires it.
   - Update or add focused tests when behavior changes or existing verification
     cannot cover the requested result. Do not alter fixtures or broaden test
     coverage solely to justify unrelated adjacent cleanup.
   - Treat changes affecting security, persistence, public APIs, live trading,
     data loss, or other high-impact behavior as requiring explicit user
     direction unless the request already clearly authorizes them.

5. Verify before retiring the marker.

   - Run focused tests or checks first, then the repository's relevant quality
     gates when practical. Inspect the diff and confirm the TODO's acceptance
     criteria are actually met.
   - Audit every line changed by the current batch against the expected diff.
     Keep only lines that directly fulfill the TODO or are required for
     compilation or verification; remove only opportunistic edits introduced
     by the current task.
   - Remove the TODO comment only after the implementation is complete and
     verification passes. Remove just the marker and any now-misleading
     wording; preserve useful rationale as a normal comment when it still
     explains non-obvious behavior.
   - Never bulk-delete TODO comments or remove a marker merely because the
     code was touched.

6. Re-scan and summarize.

   - Run the same source/comment-specific TODO search again to prove which
     markers were retired.
   - Report completed items, tests/checks run, and any remaining TODOs with
     their file locations and why they remain.

## Practical search

Start with a tracked, source-comment search. For Kotlin, Kotlin script,
JavaScript, TypeScript, HTML, and CSS files:

```bash
git grep --line-number -I -E \
  '(//|/\*|\*)[[:space:]]*TODO(:|[[:space:]]|$)' -- \
  '*.kt' '*.kts' '*.js' '*.ts' '*.html' '*.css'
```

Adapt extensions and comment syntax to the repository. Run a secondary,
path-scoped search only when configuration or another non-source format is
explicitly in scope. Use the exact same primary search for the final re-scan so
the before/after inventory is comparable.

## Completion standard

A TODO is complete only when its intended behavior exists in code, relevant
tests/checks pass, and the comment no longer adds actionable information. If a
TODO cannot meet that standard safely, keep it and explain the blocker rather
than hiding unfinished work.
