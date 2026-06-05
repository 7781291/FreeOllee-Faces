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

    signingConfigs {
        create("release") {
            // Populated only in CI (see .github/workflows/release.yml). Absent locally,
            // which is fine: the release buildType only attaches this config when present.
            System.getenv("KEYSTORE_FILE")?.let { ksPath ->
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Install dev/test builds as a SEPARATE app (….debug) so the debug-signed build never
            // collides with the release-signed GitHub build. Different signing certs can't update
            // over each other; a distinct applicationId lets both coexist. The label is overridden
            // in src/debug/res so it's easy to tell apart in the drawer.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
    implementation(libs.androidx.health.connect.client)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
