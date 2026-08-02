---
name: gradle-quality-gates
description: >-
  Project quality tooling — Spotless/ktlint 120, allWarningsAsErrors, JaCoCo
  gates (95/90) and exclusions sync, Karma Istanbul thresholds, CI
  ./gradlew build jacocoTestCoverageVerification, and CodeQL workflow guidance. Use
  when changing build.gradle.kts, coverage, CI, or verifying a change.
---

# Gradle Quality Gates

## How this differs from nearby skills

| Skill | Role |
| :--- | :--- |
| **gradle-quality-gates** (this) | Canonical thresholds + verify commands (Spotless, JaCoCo, Karma, markdownlint) |
| [write-kotest](../write-kotest/SKILL.md) | How to write tests that feed those gates |
| [commit-and-push](../commit-and-push/SKILL.md) / [open-pr](../open-pr/SKILL.md) | When to run gates in ship/PR workflows |
| [frontend-js-development](../frontend-js-development/SKILL.md) | JS behavior; defers coverage numbers here |

## Formatting & compiler

- **Spotless** + **ktlint 1.7.1**, `max_line_length = 120`
- Targets: `src/**/*.kt`, `common/src/**/*.kt`, `frontend-js/src/**/*.kt`
- Excludes: none (all Kotlin under `src/**`, `common/src/**`, `frontend-js/src/**`)
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

### Multi-agent / CI verification

- One `./gradlew` per clone at a time — concurrent workers cause `EOFException`
  and flaky `UP-TO-DATE`.
- After parallel tracks merge, the parent reruns gates serially:

  ```bash
  ./gradlew build jacocoTestCoverageVerification --rerun-tasks
  ```

- Fast evaluation iteration is fine
  (`-x jacocoTestCoverageVerification`), but run full gates before shipping.

## JVM coverage (JaCoCo)

Minimums in `build.gradle.kts` `jacocoTestCoverageVerification`:

| Metric | Min |
| :--- | ---: |
| Instruction | 95% |
| Line | 95% |
| Method | 95% |
| Branch | 90% |

### JaCoCo exclusion sync rule

Exclusions live in the shared `coverageExcludes` list in root
`build.gradle.kts`. **Both** `jacocoTestReport` and
`jacocoTestCoverageVerification` must use the same
`fileTree { exclude(coverageExcludes) }`.

Current exclusions:

- `**/config/DatabaseConfig*`, `**/config/KtorConfigKt*`
- `**/repository/table/**`
- `**/service/KrakenService*`, `**/service/impl/KrakenServiceImpl*`
- `**/view/util/HtmlExtensionsKt*`, `**/view/css/**`
- `**/KrakenRebalancerApplication*`

When moving packages:

1. Add tests, or add to `coverageExcludes` with a comment (views / DSL /
   Ktor bootstrap only).
2. Update the README package tree.
3. Never exclude money-path packages (`OrderExecutor*`, `RebalancerEngine`,
   trade repositories).

Acceptable exclusions: Ktor bootstrap, HTML DSL / CSS, thin Kraken interface.
Never exclude trade persistence, executor, engine, or config validation — add
tests instead.

## JS coverage (Karma / Istanbul)

`frontend-js/karma.config.d/coverage.js`:

| Metric | Min |
| :--- | ---: |
| Statements / functions / lines | 90% |
| Branches | 75% |

## CI / check commands

```bash
./gradlew build jacocoTestCoverageVerification
./gradlew :frontend-js:jsBrowserTest
./gradlew spotlessCheck
npx markdownlint-cli .agents/AGENTS.md .agents/OPERATING.md CLAUDE.md .github/copilot-instructions.md CHANGELOG.md CONTRIBUTING.md README.md SECURITY.md docs/*.md .agents/skills/**/SKILL.md .agents/skills/**/*.md
```

`check` depends on JaCoCo verification and frontend browser tests.
Include `CONTRIBUTING.md` and `SECURITY.md` in markdownlint when present.
Also lint `.agents/OPERATING.md` and thin harness stubs (`CLAUDE.md`,
`.github/copilot-instructions.md`) whenever agent OS files change.

## CodeQL

**Enabled** — `.github/workflows/codeql.yml` runs the `java-kotlin` analysis on
`main` pushes and pull requests using CodeQL Action **v4.37.4** and bundle
**2.26.2**, which support this project's Kotlin **2.4.10** compiler. Keep the
workflow SHA pin and manual Gradle build aligned with the supported bundle.

## Checklist

- [ ] Spotless 120 + warnings-as-errors respected
- [ ] JaCoCo 95/95/95/90 and Karma 90/90/90/75 quoted accurately
- [ ] Exclusions synced when packages change
- [ ] CodeQL workflow and documented Action/bundle support stay aligned
