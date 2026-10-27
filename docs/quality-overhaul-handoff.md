# Quality overhaul: selected work and decision backlog

## Status and authority

- Evidence baseline: `main` at `28237e4b2e56b7abc860ad3fa30aca25d5e5fc9a`.
- Source: the five-track audit and source-validated reconciliation used to make
  the selection. The user has since deleted the referenced MiMo-V2.6-Flash
  report; this handoff retains the selected/deferred decisions and implementation
  record, and the report will not be restored.
- This document supersedes earlier candidate priorities, not repository rules.
- The handoff was created for selection and durable tracking before switching
  models; the implementation and verification state is now recorded below.
- Selected: original finding IDs **1–20 and 24**. Deferred/decision/skip items
  are recorded below so the next agent must not rediscover or silently apply them.
- The user authorized implementation of the selected scope, including its
  narrowly scoped L-class fixes. This does not authorize changes beyond the
  selected work or changes to trading execution, settlement, journal semantics,
  credentials, CORS policy, or major dependencies.
- No commit, push, issue, or PR action is authorized or performed. Readiness and
  remaining verification limits are recorded below.

The comprehensive-overhaul skill permits a parent-owned findings report and
requires separate authorization for remote actions. This is its durable local
decision log, not a new continuous-quality cycle. Do not create GitHub issues or
modify the historical continuous-quality/improvement backlogs automatically.

## Selected work

IDs match the consolidated 37-item table in the conversation. S/M describe
normal work size; L marks safety-sensitive review, not necessarily a large diff.
Implementation status is recorded in the execution record below.

| ID | Finding | Risk | Action / acceptance |
| :--- | :--- | :--- | :--- |
| 1 | Settings mode plate becomes stale after save | L | Display authoritative published mode after immediate/deferred publication, without discarding edits. |
| 2 | Recovery UI depends on a portfolio snapshot | L | Show existing unresolved-intent recovery with no snapshot, including while paused. Preserve resolution rules. |
| 3 | Failed allocation preview discards edits | M | Preserve all submitted settings/allocation/score controls and display the error. |
| 4 | CodeQL workaround recognizes an obsolete compiler pin | M | Verify actual compatibility, then repair/remove the workaround; verify extraction, not only build success. |
| 5 | Contract assertions share production text constants | S | Replace coupled copy expectations with independent literals; retain typed constants for setup. |
| 6 | Markdown lint is not enforced in CI | S | Install locked tooling and execute the existing Markdown lint gate. |
| 7 | CORS override plugin wiring lacks integration tests | L | Add override wiring tests only; preserve CORS behavior and restore any test environment changes. |
| 8 | Shell instructions default to force/yes and bypass SSH host verification | L | Replace unsafe examples with noninteractive, fail-safe behavior; preserve Rehydra instructions. |
| 9 | Cloud boot recipe risks operator files/state | L | Require private disposable config/data, simulation and dry-run both true, background launch and cleanup. |
| 10 | Delegation instructions contradict parent-model inheritance | M | Align affected workflow text with canonical native delegation; do not redesign model routing. |
| 11 | Mandatory artifacts conflict with read-only/non-Cursor reviews | M | Permit chat reports; make optional artifacts conditional on host support and write authorization. |
| 12 | Review skill quotes wrong backend branch threshold | S | Reference canonical backend 85% / engine 90% branch gates; do not change build thresholds. |
| 13 | Tracked review memory requires obsolete Muse delegation | S | Correct the stale requirement; do not delete unknown tool state or historical records. |
| 14 | Settlement documentation omits balance-peek failure fallback | S | Describe existing fill-confirmed/projected-cash cap behavior; do not change settlement. |
| 15 | README states an obsolete two-fork default | S | Document the CPU-based formula and overrides; synchronize the same claim in the quality-gates skill. |
| 16 | README incorrectly excludes concrete repositories from coverage | S | Match actual build exclusions, preserving measured implementations and unchanged gates. |
| 17 | README omits qualityScores | S | Document JSON scores in (0,10], template empty map, and form-only blank/zero clearing. |
| 18 | README tree omits current modules/packages | S | Add codegen and missing backend model/repository/util packages; no package moves. |
| 19 | Agent architecture table uses wrong repository class name | S | Use SqliteExecutionOrderIntentRepositoryImpl; do not rename implementation classes. |
| 20 | Advisory evidence has no review date | S | Find historical review evidence or revalidate it, then date/source the assessment honestly. |
| 24 | JSON-escaping comment duplicates adjacent KDoc | S | Remove only the redundant inline explanation; preserve serialization behavior. |

## Implementation handoff

### Objective and boundaries

Fix observable UI defects, strengthen existing gates/tests, and make guidance
match current behavior. No new features, architecture changes, persistence
migrations, dependency upgrades, or public endpoint removals are selected.

Read applicable project skills before edits: `write-kotest`,
`frontend-js-development`, `ktor-html-views`, `common-kmp-module` when needed,
`gradle-quality-gates`, `changelog-and-docs-sync`, and the relevant guidance
authoring skills. Use `security-review` for safety-boundary changes and
`adversarial-pr-review` before any separately authorized high-risk PR.

At start, compare HEAD and working changes with the evidence baseline. Recheck
only findings affected by drift; preserve unrelated user work. Keep each task
cohesive. No worker may boot the app or run Gradle concurrently in one clone.

### A. Coupled UI track: IDs 1–3

Primary files:

- `frontend-js/src/jsMain/kotlin/Settings.kt`, save handling around lines 235–268.
- `backend/src/main/kotlin/com/gemini/krakenbot/controller/DashboardController.kt`:
  settings acknowledgement around 198–204, preview errors around 367–376,
  dashboard early return around 580–592, active mode around 831–835.
- `backend/src/main/kotlin/com/gemini/krakenbot/view/util/Layouts.kt` for mode chrome.
- Existing Dashboard views/components and their focused route/JS regression specs.

Requirements:

1. Mode reflects server-published settings, not checkbox intent or merely saved
   settings. Keep SIMULATION > DRY RUN > LIVE TRADING precedence. A pending save
   must not prematurely show a requested mode. Update when publication completes,
   preserve edits made while saving, and ignore stale responses from older saves.
   Preserve the distinction between trading mode and STREAM/STALE status.
   Existing `/api/health` exposes active mode; reuse an existing authoritative
   status/fragment mechanism where practical instead of inventing a parallel
   configuration channel. Unknown/unavailable status must not be called safe/live
   by default. Do not reload the whole form and lose unsaved work.
2. Reuse existing recovery rendering and CSRF-protected resolution when latest
   snapshot is absent. Do not allow PENDING resolution, alter journal transitions,
   auto-reconcile orders, or add an automatic pause/resume policy. Keep normal
   waiting content when there are no unresolved intents.
3. Thread submitted preview parameters through the existing error/form-values
   mechanism. Retain numeric settings, mode inputs, rows, scores, colors, and
   preview controls. Preserve existing CSRF replacement behavior and error status.

Write focused regressions before fixes. Cover immediate and deferred mode
publication, later unsaved edits/out-of-order acknowledgements, no-snapshot
recovery while paused, and invalid preview retention. Use fake state/services;
never switch a real account into live mode for a UI check.

This track shares the controller: one owner, not three parallel editors. UI
verification includes laptop and phone captures and interactions in isolated
simulation with both safe flags enabled. Update published screenshots only if
their represented appearance actually changes.

### B. Test/CI track: IDs 4–7

- ID 4: `.github/workflows/codeql.yml:45–54` and
  `gradle/libs.versions.toml:2`. The guard mismatch is proven; extraction failure
  is not. Use current authoritative support documentation/run evidence before
  choosing a compiler workaround. Do not blindly downgrade Kotlin or widen the
  regex to future versions. Fail clearly on unsupported configurations. A green
  local Gradle build cannot prove CodeQL extraction. If hosted extraction cannot
  be verified within authorized actions, mark this item blocked, with evidence.
- ID 5: `backend/src/test/kotlin/com/gemini/krakenbot/controller/DashboardMutationRoutesTest.kt`.
  Check direct and table-driven expected-copy assertions, not just nine lines.
  Do not rewrite unrelated setup or assert generated constants against themselves.
- ID 6: `.github/workflows/ci.yml`, `package.json`, and `package-lock.json`.
  Use the locked tooling and existing `npm run lint:md`; avoid dependency bumps.
  Run it locally. Reports under `docs/reports/` are excluded by that script;
  this handoff outside that directory must remain covered by Markdown lint.
- ID 7: `backend/src/test/kotlin/com/gemini/krakenbot/config/CORSConfigTest.kt`
  and the existing `configureCORS()` implementation in `config/KtorConfig.kt`.
  Add plugin-boundary exact-allowlist and allow-all cases, including rejection of
  an origin not exactly allowlisted. Keep default private/public cases intact.
  Reuse safe test seams or existing environment-test tooling. Do not introduce
  global environment races or modify production origin policy to simplify tests.

These can run in parallel with the UI track only if controller tests have one
owner or serial integration. Keep workflow changes separately reviewable.

### C. Guidance track: IDs 8–13 and 19

Files are `.opencode/shell-strategy.md`, `.agents/HARNESS_INTEGRATIONS.md`,
`.agents/skills/documentation-review/SKILL.md`,
`.agents/skills/architecture-review/SKILL.md`,
`.agents/skills/skill-reviewer/SKILL.md`,
`.agents/skills/code-review/SKILL.md`,
`.agents/memory/memory/adversarial-review.md`, and `.agents/AGENTS.md`.

Make targeted factual/safety corrections, not a guidance-compression project.
Keep canonical ownership, noninteractive execution, privacy protections, and
existing approval/verification requirements. Shell noninteractivity must not
grant overwrite/destructive authority. Cloud recipes must leave operator files
untouched. Validate links, skill structure, and Markdown; do not launch servers
or SSH probes merely to validate prose.

### D. Documentation/minor comment track: IDs 14–18, 20, and 24

- ID 14: `docs/ALGORITHM.md:105–110`, `docs/FLOWS.md:174–179` versus
  `OrderSettleHelper.kt:62–91`. Existing fill-settlement regression covers the
  missing-peek fallback. Document this exception without changing money behavior.
- IDs 15–18: README build guidance around 499, coverage around 597, configuration
  around 529, and tree around 394. Treat `backend/build.gradle.kts` as evidence.
  Also fix the matching stale two-fork sentence in
  `.agents/skills/gradle-quality-gates/SKILL.md:35`. Do not change build settings.
- ID 20: `SECURITY.md:318–344`. Historical source-backed review dates are acceptable;
  otherwise recheck authoritative advisory/upstream evidence. Do not insert
  today's date without an actual review or claim a new vulnerability. If evidence
  is unavailable, mark blocked and retain uncertainty.
- ID 24: `ConfigServiceImpl.kt:253–256`, comment deletion only. Preserve the
  surrounding implementation and security rationale in KDoc.

Coordinate `.agents/skills/gradle-quality-gates/SKILL.md` ownership with track C.
Add a concise changelog entry for shipped user-visible fixes; an audit artifact
alone does not require a product release or changelog entry.

### Integration, verification, and readiness

1. Integrate one cohesive theme at a time; review scope against selected IDs.
2. Run focused regression specs and required formatting checks. Do not reduce
   thresholds, add exclusions, or add coverage-padding tests to obtain green gates.
3. Parent runs final Gradle commands serially:

   ```bash
   ./gradlew build jacocoTestCoverageVerification --rerun-tasks
   ./gradlew :frontend-js:jsBrowserTest
   ./gradlew spotlessCheck
   npm run lint:md
   ```

4. Complete UI verification through project simulation/QA skills; use temporary
   configuration/data, no live credentials, and stop owned processes afterward.
5. Record actual command results, UI evidence, unresolved blockers, and per-ID
   completion in this file. No current checkbox or row implies a test already ran.
6. Keep UI, CI, guidance, and documentation changes independently reviewable.
   UI-first addresses operational defects; guidance/test/doc tracks may proceed
   concurrently when ownership is disjoint. CodeQL compatibility is independent
   and may remain blocked without holding unrelated validated fixes hostage.
7. None is Ready to merge until implemented and verified. Run mandatory
   adversarial review for any PR touching the journal/CORS/credential boundaries;
   also use it for safety-mode/recovery UI. Commit/push/PR actions need their
   separately authorized workflows; no such actions are authorized by this file.

## Durable decision backlog

These are not selected for implementation. Preserve IDs when recording later
decisions. Deferred means reconsider when its trigger occurs, not automatic work.

| ID | Item | Disposition | Decision needed / revisit trigger |
| :--- | :--- | :--- | :--- |
| 21 | Screenshot age | Deferred verification | Compare current simulation images during UI work; replace only actual divergence. Age alone is not a defect. |
| 22 | Journal all-Unit switches | Deferred, L | Simplify only during approved journal work; recording/rethrow semantics must remain identical. |
| 23 | Unreached settlement backoff cap | Deferred, L | Revisit with approved settlement work; preserve three-attempt timing. |
| 25 | Redundant buy-budget min(actualCash) | Skip standalone, L | Tiny benefit. Revisit only with an approved budget refactor and proven rounding/domain invariant. |
| 26 | Public snapshot overload / duplicated scaling | Deferred | Establish public API compatibility/consumers before removal; prefer consolidation if needed. |
| 27 | Require paused/drained recovery and explicit Resume | User decision, L | Choose procedural runbook versus enforced admission policy; define races and REST compatibility first. |
| 28 | Stalled-cycle/readiness freshness policy | User decision, L | Choose diagnostic-only versus readiness changes; define schedule-aware age limits, never auto-cancel orders. |
| 29 | Controller/config service decomposition | User decision, L | Optional maintainability choice. Identify concrete pain and preserve session/credential boundaries. |
| 30 | Extra settlement/SSE interfaces | Skip for now | Existing helpers suffice; reconsider only with an actual policy seam or additional consumer. |
| 31 | Split CurrentPortfolioState | Deferred, L | Require a concrete benefit and a design preserving atomic reads and source clearing. |
| 32 | Broad guidance compression | User decision | Agree context-cost goal and which repetition is intentional; no arbitrary line ceilings. |
| 33 | Rebalance now | User decision, L | Confirm demand and safe interaction with admission, pause, active cycles, and session pinning. |
| 34 | Durable cycle history | User decision, L | Previously retired feature. Agree value, retention, account scope, and separation from the safety journal. |
| 35 | Failure/uncertain-order alerts | User decision, L | Define delivery guarantees, trusted destinations, redaction, and maintenance burden. |
| 36 | Scheduling/quiet hours | User decision, L | Define timezone, missed-window, resume, and active-cycle semantics. |
| 37 | Config history/rollback | User decision, L | Define secret retention, permissions, account binding, and session-safe rollback before implementation. |

## Rejected findings: do not reintroduce as tasks

- Empty AddOrder response null failure: Jackson 2.22 string tree reading returns
  MissingNode; existing handling follows uncertain submission, not the claimed
  nonblocking rejection.
- Precision fuzz assertion padding: the test already checks payload precision and
  exact submitted volume after the no-throw block.
- Journal file leakage: cited integration paths use temporary directories and
  cleanup; file-backed restart testing is intentional.
- Missing STREAM/staleness regressions: explicit tests exist.
- Replace dependency-submission permission with `dependency-graph: write`:
  unsupported permission; existing `contents: write` matches action guidance.
- Generated Routes type is wrong: YAML group is Routes; RouteConstants is the
  generated filename, not the required public type.
- Automatically delete applied drafts/memory/lock files: ownership/consumers must
  be investigated; the backlog is referenced and historical state is not disposable.
- Missing skill discriminators, arbitrary skill line ceilings, two different IDE
  transport paths, and Java-versus-Kotlin reflection style are not proven defects.
- Dependency-version suspicions, transit-masked fixtures, unconditional changelog
  entries per commit, and screenshot age do not establish defects.
- The explicit 30-second trend example is not an incorrect default claim; there
  is no verified configuration-wide 30-second minimum.

## Execution record

| ID | Result |
| :--- | :--- |
| 1 | Implemented authoritative mode acknowledgements and deferred fragment refresh; controller and Kotlin/JS regressions pass. |
| 2 | Implemented recovery rendering without a snapshot; paused/no-snapshot route regression passes, with resolution choices unchanged. |
| 3 | Implemented preview-error retention for submitted controls; focused route regression passes. |
| 4 | Updated the guarded CodeQL compiler override to Kotlin 2.4.10 based on the SHA-pinned bundle's CodeQL 2.26.3 support evidence. Hosted CodeQL extraction was not run; **blocked pending CI verification**. |
| 5 | Replaced coupled response-copy assertions with independent expected literals; focused route tests pass. |
| 6 | Added the locked Markdown lint command as a CI job; local `npm run lint:md` passes. |
| 7 | Added exact-allowlist and allow-all plugin-boundary coverage without changing origin policy; focused CORS tests pass. |
| 8 | Replaced unsafe shell examples with scoped, fail-safe noninteractive guidance. |
| 9 | Documented a disposable simulation + dry-run Cloud QA recipe with isolated config/data, background launch, and owned-process cleanup. |
| 10 | Aligned delegation guidance with host-native parallel work and parent-model inheritance. |
| 11 | Made review artifacts conditional on host support and authorization; chat reports remain valid. |
| 12 | Corrected backend/engine branch thresholds in the code-review skill. |
| 13 | Removed the obsolete Muse requirement from tracked review memory. |
| 14 | Documented the existing projected-cash cap when the spendable-balance peek fails. |
| 15 | Corrected the test-fork formula in README and the quality-gates skill. |
| 16 | Corrected README coverage exclusions to distinguish interfaces from measured implementations. |
| 17 | Documented `settings.qualityScores` and its form clearing behavior. |
| 18 | Added the omitted codegen and backend package entries to the README tree. |
| 19 | Corrected the live-order repository implementation name in the architecture table. |
| 20 | Revalidated the advisory against the official GitHub record on 2026-10-27; it was withdrawn on 2026-10-23 as not practically exploitable. Updated `SECURITY.md`. |
| 24 | Removed only the redundant JSON-escaping comment; serialization behavior is unchanged. |

### Verification

- **PASS** — Focused backend specs:
  `./gradlew :backend:test --tests 'com.gemini.krakenbot.controller.ConfigSavePublicationResponseTest' --tests 'com.gemini.krakenbot.controller.DashboardMutationRoutesTest' --tests 'com.gemini.krakenbot.config.CORSConfigTest'`.
- **PASS** — `./gradlew build jacocoTestCoverageVerification --rerun-tasks`.
  This executed the backend and engine coverage gates, Kotlin/JS browser tests,
  and Spotless checks.
- **PASS** — `./gradlew :frontend-js:jsBrowserTest` (standalone invocation was
  up-to-date; the forced full build executed the task).
- **PASS** — `./gradlew spotlessCheck` (standalone invocation was up-to-date;
  the forced full build executed the checks).
- **PASS** — `npm run lint:md` after the final documentation edits.
- **PASS** — `git diff --check`.
- **BLOCKED** — Hosted CodeQL extraction/analyze was not run. The compiler
  support evidence validates the workflow choice but cannot prove extraction;
  the workflow must pass in GitHub Actions.
- **SKIPPED by user request** — Screenshot capture/inspection and visual/manual
  UI review. No image contents were read or processed.
- No application boot, commit, push, remote issue, or PR was performed.
