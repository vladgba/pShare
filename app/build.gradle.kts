import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Shared versioning (version.properties) + release signing, see gradle/android-common.gradle
apply(from = rootProject.file("gradle/android-common.gradle"))

android {
    namespace = "x.vladgba.share"
    compileSdk = 35

    defaultConfig {
        applicationId = "x.vladgba.share"
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "x.vladgba.share.FolderTransferInstrumentation"
    }

    buildTypes {
        debug {
            // Keep device verification separate from an installed release and its data.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // QR encode/decode.
    implementation("com.google.zxing:core:3.5.3")

    // Honeywell AIDC hardware scanner SDK (for EDA51 / Mobility Edge devices)
    implementation(files("libs/DataCollection.jar"))

    // Unit tests only (not shipped in the APK).
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar only has stubs); P2P integration test.
    testImplementation("org.json:json:20240303")
}
