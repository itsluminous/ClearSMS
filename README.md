# Clear SMS

[![Android CI](https://github.com/OWNER/ClearSMS/actions/workflows/android.yml/badge.svg)](https://github.com/OWNER/ClearSMS/actions/workflows/android.yml)

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
git clone https://github.com/OWNER/ClearSMS.git
cd ClearSMS
# point to your SDK if ANDROID_HOME is not set:
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug
```

Run checks the same way CI does:

```bash
./gradlew ktlintCheck lintDebug testDebugUnitTest
```

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
