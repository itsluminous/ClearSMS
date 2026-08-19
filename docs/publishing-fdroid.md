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

- `dependenciesInfo { includeInApk = false }` in `app/build.gradle.kts` -
  removes Google's non-deterministic, encrypted dependency-info block.
- A single universal APK (no ABI splits) - one artifact to verify.
- `-dontobfuscate` in `app/proguard-rules.pro` - stable R8 output.
- CI builds with JDK 17 (temurin); the fdroiddata recipe pins the same.

## The fdroiddata recipe

This is the content for `metadata/app.clearsms.yml` in the fdroiddata repo
(kept here as the master copy; update `CurrentVersion`/`CurrentVersionCode`
drift is handled automatically by F-Droid's checkupdates bot):

```yaml
Categories:
  - Phone & SMS
  - Money
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
  - versionName: 0.14.0
    versionCode: 50
    commit: v0.14.0
    subdir: app
    gradle:
      - yes
    scanignore:
      - app/src/main/assets/sender_ids.db
      - rules/sender_ids/india_sender_ids.json.gz

AllowedAPKSigningKeys: acb5eddbb1bbc2d3cd125776eebab345c083c92b9db7f4a33e55f5d139dd0448

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 0.14.0
CurrentVersionCode: 50
```

Notes for reviewers (worth repeating in the merge-request description):

- `app/src/main/assets/sender_ids.db` is a **data file**, not code: a SQLite
  index of SMS sender IDs compiled from the JSON master
  `rules/sender_ids/india_sender_ids.json.gz` by
  `scripts/build_sender_db.py` (both in-repo, both auditable). The `.gz` is
  gzipped JSON. Hence the `scanignore` entries.
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
