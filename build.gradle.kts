// Top-level build file: plugin versions come from gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint)
}

// Apply ktlint to every module so `./gradlew ktlintCheck` covers the whole project.
allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        // ktlint-cli 1.8.0 ships logback-classic 1.3.16 (closes Dependabot #12, #13, #23).
        version.set("1.8.0")
        // `android.set(true)` is NOT forwarded to ktlint >= 1.0 by plugin 12.x (only to the
        // 0.47/0.48 code paths); the code style comes from .editorconfig `ktlint_code_style`.
        filter {
            exclude { it.file.path.contains("${layout.buildDirectory.get()}") }
            exclude("**/build/**")
        }
    }
}
