// Top-level build file: plugin versions come from gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
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

    // ktlint-cli stays on the logback 1.3.x line, which Dependabot still flags (#26, #49, #50
    // want 1.5.25+/1.5.33+/1.5.34+). logback is only the *lint tool's* logger - it is never on
    // the app classpath - so override it on the `ktlint` tool configuration alone. ktlint 1.8.0
    // runs unchanged on logback 1.5.x (slf4j-api 2.x, JDK 17). Drop this once ktlint-cli
    // itself ships logback >= 1.5.34.
    configurations.matching { it.name == "ktlint" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "ch.qos.logback") useVersion("1.5.38")
        }
    }
}
