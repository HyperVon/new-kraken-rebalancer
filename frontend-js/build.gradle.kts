plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp") version "2.3.9"
    id("io.kotest") version "6.1.11"
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
        val jsMain by getting {
            dependencies {
                implementation(project(":common"))
                implementation(npm("tslib", "2.6.2"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("io.kotest:kotest-assertions-core:6.1.11")
                implementation("io.kotest:kotest-framework-engine:6.1.11")
            }
        }
        val jsTest by getting {
            dependencies {
                implementation("io.kotest:kotest-framework-engine:6.1.11")
                implementation(devNpm("karma-coverage", "2.2.1"))
                implementation(devNpm("@jsdevtools/coverage-istanbul-loader", "3.0.5"))
            }
        }

    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
