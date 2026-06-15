import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Release signing — three-tier precedence (most-secure first):
//
//   1. Environment variables  (preferred for CI / secure build machines)
//      EMUTRAN_KEYSTORE_FILE      – absolute path to the .jks / .keystore
//      EMUTRAN_KEYSTORE_PASSWORD  – store password
//      EMUTRAN_KEY_ALIAS          – key alias inside the store
//      EMUTRAN_KEY_PASSWORD       – key password
//      All four must be present and non-blank for this tier to activate.
//      Advantage: the password never touches disk — it lives only in the
//      secure environment of the CI runner or developer's shell session.
//
//   2. keystore.properties at the project root  (local developer workflow)
//      The file is gitignored — never committed. When env vars are absent
//      (typical local-dev case) Gradle reads the plaintext file instead.
//
//   3. Debug signing fallback  (loud warning — not for distribution)
//      If neither source provides a key, the release build is signed with
//      the debug keystore. A configuration-phase warning is emitted.
// ---------------------------------------------------------------------------

// Check env-var tier first.
val envKeystoreFile     = System.getenv("EMUTRAN_KEYSTORE_FILE")
val envKeystorePassword = System.getenv("EMUTRAN_KEYSTORE_PASSWORD")
val envKeyAlias         = System.getenv("EMUTRAN_KEY_ALIAS")
val envKeyPassword      = System.getenv("EMUTRAN_KEY_PASSWORD")
val hasEnvKey = listOf(envKeystoreFile, envKeystorePassword, envKeyAlias, envKeyPassword)
    .all { !it.isNullOrBlank() }

// Fallback: load keystore.properties from the project root.
val keystoreProps = Properties().apply {
    if (!hasEnvKey) {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
}
val hasPropsKey = !hasEnvKey && keystoreProps.getProperty("storeFile")?.isNotBlank() == true

val hasReleaseKey = hasEnvKey || hasPropsKey

android {
    namespace = "io.github.mayusi.emutran"
    compileSdk = 35              // androidx.core 1.15 + work 2.10 require ≥35

    defaultConfig {
        applicationId = "io.github.mayusi.emutran"
        minSdk = 29              // Android 10 — earliest still in active use on handhelds
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            // ARM64 only. Every supported handheld is aarch64.
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                // Tier 1: environment variables (CI / secure build machines).
                // Tier 2: keystore.properties on disk (local developer workflow).
                storeFile = file(
                    if (hasEnvKey) envKeystoreFile!!
                    else keystoreProps.getProperty("storeFile")
                )
                storePassword = if (hasEnvKey) envKeystorePassword
                                else keystoreProps.getProperty("storePassword")
                keyAlias      = if (hasEnvKey) envKeyAlias
                                else keystoreProps.getProperty("keyAlias")
                keyPassword   = if (hasEnvKey) envKeyPassword
                                else keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("keystore.properties missing — release build will be debug-signed.")
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // .debug suffix lets debug + release coexist on the same
            // device. We hide the dev test screen behind BuildConfig.DEBUG
            // so it never ships in release APKs.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG drives whether dev-only UI (test install
        // shortcut, etc.) renders. Not on by default in modern AGP.
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Networking
    implementation(libs.okhttp)
    // Logging interceptor — debug builds only; JAR is excluded from release APKs.
    // Verified: no main/ source file imports HttpLoggingInterceptor directly.
    debugImplementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Storage
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.mockk.android)
}
