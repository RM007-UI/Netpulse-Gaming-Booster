plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.rmgb.netpulse"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.rmgb.netpulse"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("com.github.singbox-android:libbox:1.14.4")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
