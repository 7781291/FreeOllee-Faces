plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Single source of truth for the app version: the root-level VERSION file.
// versionCode is derived as MAJOR*10000 + MINOR*100 + PATCH (MINOR/PATCH must stay <= 99).
val appVersionName: String = rootProject.file("VERSION").readText().trim()
val appVersionCode: Int = run {
    val parts = appVersionName.split(".")
    require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) {
        "VERSION must be MAJOR.MINOR.PATCH (got '$appVersionName')"
    }
    val (major, minor, patch) = parts.map { it.toInt() }
    require(minor <= 99 && patch <= 99) {
        "VERSION minor/patch must each be <= 99 for the versionCode formula (got '$appVersionName')"
    }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "com.blizzardcaron.freeolleefaces"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blizzardcaron.freeolleefaces"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
}

kotlin {
    jvmToolchain(17)
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
