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

// Aggregate verification: backend JVM coverage + frontend JS browser tests
tasks.named("build") {
    dependsOn(":backend:build")
}

tasks.named("check") {
    dependsOn(":backend:check")
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
        resolution("serialize-javascript", ">=7.0.7 <8.0.0")
        resolution("uuid", ">=14.0.1 <15.0.0")
        resolution("webpack", ">=5.109.2 <6.0.0")
        resolution("diff", ">=9.0.0 <10.0.0")
        resolution("fast-uri", ">=4.1.1 <5.0.0")
        // Dependabot #102 / CVE-2026-14257 — DoS via unbounded expansion length
        resolution("brace-expansion", ">=5.0.9 <6.0.0")
        // Dependabot #105 / GHSA-5p4m-2wfm-xmqj — quadratic CPU in !!omap resolution
        resolution("js-yaml", ">=4.3.1 <5.0.0")
    }
}
