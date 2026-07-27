# Clear SMS

[![Android CI](https://github.com/itsluminous/ClearSMS/actions/workflows/android.yml/badge.svg)](https://github.com/itsluminous/ClearSMS/actions/workflows/android.yml)

**Clear SMS** is an open-source, privacy-first SMS app for Android that automatically
organizes your inbox. It categorizes messages (Important / Promotional / Personal / OTP),
extracts transactions into a personal finance dashboard, surfaces bill reminders, and
handles OTPs intelligently — all completely offline, on your device.

## Features

- **Smart inbox** — messages are automatically sorted into Important, Promotional,
  Personal, Unknown, and OTP using a transparent, regex-based rules engine (no ML black box).
- **Finance dashboard** — debit/credit transactions are extracted from bank SMS into
  accounts, credit cards, and spend summaries with hand-rolled Compose charts.
- **Bills & reminders** — upcoming bills and payment due dates in one Alerts view.
- **OTP handling** — big, copyable OTP notifications, optional auto-copy, and
  configurable auto-delete (24h / 3d / 7d / never).
- **Scam awareness** — heuristic flagging of likely scam/fraud messages.
- **Material You** — dynamic color on Android 12+, with a curated teal/indigo palette
  on older devices. Light, dark, and system themes.
- **Community rules** — categorization rules are plain JSON, bundled with the app and
  maintained by the community in this repository.

## Privacy Principles

- **100% offline.** No network calls at runtime. No servers, no telemetry, no analytics.
- **No proprietary dependencies.** No Firebase, no Play Services — pure AOSP compatible.
- **Your data stays on your device.** Backups are local files you control.
- **Transparent categorization.** Every rule is human-readable JSON you can inspect,
  edit, export, and contribute back.

## Building

Requirements: JDK 17+ and the Android SDK (compileSdk 35).

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

Release builds are split per ABI: `./gradlew assembleRelease` produces four
per-architecture APKs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) plus a
universal APK under `app/build/outputs/apk/release/`. Without signing
environment variables (see below) these are unsigned.

## Release signing (CI)

CI builds release APKs on every push. If signing secrets are **not** configured
(e.g. on forks), it still succeeds and produces unsigned APKs — signed
publishing activates automatically once the secrets exist.

One-time keystore generation (keep this file and its passwords private; it is
never committed — `*.jks` is gitignored):

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
per-ABI (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) and universal APKs
attached, with auto-generated release notes.

## Contributing Rules

Categorization rules live under [`rules/`](rules/) and are bundled into the APK at
build time — every app update ships the latest community rules. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the JSON schema.

Two ways to contribute:

1. **Pull request** — add or edit a JSON file under `rules/<region>/<category>/` and
   open a PR (use the "Rule contribution" issue template if you prefer filing an issue).
2. **Email from the app** — in the app, go to *Settings → Rules → Share rules with
   developer*. This composes an email with your exported rules JSON attached; reviewed
   submissions are incorporated into the next release. There are no runtime rule
   downloads — the app stays fully offline.

### Auditing rule coverage

`scripts/audit_rule_coverage.py` replays the bundled rules and the sender-ID
directory against a real SMS corpus and reports what would be categorized —
useful for finding high-value gaps before authoring new rules:

```bash
# Pull the corpus straight from a connected device (adb required):
python3 scripts/audit_rule_coverage.py --from-device

# Or from a JSONL file with one {"sender": ..., "body": ...} object per line:
python3 scripts/audit_rule_coverage.py corpus.jsonl --min-coverage 80
```

It prints total coverage, per-rule hit counts, and the unmatched messages
grouped by sender and body shape (ranked by frequency). Output is redacted by
default — digits are masked — but the corpus itself is private data: keep it
outside the repository and never paste raw messages into rules or issues.
Rules must contain only generic patterns and public brand/sender names.
`--min-coverage` makes the exit status non-zero below a threshold, so the
audit can gate CI once a reference corpus is available.

### Sender ID database

The community-maintained sender ID directory lives at
`rules/sender_ids/india_sender_ids.json.gz`. It is compiled into the SQLite asset the
app ships (`app/src/main/assets/sender_ids.db`) with:

```bash
python3 scripts/build_sender_db.py \
  rules/sender_ids/india_sender_ids.json.gz \
  app/src/main/assets/sender_ids.db
```

After editing the JSON, rebuild the `.db` and include both files in your PR.

## License

[Apache License 2.0](LICENSE)
