plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Single source of truth for the app version: the root-level VERSION file.
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

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.multiplatform.settings)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.runtime.ktx)
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.androidx.work.runtime.ktx)
                implementation(libs.androidx.health.connect.client)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
                // Android's android.jar ships an org.json that throws "Stub!" under JVM unit
                // tests; production code uses org.json, so tests need the real implementation.
                implementation(libs.org.json)
            }
        }
    }
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
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
