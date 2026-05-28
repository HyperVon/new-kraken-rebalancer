import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.21"
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
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Jackson BOM — pins jackson-core & jackson-databind to a secure, explicit version
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.3"))

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
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.33")

    // Coroutines
    val kotlinXCoroutinesVersion = "1.11.0"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${kotlinXCoroutinesVersion}")

    // Testing
    val koTestVersion = "6.1.11"
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${kotlinXCoroutinesVersion}")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("io.insert-koin:koin-test:${koinVersion}")
    testImplementation("io.mockk:mockk:1.14.9")
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
                exclude("**/KrakenRebalancerApplication*")
            }
        })
    )
}

tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude("**/model/**")
                exclude("**/config/**")
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
                minimum = "0.95".toBigDecimal()
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
            useVersion("4.1.134.Final")
            because("Fixes Netty security vulnerabilities including HTTP/2 continuation frame flood (CVE-2026-33871)")
        }
    }
}
