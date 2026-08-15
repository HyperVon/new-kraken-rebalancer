import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.ksp)
    id("io.kotest") version libs.versions.kotest.get()
}

repositories {
    mavenCentral()
}

kotlin {
    js {
        browser {
            binaries.executable()
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }
    sourceSets {
        getByName("jsMain") {
            dependencies {
                implementation(project(":common"))
                implementation(npm("tslib", "2.8.1"))
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.framework.engine)
            }
        }
        getByName("jsTest") {
            dependencies {
                implementation(libs.kotest.framework.engine)
                implementation(devNpm("karma-coverage", "2.2.1"))
                implementation(devNpm("@jsdevtools/coverage-istanbul-loader", "3.0.5"))
            }
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
