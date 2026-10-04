import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Central config: brave.properties (project root) ----
fun loadProps(name: String) = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}
val cfg = loadProps("brave.properties")
val websiteUrl = cfg.getProperty("WEBSITE_URL").trim()
val appName = cfg.getProperty("APP_NAME").trim()
val appId = cfg.getProperty("APP_ID").trim()
val extraHosts = (cfg.getProperty("EXTRA_INTERNAL_HOSTS") ?: "").trim()
val fcmTopic = (cfg.getProperty("FCM_TOPIC") ?: "brave-members").trim()
val pullToRefresh = (cfg.getProperty("PULL_TO_REFRESH") ?: "true").trim().toBoolean()
val websiteHost = URI(websiteUrl).host
require(websiteUrl.startsWith("https://")) { "WEBSITE_URL must start with https://" }

val keystoreProps = loadProps("keystore.properties")

// Firebase: only enabled when app/google-services.json exists (it is NOT committed to git).
// Without the file the app still builds and works - it just receives no push notifications.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.brave.portal"   // source-code package; no need to change it
    compileSdk = 35

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 35
        versionCode = cfg.getProperty("VERSION_CODE").trim().toInt()
        versionName = cfg.getProperty("VERSION_NAME").trim()

        buildConfigField("String", "WEBSITE_URL", "\"$websiteUrl\"")
        buildConfigField("String", "EXTRA_INTERNAL_HOSTS", "\"$extraHosts\"")
        buildConfigField("String", "FCM_TOPIC", "\"$fcmTopic\"")
        buildConfigField("boolean", "PULL_TO_REFRESH", pullToRefresh.toString())
        resValue("string", "app_name", appName)
        manifestPlaceholders["websiteHost"] = websiteHost
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
