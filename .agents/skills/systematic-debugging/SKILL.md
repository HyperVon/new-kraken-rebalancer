---
name: systematic-debugging
description: >-
  Diagnose a reproducible bug, test failure, build failure, integration
  failure, performance regression, or other unexpected behavior by finding its
  root cause before proposing or implementing a fix. Do not use for planned
  feature work without an observed failure.
---

# Systematic Debugging

## Contract

- **Input:** an observed failure, expected behavior, current state, and the
  evidence available from the repository or runtime.
- **Output:** a reproducible diagnosis, one evidence-backed root-cause
  hypothesis, the smallest safe correction, and verification evidence—or a
  bounded blocked report when the cause cannot be established.
- **Owner:** investigation and repair of an observed failure.
- **Non-goals:** general code review, speculative redesign, production
  intervention without authority, or stacking unrelated cleanup into a fix.
- **Side effects:** read-only investigation first; edits, commands with
  external effects, and commits follow the user's authority and repository
  rules.

## Workflow

1. Confirm that an observed failure exists. If the request is a general review,
   planned feature, or quality pass without failing behavior, route it to
   `code-review`, `quality-hardening`, or the ordinary implementation workflow
   instead of inventing a reproduction or root cause.
2. Preserve the failure. Read the complete error, stack trace, warning, or
   failing assertion. Record the exact command, inputs, environment, and
   expected versus observed result.
3. Reproduce it with the smallest reliable case. If it is intermittent, narrow
   the conditions and gather more evidence before guessing. For timing or
   concurrency failures, wait on an observable condition with a bounded
   timeout instead of adding an arbitrary sleep or retry-until-green loop.

   **For regressions and intermittent failures:**
   - *Regression bisection:* For regressions after recent changes, inspect git commit history and diffs across the failing subsystem. Reproduce the test on the last known-working commit to confirm that the failure is a genuine regression rather than an environment issue.
   - *Intermittent / CI-only failures:* Compare environment differences (OS, architecture, lockfiles, timezone, concurrency). Execute the reproduction under a stress loop (20–50 iterations) to establish an empirical baseline failure rate before testing fixes.
4. Establish the change and data path. Inspect the relevant diff, recent
   changes, configuration, dependencies, and a known-working neighboring path.
   Trace the bad value or state backward to its first incorrect origin.
5. State one falsifiable hypothesis: “X is the root cause because Y.” Run the
   smallest diagnostic or test that can distinguish it from the alternatives.
   For performance regressions, capture a comparable baseline and change one
   variable at a time. Do not bundle multiple fixes into the experiment.
6. Once the cause is confirmed, add or update the smallest regression test or
   repeatable reproduction at the failing seam. Apply the minimal root-cause
   correction, then run the focused test, the original reproduction, and the
   repository's relevant complete gate.
7. If the hypothesis fails, record what the evidence ruled out and return to
   investigation. Continue only while the next experiment can add discriminating
   evidence. If repeated attempts leave the same uncertainty, stop and discuss
   whether the architecture, available evidence, or problem framing is wrong.

## Boundaries and gotchas

- **Prohibit the "null-check bandage":** Never fix a crash by merely suppressing the symptom at the crash site (e.g., adding `if obj is None: return`, default fallbacks, or `try/except: pass`) unless the component contract explicitly dictates that null/empty input is valid at that layer. Always trace upstream to find why the invariant was violated at the data origin.
- **Maintain diagnostic environment hygiene:**
  - Run reproductions in an isolated scratch workspace or with clean test fixtures.
  - Cleanly remove all temporary debug prints, logging probes, and diagnostic mocks before finalizing the root-cause fix.
  - Verify that local caches, build artifacts (`.pyc`, build outputs), and environment variables are reset between reproduction attempts to prevent Heisenbugs.
- A passing health check, linter, retry, or timeout does not establish root
  cause; verify the failing behavior itself.
- Do not hide an unknown behind a catch-all fallback, retry loop, delay, or
  broad refactor.
- Do not print credentials, tokens, private data, or sensitive production
  payloads while gathering evidence. Redact examples and minimize scope.
- If the failure depends on an unavailable external system, document the
  attempted reproduction, the missing evidence, and the safest local check;
  do not claim the fix is verified.

## Report and stop condition

Report:

1. observed behavior and exact reproduction;
2. evidence collected and the confirmed root cause;
3. correction and regression protection;
4. focused and complete verification results;
5. residual uncertainty, environment limits, or follow-up work.

Stop before changing code when the failure is not reproducible, the evidence
supports multiple unresolved causes, the next action has external side effects
without authority, or further attempts cannot add meaningful evidence.
