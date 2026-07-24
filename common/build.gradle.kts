import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    jvm()
    js {
        browser()
    }
    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
