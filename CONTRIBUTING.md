# Contributing to Clear SMS

Thanks for helping make SMS organization better for everyone! The most valuable
contribution is a **categorization rule**: a small JSON document that teaches the app
how to classify a message and what to extract from it.

## Rule contribution workflow

> New here? [docs/adding-rules.md](docs/adding-rules.md) walks through turning one
> mis-categorized message into a shipped rule. This file is the reference.

1. Fork the repo and add (or edit) a JSON file under
   `rules/<region>/<category>/` — for example `rules/india/banks/hdfc.json`.
2. Validate your JSON (any JSON linter) and make sure regexes compile with Kotlin's
   `Regex` (Java `Pattern` syntax; escape backslashes in JSON: `\\d`, `\\s`).
3. Open a pull request. Include 2–3 **redacted** sample messages the rule matches
   (replace amounts, names, and account digits with placeholders).

Alternatively, use *Settings → Rules → Share rules with developer* inside the app to
email your exported rules JSON, and we'll review and incorporate it into a release.

## Rule format

Each file is a document with a `version` and a list of `rules`:

```json
{
  "version": "1.0",
  "rules": [
    {
      "id": "hdfc-debit-01",
      "name": "HDFC Bank Debit Transaction",
      "priority": 100,
      "match": {
        "sender_pattern": "(?i)HDFCBK",
        "body_pattern": "debited[^\\n]{0,40}?INR\\s*([\\d,]+(?:\\.\\d{1,2})?)[^\\n]{0,40}?a/c\\s*[Xx*]*(\\d{4})",
        "body_must_contain": ["debited"],
        "guards_none": ["otp_mention"]
      },
      "action": {
        "category": "important",
        "sub_category": "transaction",
        "extract": {
          "amount": "$1",
          "account_last4": "$2",
          "type": "debit",
          "bank": "HDFC Bank"
        }
      },
      "contributed_by": "github_username",
      "created_at": "2026-07-27T00:00:00Z"
    },
    {
      "id": "generic-otp-01",
      "name": "Generic OTP Extraction",
      "priority": 50,
      "match": {
        "body_pattern": "(?i)(?:otp|code|password)\\s*(?:is|:)?\\s*(\\d{4,8})"
      },
      "action": {
        "category": "otp",
        "extract": {
          "otp_code": "$1"
        },
        "notification": "otp_popup"
      }
    }
  ]
}
```

### Field reference

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Unique, kebab-case, stable across releases (e.g. `sbi-credit-02`) |
| `name` | yes | Human-readable description |
| `priority` | yes | Higher wins when multiple rules match (built-in generic rules use ≤ 50) |
| `match.sender_pattern` | no | Regex matched against the normalized sender ID |
| `match.body_pattern` | no | Regex matched against the message body; capture groups feed `extract` |
| `match.body_must_contain` | no | All strings must be present (case-sensitive) |
| `match.body_must_not_contain` | no | None of these strings may be present |
| `match.guards_none` | no | Named guards that must NOT match the body — the rule does not apply if any listed guard fires. Ids come from `rules/guards.json` or `rules/rule_guards.json` (e.g. `otp_mention`, `settled_payment`); a rule naming an unknown id is skipped with a logged warning |
| `action.category` | yes | `important` \| `promotional` \| `personal` \| `otp` \| `unknown` |
| `action.sub_category` | no | e.g. `transaction`, `otp`, `bill`, `bank_alert`, `delivery`, `offer`, `scam`, `recharge`, `government`, `investment` |
| `action.extract` | no | Map of extracted keys to `$n` capture-group references or literals (`amount`, `account_last4`, `type`, `bank`, `otp_code`, `due_date`, `merchant`, …) |
| `action.extract_types` | no | Explicit types for extract keys where inference is wrong (see below) |
| `action.notification` | no | e.g. `otp_popup` |
| `contributed_by` | no | Your GitHub username |
| `created_at` | no | ISO-8601 timestamp |

Keys are **snake_case**, exactly as shown.

### Typed extracts

Every extracted value is resolved to a **typed** value exactly once by the
app, so the rule only has to say *which capture is what kind of thing* — the
parsing itself (the amount grammar, date formats, merchant cleanup) is done
by the app.

The type is **inferred from the extract key**, so the terse form above needs
no annotations:

| Extract key | Inferred type |
|---|---|
| `amount`, `balance`, `available_limit`, `total_due`, `min_due`, `total_limit` | `amount` — comma-grouped digits, e.g. `1,23,456.78` |
| `due_date` | `date` — `DD-MM-YY(YY)`, `DD-MMM-YY(YY)` or `YYYY-MM-DD` |
| `merchant` | `merchant` — cleaned of reference digits and trailing month/year noise |
| `type` | `transaction_type` — `debit` or `credit` |
| anything else | `text` — kept verbatim |

Where inference would be wrong, declare the type explicitly in
`action.extract_types` (values: `amount`, `date`, `merchant`,
`transaction_type`, `text`):

```json
"action": {
  "category": "important",
  "extract": { "renewal_date": "$1" },
  "extract_types": { "renewal_date": "date" }
}
```

A capture that fails to parse as its type is kept as raw text (the rule
still applies); a rule declaring an unknown type name is skipped entirely,
with a logged warning.

## Rule guidelines

- **Redact personal data.** Never include real phone numbers, full account numbers,
  names, or balances in rules, tests, or PR descriptions.
- **Be specific.** Prefer `sender_pattern` + `body_must_contain` over broad body regexes
  to avoid false positives.
- **One institution per file.** Add related rules (debit, credit, balance) to the same
  file, each with a unique `id`.
- **Test your regex.** Verify against your sample messages before submitting.
- **Write patterns that cannot hang the app.** Rule patterns run against every
  incoming SMS, and shape matters more than length: a pattern wrapped in `.*`
  once took **423 seconds** on a single long message here. Patterns are validated
  at load and rejected (with a logged warning) if they break these rules:
  - no leading/trailing `.*` or `[\s\S]*` — the engine already searches anywhere
    in the body, so a wrapper adds nothing and is what caused that bug;
  - no nested unbounded quantifiers (`(\d+)*`, `(\s*\w*)+`);
  - bound the gaps between anchors: `[^\n]{0,40}?`, not `.*`;
  - no variable-length lookbehind; `(?i)` is the only inline flag.
- **Prefer `guards_none` over hand-rolled exclusions.** `guards_none: ["otp_mention"]`
  beats `body_must_not_contain: ["OTP","otp"]` — the guard is maintained centrally.

## Sender ID database

Sender IDs (`HDFCBK`, `AMZNIN`, …) live in
`rules/sender_ids/india_sender_ids.json.gz`. Edit the JSON, then rebuild the bundled
SQLite asset:

```bash
python3 scripts/build_sender_db.py \
  rules/sender_ids/india_sender_ids.json.gz \
  app/src/main/assets/sender_ids.db
```

Commit both the JSON and the regenerated `.db` in your PR.

## Parser lookup tables

Beyond the message rules, the parsers consult small community-editable lookup
tables under [`rules/tables/`](rules/tables/). Each master file is mirrored at
`app/src/main/assets/tables/` (a unit test keeps the two identical — edit the
`rules/tables/` master and copy it over):

| File | Feeds | Contents |
| --- | --- | --- |
| `merchant_categories.json` | spend categories in Finance | merchant-keyword regex → category (`FOOD`, `SHOPPING`, …), first match wins |
| `couriers.json` | delivery alerts | courier/merchant name keys (substring-matched against sender ids and bodies) and brand registered domains (matched against URL hostnames only) |
| `billers.json` | reminder type classification | known biller sender ids (literals, regex-escaped by the app), insurer name patterns, and bill-domain word patterns |
| `reminder_evidence.json` | reminder type classification | per-type scored evidence rows: `pattern` (body regex), `table_ref`/`sender_table_ref` (reuse of the `billers.json` regexes), `score`, optional `not_if_guard` (a guard id whose match suppresses the row) and `only_if_no_other_evidence`; `support` rows corroborate but can never classify alone. The threshold, tie-break order and bill-disqualifies-subscription arbitration stay in Kotlin — see the file's inline description |

The app assembles any regexes from these tables in code, so keep entries
simple: literal ids where the field says literal, and for pattern fields flat
case-insensitive fragments with word boundaries — never nested quantifiers,
never unbounded `.*` spans. Only public brand, courier, insurer, and biller
names belong here; scoring, thresholds and arbitration stay in Kotlin.

The brand identity table (`rules/brands/brands.json`, see the README) also
carries the financial-institution data used to name accounts: entries with an
`is_issuer` field are institutions, and the optional `issuer_name`,
`issuer_senders`, and `issuer_aliases` fields override the brand's display
values where the account-naming view differs from the avatar view (for
example, SBI Card messages belong to the State Bank of India account while
keeping their own avatar). `is_issuer: true` means the entry can own an
account or card (banks, wallets, provident funds); `false` marks brands that
appear in money messages only as merchants or payment channels.

## Guard library

The parsers also consult a library of named **guards** — negative knowledge
like "this phrasing means a *failed* payment" or "this is a statement notice,
not a debit" — whose patterns live in [`rules/guards.json`](rules/guards.json)
(mirrored at `app/src/main/assets/guards/guards.json`; a unit test keeps the
two identical — edit the `rules/` master and copy it over). Each entry:

```json
{
  "id": "failed_payment",
  "description": "Failed / declined payments — never a transaction.",
  "patterns": ["(?i)\\bhas\\s+failed\\b", "(?i)\\bunsuccessful\\b"]
}
```

- `id` — stable identifier; the app binds each id to a fixed semantic
  (scrub / reject / suppress) in code. Adding a new id has no effect until
  code consults it, and removing one only disables that veto.
- `patterns` — standalone regexes; the guard fires when ANY pattern matches.
  Prefix `(?i)` for case-insensitivity.

Because guards run against every incoming message, patterns are validated at
load and unsafe ones are skipped (with a logged warning): keep each pattern
under 512 characters, never start or end it with `.*`/`[\s\S]*`, never nest
unbounded quantifiers (`(a+)+`), never use a variable-length lookbehind.
Bounded spans like `[^\n]{0,80}?` are the right way to bridge words.

What a guard *means* — where it is consulted and what happens when it fires —
stays in Kotlin. Editing `guards.json` can fix a false positive (a new
statement phrasing leaking into transactions) but cannot change semantics.

### Referencing guards from rules: `guards_none`

A categorization rule can veto itself against named guards instead of
restating the patterns:

```json
"match": {
  "sender_pattern": "HDFCBK",
  "body_pattern": "(?i)debited[\\s\\S]{0,40}?Rs\\.?\\s*([\\d,]+)",
  "guards_none": ["otp_mention"]
}
```

means "this rule does not apply if any listed guard matches the body". Rules
may reference every `guards.json` id, plus the entries of
[`rules/rule_guards.json`](rules/rule_guards.json) (mirrored at
`app/src/main/assets/guards/rule_guards.json`; a unit test keeps the two
identical) — guards that exist purely for rules, like `otp_mention`, which no
Kotlin call site consults. That file's id namespace is open: adding a guard
there needs no code change, so shared negative knowledge lives in one
editable entry instead of being copy-pasted across a hundred rules. The same
pattern-safety rules as `guards.json` apply, and a rule naming an unknown
guard id is skipped with a logged warning — it never fires without its veto.

`scripts/audit_rule_coverage.py` replays `guards_none` faithfully, so
coverage audits reflect exactly what the app would do.

## Code contributions

- Kotlin official style, enforced by ktlint (`./gradlew ktlintCheck`, auto-fix with
  `./gradlew ktlintFormat`).
- All checks must pass: `./gradlew ktlintCheck lintDebug testDebugUnitTest`.
- Keep the app fully offline — PRs adding network calls, analytics, or proprietary
  dependencies will not be accepted.

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
