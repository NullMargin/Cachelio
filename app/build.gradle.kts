plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Signing key is provided via environment variables (fed by GitHub Secrets in CI).
// Nothing sensitive is committed to the repo. When the variables are absent
// (e.g. local builds or fork PRs) the build falls back to the default debug key.
val vbrowserKeystorePath = System.getenv("VBROWSER_KEYSTORE").orEmpty().takeIf { it.isNotBlank() }
val vbrowserKeystoreFile = vbrowserKeystorePath?.let { file(it) }
val vbrowserStorePassword: String? = System.getenv("VBROWSER_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val vbrowserKeyAlias: String? = System.getenv("VBROWSER_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val vbrowserKeyPassword: String? = System.getenv("VBROWSER_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasStableSigning = vbrowserKeystoreFile?.exists() == true &&
    !vbrowserStorePassword.isNullOrBlank() &&
    !vbrowserKeyAlias.isNullOrBlank() &&
    !vbrowserKeyPassword.isNullOrBlank()

android {
    namespace = "com.holeintimes.vbrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.holeintimes.vbrowser"
        minSdk = 26
        targetSdk = 35
        versionCode = 203
        versionName = "2.0.3"
    }

    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = vbrowserKeystoreFile
                storePassword = vbrowserStorePassword
                keyAlias = vbrowserKeyAlias
                keyPassword = vbrowserKeyPassword
            }
        }
    }

    buildTypes {
        // Use the secret-provided key when available; otherwise fall back to the
        // default debug keystore so local builds and fork PRs still succeed.
        val stable = signingConfigs.findByName("stable")

        debug {
            if (stable != null) signingConfig = stable
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (stable != null) signingConfig = stable
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("org.nanohttpd:nanohttpd:2.3.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
