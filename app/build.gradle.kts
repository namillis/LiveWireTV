plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.livewire.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.livewire.tv"
        minSdk = 23          // Android 6 — covers virtually all Android TV / Fire TV in use
        targetSdk = 35
        // Version is injected by CI from the git tag (-PversionName / -PversionCode);
        // these defaults apply to local/dev builds.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // --- Self-update configuration --------------------------------------------
        // The update feature reads GitHub Releases for the app package. Owner/name and
        // the asset naming go in BuildConfig so forks and the local e2e test can point
        // elsewhere (-PupdateRepoOwner, -PupdateRepoName, -PupdateApiBase, ...).
        val updateRepoOwner = (project.findProperty("updateRepoOwner") as String?) ?: "namillis"
        val updateRepoName = (project.findProperty("updateRepoName") as String?) ?: "LiveWireTV"
        // API base for the release lookup. Real builds hit GitHub; the emulator e2e
        // test overrides this with -PupdateApiBase=http://127.0.0.1:8765 (adb reverse).
        val updateApiBase = (project.findProperty("updateApiBase") as String?) ?: "https://api.github.com"
        // Downloaded asset filename pattern; %s is the versionName (e.g. livewire-0.1.4.apk).
        val updateAssetPattern = (project.findProperty("updateAssetPattern") as String?) ?: "livewire-%s.apk"
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"$updateRepoOwner\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"$updateRepoName\"")
        buildConfigField("String", "UPDATE_API_BASE", "\"$updateApiBase\"")
        buildConfigField("String", "UPDATE_ASSET_PATTERN", "\"$updateAssetPattern\"")
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
        // UPDATES_ENABLED defaults to false for debug and true for release; either can be
        // forced with -PupdatesEnabled=true|false (the e2e test builds debug APKs with it on).
        val updatesEnabledOverride = (project.findProperty("updatesEnabled") as String?)?.toBooleanStrictOrNull()
        // Extra id suffix for throwaway installs (e.g. -PappIdSuffix=.updtest), appended after
        // the build-type suffix so it installs beside the release and normal debug apps.
        val extraIdSuffix = (project.findProperty("appIdSuffix") as String?)?.takeIf { it.isNotBlank() }

        debug {
            // Distinct id so a debug/test build can be installed ALONGSIDE a release
            // build (different signing keys otherwise force an uninstall). -> com.livewire.tv.debug
            applicationIdSuffix = ".debug" + (extraIdSuffix ?: "")
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "UPDATES_ENABLED", (updatesEnabledOverride ?: false).toString())
        }
        release {
            extraIdSuffix?.let { applicationIdSuffix = it }
            buildConfigField("boolean", "UPDATES_ENABLED", (updatesEnabledOverride ?: true).toString())
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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Pin the compile toolchain to JDK 17 so the build is reproducible regardless of the
// ambient `java` on PATH (some hosts default to a newer JDK than this project targets).
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
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kxml2)          // XmlPullParser impl for JVM-side XMLTV parser tests
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
