import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

repositories {
    mavenCentral()
}

spotless {
    kotlin {
        target(
            "backend/src/**/*.kt",
            "common/src/**/*.kt",
            "frontend-js/src/**/*.kt",
            "codegen/src/**/*.kt",
            "engine/src/**/*.kt",
        )
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
            mapOf(
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "ktlint_standard_backing-property-naming" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts", "backend/*.gradle.kts")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(
            mapOf(
                "max_line_length" to "120",
            ),
        )
    }
}

// Spotless reads under backend/src; avoid parallel races with resource copy (Gradle 9 validation).
tasks.named("spotlessKotlin") {
    mustRunAfter(":backend:copyJsBundle")
}

group = "com.gemini"
version = "0.0.1-SNAPSHOT"

// Aggregate verification: backend JVM coverage + engine coverage + frontend JS browser tests
tasks.named("build") {
    dependsOn(":backend:build")
    dependsOn(":engine:build")
}

tasks.named("check") {
    dependsOn(":backend:check")
    dependsOn(":engine:check")
    dependsOn(":frontend-js:jsBrowserTest")
}

// Preserve `./gradlew run` at repo root (delegates to backend)
tasks.register("run") {
    group = "application"
    description = "Delegates to :backend:run (workingDir = repo root for rebalancer-config.json lookup)"
    dependsOn(":backend:run")
}

rootProject.plugins.withType<YarnPlugin> {
    rootProject.extensions.configure<YarnRootExtension> {
        // Bounded ranges keep Yarn 1 quiet while retaining the patched security floor;
        // kotlin-js-store/yarn.lock pins the resolved versions for reproducible builds.
        resolution("webpack-dev-server", ">=6.0.0 <7.0.0")
        // Dependabot #140 / GHSA-g84c-rxfj-3j2c — path traversal in webpack-dev-middleware.
        resolution("webpack-dev-middleware", ">=8.3.0 <9.0.0")
        // Dependabot #192 — unescaped `</script` at the end of a serialized function body; patched in 7.1.3.
        resolution("serialize-javascript", ">=7.1.3 <8.0.0")
        resolution("uuid", ">=14.0.1 <15.0.0")
        resolution("webpack", ">=5.109.2 <6.0.0")
        resolution("diff", ">=9.0.0 <10.0.0")
        // Dependabot #188-#191 — URI scheme spoofing and reference-normalization bugs; patched in 4.2.3.
        resolution("fast-uri", ">=4.2.3 <5.0.0")
        // Dependabot #186-#187 — trailing-bytes denial of service. Karma pins body-parser 1.x
        // (patched in 1.20.9) while Express 5 pins 2.x (patched in 2.4.0), so scope by parent.
        resolution("**/karma/body-parser", ">=1.20.9 <2.0.0")
        resolution("**/express/body-parser", ">=2.4.0 <3.0.0")
        // Dependabot #171-#173 — Windows path checks and editor-flag handling; patched in 2.14.2.
        resolution("launch-editor", ">=2.14.2 <3.0.0")
        // Dependabot #169-#170 — cross-origin credential-header filtering; patched in 1.16.1.
        resolution("follow-redirects", ">=1.16.1 <2.0.0")
        // Body-parser 2.4.0 requires qs ^6.16.1; keep the floor at the latest 6.16 patch.
        resolution("qs", ">=6.16.1 <7.0.0")
        // Dependabot #143 — Engine.IO protocol revision mismatch DoS
        resolution("engine.io", ">=6.6.10 <7.0.0")
        // Dependabot #144-#146 — recursion and quadratic-time expansion denial of service
        resolution("brace-expansion", ">=5.0.12 <6.0.0")
        // Dependabot #105 / GHSA-5p4m-2wfm-xmqj — quadratic CPU in !!omap resolution
        // Dependabot #136 — empty merge sources bypass maxTotalMergeKeys
        resolution("js-yaml", ">=4.3.2 <5.0.0")
        // Dependabot #159 — shell-quote < 1.11.0 improper neutralization of shell metacharacters
        resolution("shell-quote", ">=1.11.0 <2.0.0")
        // Dependabot #158 — source-map-js < 1.2.2 regular-expression denial of service
        resolution("source-map-js", ">=1.2.2 <2.0.0")
        // Dependabot #157 — proxy-addr < 2.0.8 proxy-addr trusted-proxy parsing
        resolution("proxy-addr", ">=2.0.8 <3.0.0")
        // Dependabot #156 — compression < 1.8.2 response body handling
        resolution("compression", ">=1.8.2 <2.0.0")
    }
}
