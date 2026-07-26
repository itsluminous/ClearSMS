## What does this PR do?

<!-- Short description of the change. Link related issues with #123. -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Categorization rule (JSON under `rules/`)
- [ ] Sender ID database update
- [ ] Docs / CI / tooling

## Checklist

- [ ] `./gradlew ktlintCheck lintDebug testDebugUnitTest` passes locally
- [ ] No network calls, analytics, or proprietary dependencies introduced
- [ ] User-visible strings are in `res/values` string resources
- [ ] For rule changes: JSON validates, regexes tested against **redacted** sample messages
- [ ] For sender ID changes: `app/src/main/assets/sender_ids.db` regenerated via `scripts/build_sender_db.py`

## Sample messages (for rule changes)

<!-- Paste 2–3 sample messages with personal data redacted
     (amounts, names, account digits replaced by placeholders). -->
