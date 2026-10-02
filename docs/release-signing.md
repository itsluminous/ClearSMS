# Release signing (CI)

CI builds release APKs on every push. If signing secrets are **not** configured
(e.g. on forks), it still succeeds and produces unsigned APKs - signed
publishing activates automatically once the secrets exist.

One-time keystore generation (keep this file and its passwords private; it is
never committed - `*.jks` is gitignored):

```bash
keytool -genkeypair -v -keystore clearsms-release.jks -alias clearsms \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then configure four repository secrets under
*Settings → Secrets and variables → Actions*:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | `base64 -i clearsms-release.jks` output |
| `SIGNING_KEYSTORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | the key alias (e.g. `clearsms`) |
| `SIGNING_KEY_PASSWORD` | the key password |

Or with the GitHub CLI:

```bash
gh secret set SIGNING_KEYSTORE_BASE64 --body "$(base64 -i clearsms-release.jks)"
gh secret set SIGNING_KEYSTORE_PASSWORD
gh secret set SIGNING_KEY_ALIAS --body "clearsms"
gh secret set SIGNING_KEY_PASSWORD
```

Pushing a tag matching `v*` (e.g. `v0.1.0`) creates a GitHub Release with the
signed `ClearSMS.apk` attached, with auto-generated release notes. Before
tagging, add a changelog file for the new versionCode at
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` - F-Droid
shows it as the "What's New" text (see
[publishing-fdroid.md](publishing-fdroid.md)).
