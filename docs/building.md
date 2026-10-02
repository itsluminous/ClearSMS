# Building Clear SMS

Requirements: JDK 17+ and the Android SDK (compileSdk 35). The Android Gradle
plugin (9.x, with its built-in Kotlin support) also needs SDK build-tools
36.0.0; Gradle installs it on first build if the SDK manager's licences are
accepted, which is also how the F-Droid build server gets it.

```bash
git clone https://github.com/itsluminous/ClearSMS.git
cd ClearSMS
# point to your SDK if ANDROID_HOME is not set:
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug
```

Run checks the same way CI does:

```bash
./gradlew ktlintCheck lintDebug testDebugUnitTest
```

`./gradlew assembleRelease` produces a single universal APK under
`app/build/outputs/apk/release/` (Clear SMS has no native code of its own -
the only `.so` files come from AndroidX's DataStore and graphics-path
helpers - so per-ABI splits would save about 45 KB and cost an extra
artifact to verify). Without signing environment variables (see
[release-signing.md](release-signing.md)) it is unsigned. Release APKs are
shrunk with R8 and resource shrinking but **not obfuscated**
(`-dontobfuscate` in `app/proguard-rules.pro`), keeping the shipped APK
auditable and the build reproducible for F-Droid verification.

> Follow-up: Gradle dependency verification / lockfiles are not yet
> configured; CI validates the Gradle wrapper checksum but does not yet pin
> dependency hashes.
