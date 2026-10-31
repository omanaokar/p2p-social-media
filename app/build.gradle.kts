plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.proximity.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.proximity.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":app-core"))
    implementation(project(":transport"))
    implementation(libs.sqlite.bundled)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.nearby)
}
