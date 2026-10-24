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

// Guard: ktlint must actually be inspecting the app's Kotlin sources.
//
// After the AGP 9 migration (PR #84) plugin 12.1.2 could no longer discover
// the Kotlin source sets - the `kotlin.android` plugin it looked for was
// gone - so `ktlintCheck` registered no per-source-set task, inspected
// nothing, and still exited 0. That stayed invisible for two weeks of
// merged PRs. A lint check that passes vacuously is worse than none, so
// fail the build at configuration time unless the plugin has registered a
// check for every source set we expect and wired it into `ktlintCheck`.
gradle.projectsEvaluated {
    val app = project(":app")
    val expected = listOf("ktlintMainSourceSetCheck", "ktlintTestSourceSetCheck")
    val missing = expected.filter { app.tasks.findByName(it) == null }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "ktlint is not inspecting the app sources: ${missing.joinToString()} not registered. " +
                "The ktlint Gradle plugin failed to discover the Kotlin source sets (as 12.1.2 did " +
                "under AGP 9), so ktlintCheck would pass without reading a file. " +
                "Registered ktlint tasks: ${app.tasks.names.filter { it.startsWith("ktlint") }.sorted()}",
        )
    }
    val aggregate = app.tasks.getByName("ktlintCheck")
    val wired = aggregate.taskDependencies.getDependencies(aggregate).map { it.name }
    val unwired = expected.filter { it !in wired }
    if (unwired.isNotEmpty()) {
        throw GradleException(
            "ktlintCheck no longer depends on ${unwired.joinToString()}; it would pass without " +
                "inspecting those sources. Wired: $wired",
        )
    }
}
