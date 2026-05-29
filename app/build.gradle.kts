import java.util.Base64

// Load .env for integration test credentials (not checked in)
val envFile = rootProject.file(".env")
val env = if (envFile.exists()) {
    envFile.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key.trim() to value.trim().removeSurrounding("\"")
        }
} else emptyMap()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.yage.opencode_client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yage.opencode_client"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.1.20260526"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Integration test credentials from .env (dynamic, not in code)
        testInstrumentationRunnerArguments["openCodeServerUrl"] = env["OPENCODE_SERVER_URL"] ?: ""
        testInstrumentationRunnerArguments["openCodeUsername"] = env["OPENCODE_USERNAME"] ?: ""
        testInstrumentationRunnerArguments["openCodePassword"] = env["OPENCODE_PASSWORD"] ?: ""
        testInstrumentationRunnerArguments["aiBuilderBaseUrl"] = env["AI_BUILDER_BASE_URL"] ?: ""
        testInstrumentationRunnerArguments["aiBuilderToken"] = env["AI_BUILDER_TOKEN"] ?: ""
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.security.crypto)
    
    // VoiceFlowKit: realtime speech transcription pipeline, consumed remotely from
    // grapeot/voiceflow-android via JitPack (com.github.<user>:<repo>:<tag>).
    implementation("com.github.grapeot:voiceflow-android:0.1.0")

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
