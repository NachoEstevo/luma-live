import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val datLocal = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val datDeveloperMode = providers.gradleProperty("datDeveloperMode").orNull == "true"
android {
    namespace = "com.lumalive.probe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.lumalive.probe"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.1"
        manifestPlaceholders["datApplicationId"] = if (datDeveloperMode) "0" else datLocal.getProperty("mwdat.applicationId", "0")
        manifestPlaceholders["datClientToken"] = if (datDeveloperMode) "0" else datLocal.getProperty("mwdat.clientToken", "0")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    // Activity Result owns Android and DAT permission redirects; lifecycle owns UI observation.
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("com.meta.wearable:mwdat-core:1.0.1")
    implementation("com.meta.wearable:mwdat-camera:1.0.1")
    // On-glasses recognition avoids sending silent/command audio to cloud ASR.
    implementation("com.meta.wearable:mwdat-speech:1.0.1")
    testImplementation("junit:junit:4.13.2")
    // Test-only: virtual time and cancellation regression tests; matches DAT's resolved runtime.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
