---
name: kotlin-refactoring-and-cleanup
description: Refactor and clean up Kotlin codebase following project preferences — eliminate FQNs, reduce raw string literals, clean compiler warnings, enforce type safety, extract DRY helpers, and remove absolute paths.
---

# Kotlin Refactoring & Code Cleanup

When performing refactoring, code cleanup, or code reviews across the Kotlin codebase (JVM and Kotlin/JS), strictly enforce the following principles and project preferences.

## 1. No Fully Qualified Names (FQNs)

- **NEVER** use Fully Qualified Names (FQNs) in standard code, services, controllers, HTML DSL components, repository classes, or test files unless resolving an unavoidable class name collision.
- Always add explicit `import` statements at the top of the file.

```kotlin
// WRONG:
val formatted = com.gemini.krakenbot.util.Formatter.formatUSD(amount)
val logger = org.slf4j.LoggerFactory.getLogger(MyClass::class.java)

// CORRECT:
import com.gemini.krakenbot.util.Formatter
import org.slf4j.LoggerFactory

val formatted = Formatter.formatUSD(amount)
val logger = LoggerFactory.getLogger(MyClass::class.java)
```

## 2. Eliminate Raw Strings & Magic Values

- **Minimize Raw String Blobs**: Avoid scattering raw string literals throughout business logic, HTML templates, or CSS builders.
- **Kotlin Multiplatform `:common` Module**: Maintain shared domain models (`TimeRange`, `OrderSide`, `OrderType`), HTML IDs/attributes (`HtmlIds`, `HtmlAttrs`, `HtmxAttrs`, `SyncMetadataKeys`, `HealthStatusKeys`), UI display text (`ViewText`), precision scales (`PrecisionConstants`), and `CssClass` sealed class hierarchies inside the shared KMP subproject (`common/src/commonMain/kotlin/com/gemini/krakenbot/`).
- **Type-Safe CSS & HTML**: Represent repeated CSS classes using `CssClass` sealed class hierarchies. Ensure both backend `kotlinx.html` DSL templates and client Kotlin/JS (`:frontend-js`) DOM scripts consume shared constants from `:common` rather than duplicating frontend constants (`JsConstants`).
- **Domain Constants & Enums**: Replace magic strings and numbers with well-named `const val` declarations, `enum class` instances, or value classes (e.g., `Asset("BTC")`).

```kotlin
// WRONG:
div("flex items-center justify-between p-4 bg-gray-800 rounded-lg shadow-md mb-4") { ... }

// CORRECT:
div(CssClass.GlassCard.name) { ... }
```

## 3. Clean Compiler Warnings & Deprecations

- **Remove Unused Code**: Clean up unused `import` statements, redundant casts, unnecessary escape characters, unused variables, and redundant `inline` modifiers.
- **Address Deprecations**: Proactively update deprecated API usages (e.g., Jackson `fields()`, Exposed `createMissingTablesAndColumns`).
- **Null Safety**: Prefer idiomatic Kotlin safe calls (`?.`), `let`, `elvis` (`?:`), and default values over explicit null checks or `!!` force-unwraps.

## 4. Environment Agnosticism & Public Repository Safety

- **Public Repository Safety**: Never hardcode absolute filesystem paths (such as `/Users/charlesv/...` or `C:\Users\...`), local machine hostnames (`my-macbook`, `charles-pc`), or developer-specific local network hosts in source code, configuration files, test data, or mock assertions.
- Always use relative paths, classpath resources (`getResourceAsStream`), workspace-relative temp paths, environment variables, or generic hostnames (`app-server.local`, `localhost`).

```kotlin
// WRONG:
val file = File("/Users/charlesv/Projects/new-kraken-rebalancer/data/test.json")
val host = "my-macbook.local"

// CORRECT:
val file = File("data/test.json")
val host = "app-server.local"
```

## 5. DRY & Modular Layout Helpers

- **Single Responsibility Principle (SRP)**: Keep functions and components short, focused, and single-purpose.
- **Extract Layout Helpers**: Refactor recurring HTML/CSS components into reusable Kotlin extension functions or modular helper objects (e.g., `Layouts.kt` helpers like `statusCard`, `glassPanel`).
- **Utility Methods**: Move repeated validation, math formatting, or array manipulation logic into dedicated extension functions or utility objects.

## 6. Modular CSS & Package Layout Synchronization

- **Modular CSS Packages**: Keep CSS definitions organized under domain-specific files inside `com.gemini.krakenbot.view.css` (`CssTheme`, `LayoutStyles`, `ComponentStyles`, `TableStyles`, `FormStyles`, `NavigationStyles`, `MediaQueries`, `CssStyles` facade).
- **Type-Safe Selectors**: Use `querySelector` extension properties on `CssClass` rather than string replacements (e.g. `.replace(" ", ".")`).
- **Strongly Typed `kotlinx-css` Properties**: Use typed properties and `CssTheme` color tokens rather than raw `put(...)` calls or hardcoded hex colors.
- **Documentation & Build Sync**: Whenever adding, moving, or deleting packages under `src/main/kotlin/`, immediately update:
  1. The project structure directory tree in `README.md`.
  2. JaCoCo coverage exclusion filters in `build.gradle.kts` (`tasks.jacocoTestReport` and `tasks.jacocoTestCoverageVerification`).

## Anti-Pattern Detection Script

Execute `.agents/skills/kotlin-refactoring-and-cleanup/scripts/find_anti_patterns.sh` to scan the codebase for inline FQNs, magic strings, and absolute paths (`/Users/`):

```bash
./.agents/skills/kotlin-refactoring-and-cleanup/scripts/find_anti_patterns.sh
```

## Refactoring Checklist

Before declaring a refactoring task complete:

- [ ] All FQNs replaced with explicit imports
- [ ] Raw strings/magic numbers replaced with type-safe constants/enums
- [ ] No compiler warnings or unused imports remain
- [ ] No hardcoded user paths (`/Users/...`) present
- [ ] Directory tree in `README.md` updated for any package or layout changes
- [ ] JaCoCo exclusions in `build.gradle.kts` updated for any new non-tested view/DSL packages
- [ ] Automated tests executed (`./gradlew test :frontend-js:jsTest`) to verify zero regressions
- [ ] Markdown files formatted and validated (`npx markdownlint-cli`)
