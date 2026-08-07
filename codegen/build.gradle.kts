import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")
    implementation(libs.snakeyaml)
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}
