plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.hex.stenographer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hex.stenographer"
        minSdk = 26; targetSdk = 35
        versionCode = 1; versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("onnx", "gguf", "task", "txt") }
    packaging { jniLibs.useLegacyPackaging = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Локальная LLM (Gemma .task) через MediaPipe
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    // sherpa-onnx: .so лежат в src/main/jniLibs/arm64-v8a, Kotlin API — в пакете com.k2fsa.sherpa.onnx
}
