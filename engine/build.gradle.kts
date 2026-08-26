import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    jacoco
}

repositories {
    mavenCentral()
}

dependencies {
    ksp(project(":codegen"))
    compileOnly(project(":codegen"))
    implementation(project(":common"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

kotlin {
    jvmToolchain(25)
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
    jvmArgs("-Xshare:off", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
}

val coverageExcludes =
    listOf(
        "**/api/*ApiMapperKt*",
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
