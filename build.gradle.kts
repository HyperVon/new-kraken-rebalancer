import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    id("com.diffplug.spotless") version "8.9.0"
    application
    jacoco
}

spotless {
    kotlin {
        target("src/**/*.kt", "common/src/**/*.kt", "frontend-js/src/**/*.kt")
        ktlint("1.7.1").editorConfigOverride(
            mapOf(
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "ktlint_standard_backing-property-naming" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.7.1").editorConfigOverride(
            mapOf(
                "max_line_length" to "120",
            ),
        )
    }
}

// Spotless reads under src/; avoid parallel races with resource copy (Gradle 9 validation).
tasks.named("spotlessKotlin") {
    mustRunAfter("copyJsBundle")
}

group = "com.gemini"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.gemini.krakenbot.KrakenRebalancerApplicationKt")
    applicationDefaultJvmArgs =
        listOf("-Xshare:off", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

repositories {
    mavenCentral()
}

dependencies {
    val ktorVersion = "3.5.1"
    val koinVersion = "4.2.2"

    implementation(project(":common"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Jackson BOM — pins jackson-core & jackson-databind to a secure, explicit version
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.1"))
    implementation("io.ktor:ktor-server-caching-headers:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-conditional-headers:$ktorVersion")

    // Koin
    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation("io.insert-koin:koin-logger-slf4j:$koinVersion")
    implementation("io.insert-koin:koin-ktor:$koinVersion")

    // Ktor Server & Client
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.6.1")

    // Coroutines
    val kotlinXCoroutinesVersion = "1.11.0"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinXCoroutinesVersion")

    // SQLite + Exposed ORM
    val exposedVersion = "1.3.1"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("org.jetbrains.kotlin-wrappers:kotlin-css-jvm:2026.7.7")

    // Testing
    val koTestVersion = "6.2.3"
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinXCoroutinesVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("io.insert-koin:koin-test:$koinVersion")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("io.kotest:kotest-runner-junit5:$koTestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$koTestVersion")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        allWarningsAsErrors.set(true)
    }
}

// A `--tests` run only exercises a slice of the codebase, so the project-wide JaCoCo
// thresholds can never be met and would fail an otherwise-green focused run. Full runs
// (`./gradlew test`, `./gradlew build`) still finalize with report + verification.
val isFilteredTestRun =
    gradle.startParameter.taskRequests.any { request -> request.args.contains("--tests") }

tasks.withType<Test> {
    useJUnitPlatform()
    if (!isFilteredTestRun) {
        finalizedBy(tasks.jacocoTestReport)
        finalizedBy(tasks.jacocoTestCoverageVerification)
    }
    maxParallelForks =
        providers.gradleProperty("testForks").orNull?.toIntOrNull()?.coerceAtLeast(1)
            ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)
    maxHeapSize = providers.gradleProperty("testMaxHeap").orElse("2g").get()
    jvmArgs("-Xshare:off", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
    systemProperty("kotlinx.coroutines.debug.enable.creation.stack.trace", "false")
    systemProperty("kotest.coroutines.debug.disable", "true")
    systemProperty("kraken.db.path", ":memory:")
}

// Single source of truth for JaCoCo coverage exclusions — kept in sync across the
// report and verification tasks (see .agents/skills/gradle-quality-gates).
val coverageExcludes =
    listOf(
        // Framework/bootstrap code and generated HTML DSL lambdas remain impractical
        // to exercise to the same 95/90 bundle thresholds; tested helpers now count.
        "**/config/DatabaseConfig*",
        "**/config/KtorConfigKt*",
        "**/repository/table/**",
        "**/service/KrakenService*",
        "**/service/impl/KrakenServiceImpl*",
        "**/view/util/HtmlExtensionsKt*",
        "**/view/css/**",
        "**/KrakenRebalancerApplication*",
    )

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
    }
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) { exclude(coverageExcludes) }
            },
        ),
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.classes)
    mustRunAfter(tasks.jacocoTestReport)
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) { exclude(coverageExcludes) }
            },
        ),
    )
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
            limit {
                counter = "METHOD"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
    dependsOn(":frontend-js:jsBrowserTest")
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.netty") {
            useVersion("4.1.136.Final")
            because(
                "Fixes Netty security vulnerabilities including HTTP/2 continuation frame flood (CVE-2026-33871) and newer vulnerabilities (CVE-2026-45536, CVE-2026-45416, CVE-2026-44249)",
            )
        }
    }
}

tasks.register<Jar>("fatJar") {
    description = "Build fat jar"
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.gemini.krakenbot.KrakenRebalancerApplicationKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath
            .get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}

val copyJsBundle =
    tasks.register<Copy>("copyJsBundle") {
        description = "Copy JS bundle to resources"
        dependsOn(":frontend-js:jsBrowserProductionWebpack")
        from(project(":frontend-js").layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable"))
        into(layout.projectDirectory.dir("src/main/resources/static"))
        include("*.js")
        rename { "rebalancer.js" }
    }

tasks.processResources {
    dependsOn(copyJsBundle)
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
    }
}
