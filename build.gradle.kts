import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    id("com.diffplug.spotless") version "8.8.0"
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
    implementation("ch.qos.logback:logback-classic:1.6.0")

    // Coroutines
    val kotlinXCoroutinesVersion = "1.11.0"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinXCoroutinesVersion")

    // SQLite + Exposed ORM
    val exposedVersion = "1.3.1"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("org.jetbrains.kotlin-wrappers:kotlin-css-jvm:2026.7.5")

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

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    finalizedBy(tasks.jacocoTestCoverageVerification)
    jvmArgs("-Xshare:off", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED", "-Xmx4096m")
    systemProperty("kotlinx.coroutines.debug.enable.creation.stack.trace", "false")
    systemProperty("kotest.coroutines.debug.disable", "true")
    systemProperty("kraken.db.path", ":memory:")
}

// Single source of truth for JaCoCo coverage exclusions — kept in sync across the
// report and verification tasks (see .agents/skills/gradle-quality-gates).
val coverageExcludes =
    listOf(
        "**/config/**",
        "**/repository/table/**",
        "**/service/KrakenService*",
        "**/service/impl/KrakenServiceImpl*",
        "**/view/util/**",
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
        resolution("webpack-dev-server", "5.2.6")
        resolution("serialize-javascript", "7.0.7")
        resolution("uuid", "11.1.1")
        resolution("webpack", "5.109.0")
        resolution("diff", "8.0.4")
        resolution("fast-uri", "3.1.4")
    }
}
