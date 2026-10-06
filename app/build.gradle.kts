plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.periy.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.periy.bridge"
        // 29 = Android 10. Below this, scoped storage / SAF behaviour diverges enough
        // that the storage layer would need a second implementation.
        minSdk = 29
        targetSdk = 36
        // WebRTC carries native code for each kind of processor; only 64-bit ARM, which every
        // phone this app is for has, keeps the app about 10 MB larger instead of 40.
        ndk { abiFilters += listOf("arm64-v8a") }
        versionCode = 32
        versionName = "2.12.0"

        // Default listen port.
        buildConfigField("int", "DEFAULT_PORT", "8787")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        // The test build: smooth as a release, since the phone's runtime compiles an app that isn't
        // debuggable properly and applies Compose's startup profiles (a debug build does neither,
        // and drops frames in every animation). Signed with the debug key and installed as the
        // debug app, so it goes over a debug build. Not shrunk, so nothing can go missing at runtime.
        create("fast") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        release {
            // Ktor + kotlinx.serialization both need keep rules; see proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Ktor and kotlinx.io reach for a handful of java.time / java.nio APIs that do
        // not exist on API 29. Desugaring backfills them; without this the app crashes
        // on first request on Android 10/11 devices.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    // Photos sent in messages are turned upright from their EXIF orientation.
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.json)

    implementation(libs.zxing.core)
    implementation(libs.webrtc)
    implementation(libs.androidx.core.telecom)
    // Ktor logs through SLF4J. Without a binding every request logs a "no provider"
    // warning; slf4j-simple writes to System.err, which Android routes into logcat.
    implementation(libs.slf4j.simple)
}
