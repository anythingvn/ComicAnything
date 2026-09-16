import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing credentials live in local.properties (gitignored, never committed).
// If they're absent (e.g. a fresh checkout or CI without secrets), the release build
// type falls back to unsigned rather than failing configuration.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE")
val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
val hasReleaseSigningConfig = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.comicanything.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.comicanything.reader"
        minSdk = 24
        targetSdk = 34

        // Versioning convention (Epic 9): semantic versioning for versionName
        // (MAJOR.MINOR.PATCH — MAJOR for breaking/incompatible data changes, MINOR for new
        // user-facing features, PATCH for fixes only), and versionCode as a plain integer that
        // must strictly increase on every build published to the Play Store (Play Store rejects
        // a re-upload with a versionCode <= the last published one — it does not need to track
        // versionName in any particular way, just monotonically increase). Bump both together
        // when cutting a release: e.g. 1.0.0 -> 1.1.0 for a feature release is versionCode 1 -> 2.
        versionCode = 9
        versionName = "1.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX & Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose & Material 3 (Google Native UI)
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Image & Media Loading (Coil for Compose)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // WebView asset loading (EPUB rendering)
    // NOTE: pinned to 1.15.0, not the newer 1.16.0 -- 1.16.0's POM directly depends on
    // kotlin-stdlib 2.1.20, whose class metadata (format 2.1.0) this project's Kotlin Gradle
    // Plugin (1.9.22, compiler can read up to format 2.0.0) cannot read, breaking
    // compileDebugKotlin project-wide. 1.15.0 is a pure-Java artifact (no Kotlin dependency)
    // and exposes the same WebViewAssetLoader/WebViewClientCompat APIs used here.
    implementation("androidx.webkit:webkit:1.15.0")

    // Networking & HTTP (For Google Drive REST API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Local JSON / Storage
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("com.github.junrar:junrar:8.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
