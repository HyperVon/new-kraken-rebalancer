# [PR Title]

## Summary

Brief 2-3 sentence overview explaining what this pull request introduces or refactors in the codebase.

## Key Changes

- **Component / Module**: Concise bullet points detailing structural changes, refactorings, or new features.
- **Financial Math / Safety**: Highlights of precision rules, `BigDecimal` scale rules, cash reserve caps, or liquidity checks.
- **Testing & Quality Gates**: Summary of new unit test specs and JaCoCo coverage updates.

## Verification Results

Only list checks that were already run before opening this PR. Do not leave
unchecked boxes for “after merge”.

- **Markdown Linting**: PASSED (0 errors)
- **JVM Backend Tests**: PASSED (340/340 tests)
- **JaCoCo Coverage**: PASSED (Instruction 96%, Branch 90%, Method 96%, Line 96%)
- **Kotlin/JS Client Tests**: PASSED

## Test plan

- [x] `./.agents/skills/commit-and-push/scripts/pre_commit_check.sh`
- [x] Adaptive bounded adversarial review converged
- [x] _(Only if needed)_ Manual/UI/sim spot-checks already performed — e.g. History
  table at ~1280px — never “after merge”
