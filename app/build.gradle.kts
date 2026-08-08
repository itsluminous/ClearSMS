plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing is driven entirely by environment variables so that the same
// build file works everywhere: in CI with the signing secrets configured the
// release APKs come out signed, while local builds (and forks / PRs without
// secrets) gracefully fall back to unsigned release APKs instead of failing.
val signingKeystoreFile: String? = System.getenv("SIGNING_KEYSTORE_FILE")
val signingKeystorePassword: String? = System.getenv("SIGNING_KEYSTORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning =
    !signingKeystoreFile.isNullOrBlank() &&
        !signingKeystorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()

android {
    namespace = "app.clearsms"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.clearsms"
        minSdk = 23
        targetSdk = 35
        versionCode = 23
        versionName = "0.8.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(signingKeystoreFile!!)
                storePassword = signingKeystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Signed only when all four SIGNING_* environment variables are set
            // (see the top of this file); otherwise the release APK is unsigned.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // R8 code shrinking + resource shrinking keep the APK small.
            // Obfuscation is disabled in proguard-rules.pro (-dontobfuscate):
            // the app is open source, so auditability is preserved while dead
            // code and unused resources are still removed. Room, Hilt and
            // kotlinx.serialization are covered by their bundled consumer
            // rules plus the curated app rules in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Per-ABI APKs keep downloads small; the universal APK works on any device.
    // Per-ABI versionCode differentiation is deliberately not applied: it is
    // only required when uploading multiple APKs to the Play Store, not for
    // GitHub-based distribution where users pick the matching APK (or the
    // universal one).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time and other modern JDK APIs are used with minSdk 23.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME feeds the Settings → About release-notes link.
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
            // Bundled parser tables as CLASSPATH resources, so the pure-JVM
            // domain parsers (and their plain JUnit tests) load them with no
            // Android Context. `assets/tables/` mirrors `rules/tables/`
            // (identity enforced by a unit test); `rules/brands/` is the
            // community master of brands.json, packaged directly.
            resources.srcDir("src/main/assets/tables")
            // Named guard patterns, same arrangement: `assets/guards/`
            // mirrors `rules/guards.json` (identity enforced by a unit test).
            resources.srcDir("src/main/assets/guards")
            resources.srcDir("$rootDir/rules/brands")
        }
        getByName("debug") {
            // Robolectric unit tests read the DEBUG merged assets; exposing the
            // committed Room schema JSONs here lets MigrationTestHelper load
            // them. Debug-only: release APKs never include them.
            assets.srcDir("$projectDir/schemas")
        }
        getByName("test") {
            kotlin.srcDir("src/test/kotlin")
        }
    }
}

// Publish human-friendly artifact names: ClearSMS-<abi>.apk for release builds
// (what ends up attached to a GitHub release) and ClearSMS-<abi>-debug.apk for
// debug builds, instead of Gradle's default app-<abi>-<buildType>.apk.
androidComponents {
    onVariants { variant ->
        val suffix = if (variant.buildType == "release") "" else "-${variant.buildType}"
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull()?.identifier ?: "universal"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set("ClearSMS-$abi$suffix.apk")
        }
    }
}

// Exported Room schemas (schemas/<db>/<version>.json) are committed so future
// schema changes can ship validated migrations against the released baseline.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Size note: assets/sender_ids.db (44 MB raw) is deflate-compressed by AAPT2
// inside the APK (~16.5 MB stored) — .db is not on the default noCompress
// list, so no androidResources tuning is required. The remaining size lever
// (a more compact on-disk sender directory format) lives in the data layer.
//
// TODO(supply-chain): add Gradle dependency verification
// (gradle/verification-metadata.xml) and/or version-catalog lockfiles so CI
// can detect tampered dependencies. Tracked as a follow-up; see README.

dependencies {
    // Core AndroidX + lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (versions from the BOM)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // Paging (large message lists load incrementally instead of whole tables)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Serialization + coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager + DataStore
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    // Device-lock gate for Settings → Privacy → Show balance. BIOMETRIC_WEAK
    // or DEVICE_CREDENTIAL: fingerprint/face AND PIN/pattern/password all
    // unlock — purely local, no new permissions.
    implementation(libs.androidx.biometric)

    // Images + permissions. Coil renders contact photos from content:// URIs
    // (ui/components/SenderAvatar.kt) — it never performs network I/O in this
    // app: no http(s) URLs are ever loaded, so the transitively-included
    // OkHttp engine is dormant. Revisit with a ContentResolver+BitmapFactory
    // loader if the dependency footprint becomes a concern.
    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)

    // Material Components (provides the XML Theme.Material3.* parent used in themes.xml)
    implementation(libs.google.material)

    // Desugaring (java.time on minSdk 23)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.turbine)
    // Migration tests validate committed schemas; worker tests need a test WorkManager.
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.work.testing)
    // Schema-shape tests reflect over serializable models with kotlin.reflect.
    testImplementation(kotlin("reflect"))
}
