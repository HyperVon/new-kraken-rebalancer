import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.0"
    application
    jacoco
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
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Jackson BOM — pins jackson-core & jackson-databind to a secure, explicit version
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.0"))

    // Koin
    var koinVersion = "4.2.1"
    implementation("io.insert-koin:koin-core:${koinVersion}")
    implementation("io.insert-koin:koin-logger-slf4j:${koinVersion}")
    implementation("io.insert-koin:koin-ktor:${koinVersion}")

    // Ktor Server & Client
    val ktorVersion = "3.5.0"
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
    implementation("ch.qos.logback:logback-classic:1.5.34")

    // Coroutines
    val kotlinXCoroutinesVersion = "1.11.0"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${kotlinXCoroutinesVersion}")

    // SQLite + Exposed ORM
    val exposedVersion = "0.61.0"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    // Testing
    val koTestVersion = "6.1.11"
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${kotlinXCoroutinesVersion}")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("io.insert-koin:koin-test:${koinVersion}")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("io.kotest:kotest-runner-junit5:${koTestVersion}")
    testImplementation("io.kotest:kotest-assertions-core:${koTestVersion}")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    finalizedBy(tasks.jacocoTestCoverageVerification)
    jvmArgs("-Xshare:off", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED", "-Xmx4096m")
    systemProperty("kotlinx.coroutines.debug.enable.creation.stack.trace", "false")
    systemProperty("kotest.coroutines.debug.disable", "true")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude("**/model/**")
                exclude("**/config/**")
                exclude("**/repository/table/**")
                exclude("**/service/KrakenService*")
                exclude("**/KrakenRebalancerApplication*")
            }
        })
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.classes)
    mustRunAfter(tasks.jacocoTestReport)
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude("**/model/**")
                exclude("**/config/**")
                exclude("**/repository/table/**")
                exclude("**/service/KrakenService*")
                exclude("**/KrakenRebalancerApplication*")
            }
        })
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
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.netty") {
            useVersion("4.1.135.Final")
            because("Fixes Netty security vulnerabilities including HTTP/2 continuation frame flood (CVE-2026-33871) and newer vulnerabilities (CVE-2026-45536, CVE-2026-45416, CVE-2026-44249)")
        }
    }
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.gemini.krakenbot.KrakenRebalancerApplicationKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}
