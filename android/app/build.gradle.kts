plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.flowassist"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.flowassist"
        minSdk = 26                       // TYPE_APPLICATION_OVERLAY + dispatchGesture
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-manual"
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // The solver module carries a desktop-only CLI (flow.desktop uses ImageIO, which does not
    // exist on Android). Those classes are never loaded on a phone; keep them out of the APK.
    packaging {
        resources.excludes += setOf("META-INF/*")
    }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":solver"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
