# Publishing Clear SMS on F-Droid

This documents how the app is published on F-Droid and what the maintainer
must do for the initial submission and for every release afterwards.

## How the pieces fit together

F-Droid needs two sets of metadata, in two different places:

1. **In this repository** (already done): the `fastlane/metadata/android/en-US/`
   tree - title, short/full description, icon, feature graphic, screenshots
   and per-versionCode changelogs. F-Droid reads these from the **latest
   release tag**, so they only take effect once a tag containing them exists.
2. **In F-Droid's [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
   repository**: a build-recipe file `metadata/app.clearsms.yml` that tells
   the F-Droid build server how to check out and build the app. Getting the
   app on F-Droid = getting a merge request with this file accepted.

## Reproducible builds: one signing key everywhere

The submission below uses F-Droid's *reproducible builds* mode
(`Binaries:` + `AllowedAPKSigningKeys`). The F-Droid build server rebuilds
the APK from source, verifies it is byte-identical (minus signature) to the
APK attached to the GitHub release, and then **publishes the
developer-signed APK**. Consequences:

- The APK on F-Droid carries the same signature as the GitHub release, so
  users can install from either place and update from the other.
- If a release ever fails to reproduce, F-Droid simply doesn't publish that
  version until fixed (the build-cycle log shows the diff). Tooling for
  diagnosing: https://f-droid.org/docs/Reproducible_Builds/

The signing certificate pinned in the metadata (SHA-256 of the release
signing cert, printed by `apksigner verify --print-certs`):

```
acb5eddbb1bbc2d3cd125776eebab345c083c92b9db7f4a33e55f5d139dd0448
```

Things that keep the build reproducible (do not undo these):

- `vcsInfo { include = false }` on the release build type - AGP otherwise
  embeds `META-INF/version-control-info.textproto` with the git revision and
  the checkout path. F-Droid patches `build.gradle.kts` before building, so
  that recorded state cannot be relied on to match.
- `isCrunchPngs = false` - aapt2's PNG cruncher is not byte-stable across
  build-tools versions and host platforms. Two files (`res/ww.png`,
  `res/yi.png`) came out a few bytes apart between a Linux CI build and a
  macOS build of the same commit, which shifts every subsequent zip offset
  and makes `apksigcopier` fail with "APK Signing Block offset < central
  directory offset".
- `dependenciesInfo { includeInApk = false }` - removes Google's
  non-deterministic, encrypted dependency-info block.
- A single universal APK (no ABI splits) - one artifact to verify.
- `-dontobfuscate` in `app/proguard-rules.pro` - stable R8 output.
- CI builds with JDK 17 (temurin).

### Checking reproducibility yourself

After a release is published, verify that a local build matches the
released APK exactly - this is the same check F-Droid runs:

```bash
pip install apksigcopier
export PATH="$ANDROID_HOME/build-tools/<version>:$PATH"   # for apksigner
git checkout v<version>
./gradlew clean assembleRelease                            # unsigned
curl -sLO https://github.com/itsluminous/ClearSMS/releases/download/v<version>/ClearSMS.apk
apksigcopier compare ClearSMS.apk --unsigned app/build/outputs/apk/release/ClearSMS.apk
```

No output and exit code 0 means the builds are identical. To see *which*
entries differ when it fails, compare the zip entry CRCs of the two APKs.

## The fdroiddata recipe

This is the content for `metadata/app.clearsms.yml` in the fdroiddata repo
(kept here as the master copy; update `CurrentVersion`/`CurrentVersionCode`
drift is handled automatically by F-Droid's checkupdates bot):

```yaml
Categories:
  - Phone & SMS
  - Finance Manager
License: Apache-2.0
AuthorName: itsluminous
AuthorEmail: rules@clearsms.app
SourceCode: https://github.com/itsluminous/ClearSMS
IssueTracker: https://github.com/itsluminous/ClearSMS/issues
Changelog: https://github.com/itsluminous/ClearSMS/releases

RepoType: git
Repo: https://github.com/itsluminous/ClearSMS.git
Binaries: https://github.com/itsluminous/ClearSMS/releases/download/v%v/ClearSMS.apk

Builds:
  - versionName: 0.14.3
    versionCode: 53
    commit: v0.14.3
    subdir: app
    gradle:
      - yes
    output: build/outputs/apk/release/ClearSMS.apk
    scanignore:
      - rules/sender_ids/india_sender_ids.json.gz

AllowedAPKSigningKeys: acb5eddbb1bbc2d3cd125776eebab345c083c92b9db7f4a33e55f5d139dd0448

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 0.14.3
CurrentVersionCode: 53
```

Notes for reviewers (worth repeating in the merge-request description):

- The single `scanignore` entry covers `rules/sender_ids/india_sender_ids.json.gz`,
  gzipped JSON that is the community master of the sender-ID directory;
  `scripts/build_sender_db.py` compiles it into the SQLite asset
  `app/src/main/assets/sender_ids.db`. Both are in-repo and auditable. Note
  that only paths the scanner actually flags may be listed: an unused
  `scanignore` entry is a hard error (`Unused scanignore path: ...`).
- The app requests no INTERNET permission; the bundled brand logos under
  `app/src/main/assets/logos/` are MIT-licensed artwork with provenance in
  `app/src/main/assets/logos/MANIFEST.md` and licence texts in `NOTICE`.
- `gradle-wrapper.jar` is the official Gradle wrapper (CI validates its
  checksum); recent fdroidserver verifies it automatically.

## Release process (every release)

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
   (max 500 characters).
3. Commit, tag `v<versionName>`, push the tag. CI signs and attaches
   `ClearSMS.apk` to the GitHub release.
4. Nothing else: F-Droid's checkupdates bot sees the new tag, adds a build
   block, rebuilds, verifies against the GitHub APK and publishes.

The tag must stay immutable - never delete/re-tag a published version;
publish a new patch version instead.
