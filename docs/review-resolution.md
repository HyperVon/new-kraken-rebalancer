# Baseline review resolution

## Frozen context and boundaries

- Reviewed revision: `f476ec1d`, branch `main`, whole-repository baseline (no
  production diff at review time).
- Inputs: test/validation findings F1–F7, maintainability F1–F11, original trading
  R1–R3, replacement trading F1–F3, and security/docs completion F1–F3.
- Contracts: fail-closed single-attempt AddOrder; operator resolution of
  `UNCERTAIN` only; strict unknown-key configuration parsing; unchanged JaCoCo
  backend 95/85 and engine 95/90 thresholds; pure KMP shared models.
- Authority: implement recommended bounded corrections, not a schema migration,
  architecture rewrite, or relaxation of live-order safety. No exchange calls,
  operator configuration/database access, commits, pushes, or PRs.

Finding identifiers below are scoped to their report, not global severity counts.
Original reviewer severity is retained for traceability, not treated as proof.

## Dispositions

### Tests and validation

| ID | Disposition | Evidence and action |
| :--- | :--- | :--- |
| F1 (P1), excluded execution journal | accepted | Remove `ExecutionDatabase*` from `backend/build.gradle.kts`. `ExecutionDatabaseLifecycleTest` now checks empty, initialized marker, binding, audit, and intent evidence independently, plus missing metadata. `ExecutionDatabaseSafetyTest` covers corrupt journals, marker races, failed pragma enforcement, and cleanup. Both report and gate measure the same classes. |
| F2 (P2), excluded Kraken capture facade | accepted | Narrow `KrakenService*` to `KrakenService.class`. Top-level capture extensions in `KrakenService.kt` now count toward the gate. |
| F3 (P2), phantom repository/table exclusion | accepted | Remove the nonexistent pattern from the quality-gates skill and synchronize its actual exclusion list. |
| F4 (P2), CI command claim | accepted | Clarify that CI runs `./gradlew build`; the explicit JaCoCo invocation is a local verification command. Existing task wiring already executes the gate. |
| F5 (P2), unused engine mapper exclusion | deferred | `engine/build.gradle.kts` currently excludes no generated mapper in use. Removing an inactive pattern has no present safety benefit; leave codegen policy to a dedicated change. |
| F6 (P2), assertion-free skip tests | accepted | Keep each fake in `OrderExecutorSubmissionSafetyTest` and assert `executedOrders.shouldBeEmpty()` for zero price and zero rounded volume. |
| F7 (P2), shutdown-test wall-clock waits | deferred | No observed flake established. The shutdown and occupied-port tests intentionally exercise real threads/Netty; increasing deadlines is not a root-cause fix. |

### Maintainability

| ID | Disposition | Evidence and action |
| :--- | :--- | :--- |
| F1 (P2), multi-responsibility classes | deferred | Extraction of controller, lifecycle, and gateway responsibilities is an architectural workstream, not a necessary fix to these findings. |
| F2 (P2), raw operator-facing strings | deferred | `DashboardController` strings violate catalog conventions but do not establish incorrect behavior. Avoid unrelated controller/catalog/UI changes here. |
| F3 (P2), client mode inference | deferred | The precedence copies agree today. Changing the save-response contract and deferred mode-plate refresh requires its own focused user-flow change. |
| F4 (P2), two CSRF origin helpers | deferred | `hasExplicitSameOrigin` requires explicit evidence for stale-token refresh; `presentOriginHeadersMatch` permits absent headers with valid tokens. Their different defaults serve different policies. No security-policy change or consolidation in this pass. |
| F5 (P2), recent-activity vocabulary | accepted | Remove `LIVE_TRADING`, which no server enum emits, from `Dashboard.kt`. Defer broader shared wire-enum/catalog redesign. |
| F6 (P2), raw CSS selectors | deferred | Selectors currently match the catalog. Broad selector/catalog migration is unrelated to measured safety defects. |
| F7 (P2), duplicated activity bound | deferred | Both bounds are currently 120; no observed divergence. Consolidation/parity tests are optional follow-up. |
| F8 (P2), unused chart color APIs | accepted | Repository search found border/background helpers used only by their own tests and unused public palettes. Delete them and their tests; keep solid-color mapping and its behavior tests. |
| F9 (P2), nonexistent TimeRange ownership | accepted | Remove `TimeRange` from current ownership lists in agent rules/common skill, leaving historical changelog entries untouched. |
| F10 (P2), unused suppressions/fixture | deferred | A suppression sweep and unrelated fixture cleanup are not required for the accepted behavioral fixes. |
| F11 (P2), literal HTMX attributes | accepted | `Settings.kt` now references `HtmxAttrs.HX_POST`/`HX_GET`; emitted values and request behavior are unchanged. |

### Original trading review

| ID | Disposition | Evidence and action |
| :--- | :--- | :--- |
| R1 (P2), PENDING resolution unavailable | deferred | `resolve` intentionally guards `state = 'UNCERTAIN'`; startup converts interrupted `PENDING` rows. Broadening to `PENDING` could resolve an actively submitted order and race outcome persistence. A safe in-process recovery design must distinguish active from abandoned submissions before implementation. |
| R2 (P2), purported Double conversion mismatch | rejected-with-evidence | Kotlin 2.4.21 stdlib `jvmMain/kotlin/util/BigDecimals.kt` implements `Double.toBigDecimal()` as `BigDecimal(this.toString())`, not binary expansion. Java `BigDecimal.valueOf(double)` also uses `Double.toString`. The claimed mismatch is false. Shared `Double` config inputs are an explicit KMP boundary; no math change. |
| R3 (P1), classify HTTP 4xx/429 as definite | rejected-with-evidence | `KrakenTransport` throws on non-success status before interpreting JSON. Status alone does not establish whether an order was accepted. The cited test pins a narrower structured HTTP-200 `error[]` rejection, not arbitrary HTTP failures. Preserve conservative ambiguity, and add HTTP 429/503 single-attempt regression tests. |

### Replacement trading review

| ID | Disposition | Evidence and action |
| :--- | :--- | :--- |
| F1 (P2), request-timeout classification unproven | accepted | Installed Ktor 3.6.1 bytecode (`javap`) shows `HttpRequestTimeoutException extends java.io.IOException`; the classifier already catches it. Add a test using the actual `HttpTimeout` plugin with production timeout values and a nonresponding mock engine. Assert uncertainty, timeout message, and one submission; do not widen production catches unnecessarily. |
| F2 (P2), Double conversion mismatch | duplicate | Same unsupported assertion as original R2; both conversions use the canonical decimal string. |
| F3 (P2), ambiguous-client-id field unused | deferred | Deleting a persisted field entails schema compatibility decisions; no present execution defect was established. |

### Security and documentation

| ID | Disposition | Evidence and action |
| :--- | :--- | :--- |
| F1 (P1), retired-key migration promised but absent | accepted | `ConfigServiceImpl.parseConfig` directly deserializes with Jackson's unknown-property rejection; CI-22-Q11 records the deliberate fail-loud policy. Correct README, algorithm, user guide, evaluation map, and agent guidance: remove retired keys before startup. Add constructor/reload rejection regression and remove the unreferenced obsolete JSON fixture. |
| F2 (P2), nonce after clock rewind/restart | deferred | Process-local monotonic nonce and fail-closed invalid-nonce handling are deliberate. Persisting nonces requires separate operational and cross-process semantics; no unsafe automatic retry is added. |
| F3 (P2), out-of-band credential edits overwritten | accepted | `configForPersistence` preserves process-loaded credentials, not unseen disk edits. SECURITY now directs operators to edit only while stopped and restart; no credential reload feature is introduced. |

## Implementation decisions

- No production order classifier, submission state transition, financial math,
  nonce policy, or database schema changed.
- Coverage thresholds and engine exclusions are unchanged. The execution journal
  and capture facade now contribute to both backend JaCoCo report and gate.
- New safety tests exercise real isolated journals and controlled I/O/driver
  failures. They verify rejection, evidence retention, and resource cleanup rather
  than changing production code merely to make it testable.
- The actual timeout-plugin regression uses a mock engine with a test dispatcher;
  the plugin's own timer still takes about 15 seconds of wall time at production
  settings. There is no live Kraken call or arbitrary sleep.
- Behavior-neutral JS token substitutions and dead shared helpers do not change
  rendered layout. Existing browser tests validate retained interactions; no
  screenshot refresh or new visual design is claimed.

## Verification

Commands executed serially; no concurrent Gradle process was started by this pass.

| Command | Result |
| :--- | :--- |
| Focused `:backend:test` for lifecycle, config, submission safety, and AddOrder signing (temporarily excluding the coverage gate) | Exit 0; new tests passed. |
| `spotlessApply :backend:test :backend:jacocoTestCoverageVerification --rerun-tasks --no-build-cache` | First two runs failed only the expanded instruction gate (94.49%, then 94.92%); tests passed. Added real corrupt-journal, marker-race, and pragma-failure regressions, without changing thresholds or adding exclusions. |
| `./gradlew spotlessApply build jacocoTestCoverageVerification --rerun-tasks --no-build-cache --console=plain` | Exit 0; 89 tasks executed, one up-to-date. All test/gate tasks executed uncached. |
| `./gradlew build jacocoTestCoverageVerification --rerun-tasks --no-build-cache --console=plain` | Final verification exit 0; all 88 actionable tasks executed, including Spotless and all test/coverage gates. |
| `npm run lint:md` | Exit 0, including the new resolution report and touched guidance. |
| `python3 .agents/scripts/validate_skills.py` | Exit 0; 50 skills verified. |
| `git diff --check` | Exit 0; no whitespace errors. |

Full-run results: backend **678**, engine **117**, common JVM **20**, common JS
**20**, frontend browser **45** tests; zero failures, errors, or skips. Common's
four removed border/background tests no longer exercise deleted APIs.

| JaCoCo metric | Backend | Engine |
| :--- | ---: | ---: |
| Instruction | 95.039% | 98.664% |
| Branch | 85.871% | 90.000% |
| Line | 97.170% | 100.000% |
| Method | 95.509% | 100.000% |

The backend instruction gate has little headroom; that is disclosed, not hidden
by an exclusion or a threshold reduction. Deferred items above remain follow-up
work, not implicit authorization for further edits.

## Round 2 follow-up

Three fresh reviewers inspected the implementation. They found no P0 or P1 issues
and identified two P2 omissions, both accepted for a bounded follow-up:

| Finding | Disposition and change |
| :--- | :--- |
| Corruption tests did not prove the journal remained unrepaired | accepted — Each case now reopens the rejected journal through direct JDBC, verifies its specific corruption remains, and checks a preexisting account-audit sentinel survived. An exception, existing path, and unchanged marker alone are no longer the complete assertion. |
| Two skills still claimed automatic retired-key migration | accepted — Correct `ktor-html-views` and `documentation-review` to require unknown-key removal before startup. A focused search also found and corrected the same stale checklist claim in `portfolio-rebalancing-math`. Rejection-only regression fixtures are not valid configuration examples. |

The parent independently reran
`./gradlew build jacocoTestCoverageVerification --rerun-tasks --no-build-cache --console=plain`
after the first worker handoff: exit 0, all 88 actionable tasks executed, 41 seconds.

Follow-up validation:

| Command | Result |
| :--- | :--- |
| `./gradlew spotlessApply :backend:test --tests 'com.gemini.krakenbot.config.ExecutionDatabaseSafetyTest' -x :backend:jacocoTestCoverageVerification --rerun-tasks --no-build-cache --console=plain` | Exit 0; all 49 actionable tasks executed. Focused test run intentionally skips the bundle coverage gate. |
| `./gradlew build jacocoTestCoverageVerification --rerun-tasks --no-build-cache --console=plain` | Exit 0; all 88 actionable tasks executed, 38 seconds. Full coverage thresholds and all suites pass. |
| `npm run lint:md` | Exit 0. |
| `python3 .agents/scripts/validate_skills.py` | Exit 0; 50 skills verified. |
| `git diff --check` | Exit 0; no whitespace errors. |

No production code, classifier, transition, schema, threshold, or exclusion changed
in this follow-up. Fresh targeted reviewers must inspect these two fixes before
parent acceptance.

## Deferred operability finding (discovered during verification)

The documented isolated-simulation workflow bootstraps a temporary run directory
with placeholder credentials, but `KRAKEN_DATA_DIR` does not relocate the
configuration file. `ConfigServiceImpl` defaults `configFilePath` to the
CWD-relative `rebalancer-config.json` (`ConfigServiceImpl.kt:31,422`), while
`DataDirectoryConfig` only owns the data directory. Starting the application from
a directory containing a real `rebalancer-config.json` therefore loads that file
even when an isolated data directory is supplied. The safe procedure today is to
run the process with its working directory set to the isolated directory.
Changing config-path resolution is a behavior change and is out of scope here.

## Round 3 verification (parent substitution)

Two fresh round-3 reviewers were launched for the two follow-up fixes. Both failed
before producing output with a provider quota error (subscription usage limit),
not a repository or diff failure. Retrying the same lane could not succeed at that
moment, and the harness rules forbid a silent provider switch, so the parent ran
the targeted verification directly and records the substitution here.

| Question | Result |
| :--- | :--- |
| Is the corruption-test finding resolved? | Yes. Each case reopens the rejected journal through direct JDBC and asserts the specific corruption remains plus a single surviving account-audit sentinel. The assertion is meaningful because `ExecutionDatabase.init` only calls `verifyInitializedSchema()` (which throws `IllegalStateException`); only `initialize`/`resumeFreshInitialization` delete artifacts, and the test calls neither. |
| Any new defect in the follow-up blast radius? | None found. Sentinel rows are inserted before each mutation, so the audit table exists for every case, and the dropped-table case does not affect the audit table. |
| Do prior P1/P2 notes still stand? | The excluded-journal P1 and the broad Kraken-facade P2 are closed. Deferred items below remain deferred unchanged. |

The three corrected skills were re-read; a repository-wide search for the retired-key
migration wording returns no remaining current-guidance match outside historical
CHANGELOG entries and this report. Parent validation after the final edit:
`./gradlew build jacocoTestCoverageVerification --rerun-tasks --no-build-cache`
exit 0 with 88/88 tasks executed, `npm run lint:md` exit 0, and
`python3 .agents/scripts/validate_skills.py` exit 0 with 50 skills verified.
