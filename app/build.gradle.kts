plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.livewire.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.livewire.tv"
        minSdk = 23          // Android 6 — covers virtually all Android TV / Fire TV in use
        targetSdk = 35
        // Version is injected by CI from the git tag (-PversionName / -PversionCode);
        // these defaults apply to local/dev builds.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing. Config is populated ONLY when the keystore env vars are present
    // (set by CI from encrypted secrets); otherwise release builds are left unsigned
    // so local/CI-without-secrets builds still succeed. No key material is ever hardcoded.
    val ksPath = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    val hasSigning = ksPath != null && file(ksPath).exists()
    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Distinct id so a debug/test build can be installed ALONGSIDE a release
            // build (different signing keys otherwise force an uninstall). -> com.livewire.tv.debug
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Pin the compile toolchain to JDK 17 so the build is reproducible regardless of the
// ambient `java` on PATH (this host defaults to JDK 25, which Gradle 8.9 rejects).
// Gradle auto-detects the JDK 17 registered via org.gradle.java.installations.paths.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM-managed)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Compose for TV (D-pad focus, TV components)
    implementation(libs.androidx.tv.material)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Player — Media3 / ExoPlayer (hardware decode by default) + HLS
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Storage
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Images
    implementation(libs.coil.compose)

    // Networking + JSON
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines.android)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kxml2)          // XmlPullParser impl for JVM-side XMLTV parser tests
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
