import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val ksFile = rootProject.file(localProps.getProperty("KEYSTORE_PROPERTIES") ?: System.getenv("KEYSTORE_PROPERTIES") ?: "../keystore/keystore.properties")
val ks = Properties().apply { if (ksFile.exists()) ksFile.inputStream().use { load(it) } }
fun lp(key: String, def: String) = (localProps.getProperty(key) ?: System.getenv(key) ?: def)

android {
    namespace = "com.kidtoy"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kidtoy.toyforge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // DEV ONLY: key is baked into the debug APK. Never ship this to Play Store - use a backend proxy.
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${lp("ANTHROPIC_API_KEY", "")}\"")
        buildConfigField("String", "ANTHROPIC_MODEL", "\"${lp("ANTHROPIC_MODEL", "claude-sonnet-5-5")}\"")
        buildConfigField("String", "AI_PROXY_URL", "\"${lp("AI_PROXY_URL", "")}\"")
        buildConfigField("boolean", "AI_MOCK", lp("AI_MOCK", "false"))
        buildConfigField("String", "SI_API_KEY", "\"${lp("SI_API_KEY", "")}\"")
    }
    // Release signing: keystore lives OUTSIDE the repo (path overridable with KEYSTORE_PROPERTIES in local.properties).
    signingConfigs {
        if (ks.getProperty("storeFile") != null) create("release") {
            storeFile = file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            // Safety net: release builds never carry the raw key.
            buildConfigField("String", "ANTHROPIC_API_KEY", "\"\"")
            buildConfigField("boolean", "AI_MOCK", "false")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.0.0")
    testImplementation("junit:junit:4.13.2")
}
