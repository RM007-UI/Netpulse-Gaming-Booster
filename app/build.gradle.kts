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
        versionCode = 8
        versionName = "0.5.1"
        ndk { abiFilters.add("arm64-v8a") }
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
    testImplementation("junit:junit:4.13.2")
    // Android's mockable android.jar stubs org.json methods during local JVM tests.
    // Use the reference JSON implementation so profile-import tests exercise real serialization.
    testImplementation("org.json:json:20260814")
}
