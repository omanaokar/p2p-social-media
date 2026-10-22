import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin/JVM so it is testable without a device. Every dependency here
// also runs on Android; the Android app adds the same sqlite-bundled driver.
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
    api(libs.sqlite)
    implementation(libs.serialization.json)
    implementation(libs.bouncycastle)

    testImplementation(kotlin("test"))
    testImplementation(libs.sqlite.bundled)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
