import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM library. No Android SDK, no external ML deps.
// Only depends on :contracts (which api-exposes coroutines-core).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    api(project(":contracts"))

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
