import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    configure<DetektExtension> {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = rootProject.file("config/detekt/detekt-baseline.xml")
        buildUponDefaultConfig = true
        source.setFrom(
            "src/commonMain/kotlin",
            "src/androidMain/kotlin",
        )
    }
    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }
}
