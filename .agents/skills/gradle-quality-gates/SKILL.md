---
name: gradle-quality-gates
description: >-
  Project quality tooling — Spotless/ktlint 120, allWarningsAsErrors, JaCoCo
  gates (95/90) and exclusions sync, Karma Istanbul thresholds, CI
  ./gradlew build jacocoTestCoverageVerification, and CodeQL disabled note. Use
  when changing build.gradle.kts, coverage, CI, or verifying a change.
---

# Gradle Quality Gates

## Formatting & compiler

- **Spotless** + **ktlint 1.3.1**, `max_line_length = 120`
- Targets: `src/**/*.kt`, `common/**/*.kt`, `frontend-js/src/**/*.kt`
- Excludes (still): `**/view/**`, `EvaluationScenariosTest.kt`
- Apply: `./gradlew spotlessApply` — check: `./gradlew spotlessCheck`
- **`allWarningsAsErrors`** enabled in root, `:common`, and `:frontend-js`

## Build performance

- `org.gradle.parallel=true` and `org.gradle.caching=true`; configuration cache
  is also enabled.
- JVM tests use up to two forks by default. Override with `-PtestForks=1` and
  `-PtestMaxHeap=1g` on constrained hosts.
- Routine release build: `./gradlew build fatJar` (no `clean`). Use `clean` only
  when diagnosing stale outputs; it discards Kotlin/JS, Webpack, compile, and
  test caches.
- When CI is already green and only packaging is needed: `./gradlew fatJar`.

## JVM coverage (JaCoCo)

Minimums in `build.gradle.kts` `jacocoTestCoverageVerification`:

| Metric | Min |
| :--- | ---: |
| Instruction | 95% |
| Line | 95% |
| Method | 95% |
| Branch | 90% |

Exclusions (keep report + verification filters in sync):

- `**/config/DatabaseConfig*`, `**/config/ErrorHandlingConfig*`,
  `**/config/KtorConfigKt*`
- `**/repository/table/**`
- `**/service/KrakenService*`, `**/service/impl/KrakenServiceImpl*`
- `**/view/util/HtmlExtensionsKt*`, `**/view/css/**`
- `**/KrakenRebalancerApplication*`

When adding non-tested packages (views/DSL), update **both**
`jacocoTestReport` and `jacocoTestCoverageVerification` exclusions plus README.

## JS coverage (Karma / Istanbul)

`frontend-js/karma.config.d/coverage.js`:

| Metric | Min |
| :--- | ---: |
| Statements / functions / lines | 90% |
| Branches | 75% |

## CI / check commands

```bash
./gradlew build jacocoTestCoverageVerification
./gradlew :frontend-js:jsTest
./gradlew spotlessCheck
npx markdownlint-cli .agents/AGENTS.md CHANGELOG.md CONTRIBUTING.md README.md SECURITY.md docs/*.md .agents/skills/**/SKILL.md
```

`check` depends on JaCoCo verification and frontend browser tests.
Include `CONTRIBUTING.md` and `SECURITY.md` in markdownlint when present.

## CodeQL

**Currently disabled** — `.github/workflows/codeql.yml` targets a non-`main`
branch because Kotlin 2.4.x is unsupported by CodeQL. Do not document CodeQL as
active on `main` until the workflow is re-enabled.

## Checklist

- [ ] Spotless 120 + warnings-as-errors respected
- [ ] JaCoCo 95/95/95/90 and Karma 90/90/90/75 quoted accurately
- [ ] Exclusions synced when packages change
- [ ] CodeQL disabled status not contradicted
