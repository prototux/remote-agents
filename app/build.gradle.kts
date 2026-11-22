plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.prototux.remoteagents"
    compileSdk = 37
    defaultConfig {
        applicationId = "net.prototux.remoteagents"
        minSdk = 31
        targetSdk = 37
        // CI passes the tag and run number (-PappVersion=1.0.0 -PappVersionCode=42); local builds stay 0.0.0-dev.
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("appVersion") as String?) ?: "0.0.0-dev"
    }
    // Release signing only when the keystore is provided (CI secrets or a local env); never committed.
    val keystore = System.getenv("RA_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = System.getenv("RA_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RA_KEY_ALIAS")
            keyPassword = System.getenv("RA_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    testOptions { unitTests.all { it.maxHeapSize = "1g" } }
}

dependencies {
    implementation(projects.api)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.embedded)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
