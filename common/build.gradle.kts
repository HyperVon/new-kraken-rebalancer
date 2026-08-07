import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp") version "2.3.11"
}

repositories {
    mavenCentral()
}

dependencies {
    add("kspCommonMainMetadata", project(":codegen"))
}

ksp {
    arg("codegenResourceRoot", layout.projectDirectory.dir("src/commonMain/resources").asFile.absolutePath)
}

kotlin {
    jvmToolchain(25)
    jvm()
    js {
        browser()
    }
    sourceSets {
        getByName("commonMain") {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn("kspCommonMainKotlinMetadata")
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

tasks.configureEach {
    if (name == "kspCommonMainKotlinMetadata") {
        inputs.files(fileTree("src/commonMain/resources/codegen") { include("*.yaml") })
    }
}

// Gradle 9 validation: ksp* tasks consume the metadata output without an explicit
// dependency, so declare it. Required for KSP 2.3.11 (see CI-28-C05).
tasks.configureEach {
    if (name == "kspKotlinJvm" || name == "kspKotlinJs") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}
