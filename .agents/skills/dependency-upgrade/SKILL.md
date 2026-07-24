---
name: dependency-upgrade
description: Check every dependency, plugin, tool, and the Gradle/Kotlin toolchain used by the Kraken Rebalancer for newer releases, upgrade them to the latest stable versions, then migrate code to the new APIs by resolving deprecations, breaking changes, and adopting new features. Use when the user asks to update/upgrade dependencies, bump versions, check for outdated libraries, or modernize the tech stack.
---

# Dependency Upgrade

Systematically discover newer versions of everything the application depends on, upgrade to the latest **stable** releases, and update the code to match (deprecations, breaking changes, new features).

## Where versions live

There is **no** Gradle version catalog. Versions are inline string literals spread across:

- `build.gradle.kts` — JVM backend deps + `kotlin("jvm")`, `spotless` (ktlint), Jackson BOM, Netty pin (`resolutionStrategy`), yarn `resolution(...)` npm pins.
- `common/build.gradle.kts` — KMP `:common` module.
- `frontend-js/build.gradle.kts` — Kotlin/JS deps, `ksp`, `io.kotest` plugin, `npm(...)` / `devNpm(...)` packages.
- `settings.gradle.kts` — Kotlin/plugin management if present.
- `gradle/wrapper/gradle-wrapper.properties` — `distributionUrl` (Gradle version).
- `build.gradle.kts` `java.toolchain.languageVersion` — JDK version.

Many versions are declared once as a local `val`/`var` (e.g. `ktorVersion`, `koinVersion`, `exposedVersion`, `koTestVersion`, `kotlinXCoroutinesVersion`) and reused via `$var`. Update the variable, not each usage.

## Workflow

Copy this checklist and track progress:

```text
- [ ] Step 1: Detect current versions + latest available
- [ ] Step 2: Present upgrade plan, confirm scope
- [ ] Step 3: Apply version bumps (one logical group at a time)
- [ ] Step 4: Refresh build + resolve deprecations/breaking changes
- [ ] Step 5: Migrate code to new APIs & adopt new features
- [ ] Step 6: Verify (build, tests, lint, coverage)
- [ ] Step 7: Update docs (README/CHANGELOG/AGENTS)
```

### Step 1: Detect current vs latest

Run the checker. It parses all build files, resolves version variables, and queries Maven Central, the Gradle Plugin Portal, the npm registry, and the Gradle release feed for the latest **stable** version of each artifact:

```bash
./.agents/skills/dependency-upgrade/scripts/check_updates.py
```

Output is a table of `coordinate | current | latest | status`. Pre-releases (`-RC`, `-M`, `alpha`, `beta`, `eap`, `dev`, `SNAPSHOT`) are excluded from "latest".

Also check tooling not in build files:

```bash
./gradlew --version                       # Gradle runtime
gh --version && npx markdownlint-cli --version
```

Cross-reference the numbers in `.agents/AGENTS.md` §1 (Stack Specification) so the documented stack matches reality.

### Step 2: Present the plan

Summarize each proposed bump as `name: old -> new` grouped by risk:

- **Patch/minor** (safe): apply together.
- **Major** (breaking): call out each one and check its migration guide before touching code. Prefer web search / official changelogs for `Kotlin`, `Ktor`, `Koin`, `Exposed`, `Kotest`, `Jackson`, `Gradle`.

Do not upgrade past a version that requires a JDK newer than the configured toolchain unless you also bump the toolchain intentionally. Keep security-motivated pins (e.g. the Netty `resolutionStrategy` block, Jackson BOM, yarn `resolution(...)`) — only raise them, never drop below the pinned secure version.

### Step 3: Apply version bumps

Edit the version literals / variables in the build files. Bump one coherent group at a time (e.g. all Ktor artifacts share `ktorVersion`) so failures are attributable. For the Gradle wrapper, prefer:

```bash
./gradlew wrapper --gradle-version <latest>
```

### Step 4: Refresh and surface breakage

```bash
./gradlew help --refresh-dependencies
./gradlew build -x test
```

`allWarningsAsErrors` is enabled in every module, so **every new deprecation warning becomes a compile error**. Read each error: it points directly at the API that changed.

### Step 5: Migrate code

For each deprecation/breaking change:

1. Read the deprecation message / replacement hint (`@Deprecated(replaceWith = ...)`).
2. Confirm the new API against the library's release notes (web search when unsure — do not guess signatures).
3. Update call sites. Prefer the officially recommended replacement, and adopt genuinely useful new features (not churn).
4. Honor project rules in `.agents/AGENTS.md`: no FQNs (use imports), `BigDecimal` scale 8/2, coroutines on `Dispatchers.IO`, `:common` stays pure KMP.

Known migration-sensitive areas in this codebase:

- **Ktor** — plugin install APIs, SSE, HTML builder, client engine config change across majors.
- **Exposed** — DSL/DAO signatures and `java-time` module shift between 0.5x/0.6x+.
- **Koin** — module DSL / `KoinApplication` lifecycle.
- **Kotest / MockK** — assertion + matcher package moves; keep `BigDecimal` comparisons on `shouldBeEqualByComparingTo`.
- **Jackson** — always bump via the `jackson-bom` platform, not individual artifacts.
- **Kotlin / KSP** — KSP version must track the Kotlin version (`<kotlin>-<ksp>`).

### Step 6: Verify (mandatory)

Never declare done without a green run:

```bash
./.agents/skills/commit-and-push/scripts/pre_commit_check.sh
```

or manually:

```bash
./gradlew spotlessApply
./gradlew build
./gradlew test :frontend-js:jsTest
```

Backend coverage (95% line / 90% branch) and Kotlin/JS tests must pass. If a bump breaks something you cannot resolve, revert that single bump and report it rather than lowering quality gates.

### Step 7: Update docs

Update the version numbers in `.agents/AGENTS.md` §1, `README.md` (tech stack), and add a `CHANGELOG.md` entry under `### Changed` listing the upgrades. Do not commit or push unless the user asks.

## Anti-patterns

- Do not adopt pre-release/EAP versions unless the user explicitly asks.
- Do not bump only some artifacts of a shared version variable — keep families in lockstep.
- Do not delete a security-motivated version pin to make a build pass.
- Do not silence new deprecation warnings with `@Suppress`; migrate the code instead.
