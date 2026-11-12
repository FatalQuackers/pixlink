plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.pixlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.pixlink"
        minSdk = 33          // Android 13 — every supported Pixel runs 14+
        targetSdk = 35
        // CI passes the version from the release tag (v1.2.3 → 1.2.3) and the build number as versionCode.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.0.0-dev"
    }

    // Release builds are signed with the permanent Pixlink key from GitHub secrets, so updates
    // install over the previous version. Without the key (local builds), the debug key is used.
    signingConfigs {
        create("pixlink") {
            val keystore = project.findProperty("pixlinkKeystore") as String?
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = project.findProperty("pixlinkKeystorePassword") as String?
                keyAlias = "pixlink"
                keyPassword = project.findProperty("pixlinkKeystorePassword") as String?
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinking stays off: Shizuku and the hidden-API calls rely on reflection.
            isMinifyEnabled = false
            signingConfig = if (project.hasProperty("pixlinkKeystore")) signingConfigs.getByName("pixlink")
                            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // QR scanning
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Power mode
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
