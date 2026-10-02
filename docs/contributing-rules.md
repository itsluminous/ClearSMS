# Contributing rules

Categorization rules live under [`rules/`](../rules/) and are bundled into the APK at
build time - every app update ships the latest community rules. See
[adding-rules.md](adding-rules.md) for a step-by-step walkthrough and
[CONTRIBUTING.md](../CONTRIBUTING.md) for the JSON schema.

Two ways to contribute:

1. **Pull request** - add or edit a JSON file under `rules/<region>/<category>/` and
   open a PR (use the "Rule contribution" issue template if you prefer filing an issue).
2. **Email from the app** - in the app, go to *Settings → Rules → Share rules with
   developer*. This composes an email with your exported rules JSON attached; reviewed
   submissions are incorporated into the next release. There are no runtime rule
   downloads - the app stays fully offline.

Want a fully populated app for testing or screenshots without using real
messages? Replay the synthetic demo corpus into an emulator - see
[`scripts/demo/`](../scripts/demo/).

## Finding missing rules using your own messages

The most useful contribution is telling us which of *your* messages the app fails
to categorize. `scripts/audit_rule_coverage.py` replays the bundled rules and the
sender-ID directory against a real SMS corpus and reports exactly that. It runs on
your computer, needs no app build, and **masks all digits by default** so the
output is safe to share.

**1. Install the prerequisites**

- Python 3.8 or newer (`python3 --version`)
- `adb`, from the [Android SDK platform-tools](https://developer.android.com/tools/releases/platform-tools)
  (macOS: `brew install android-platform-tools`)
- This repository: `git clone https://github.com/itsluminous/ClearSMS.git && cd ClearSMS`

**2. Enable USB debugging on the phone**

- *Settings → About phone → Software information* and tap **Build number** seven
  times to unlock Developer options
- *Settings → Developer options → USB debugging* → on
- Connect the phone by USB and accept the "Allow USB debugging?" prompt
- Confirm it is visible: `adb devices` should list your device as `device`
  (not `unauthorized`)

**3. Run the check**

```bash
python3 scripts/audit_rule_coverage.py --from-device
```

The script reads your SMS through `adb` into memory only - it writes no copy of
your messages anywhere. Expect it to take a minute or two on a large inbox.

**4. Read the report**

- **Coverage** - the share of messages that got a confident category.
- **Per-rule hit counts** - which rules are doing the work.
- **Unmatched messages** - grouped by sender and body shape, ranked by how often
  they occur. This is the list worth reporting: the senders at the top are the
  biggest gaps.
- **`generic-*` rule breakdown** - messages caught only by the catch-all rules,
  listed per sender. Generic rules are a last-resort safety net, so anything here
  ideally deserves a sender-specific rule.

Useful flags: `--top N` (how many unmatched groups to print), `--generic-top N`
(senders listed per generic rule), `--no-generic-breakdown`, and
`--min-coverage N` (exit non-zero below a threshold, so the audit can gate CI).

**5. Share the findings**

Open an issue using the **Rule contribution** template and paste the *unmatched
groups* and *generic breakdown* sections. Before posting, read what you are about
to share:

- Digits are masked as `X`, but **check the text anyway** - names, email
  addresses, URLs and order references are not masked.
- Never pass `--no-redact` on anything you post publicly.
- Do not attach a full corpus dump, and keep any corpus file outside this
  repository.
- Better still, send a pull request: rules are plain JSON under `rules/`, and the
  schema is documented in [CONTRIBUTING.md](../CONTRIBUTING.md).

Rules must contain only generic patterns and public brand/sender names - never
your account numbers, amounts or personal details.

If you would rather not use a computer at all, the app can do a simpler version of
this: *Settings → Rules → Share rules with developer* emails your exported rules
JSON, which tells us what you have had to add by hand.

**Auditing from a file instead of a phone**

If you already have a corpus exported as JSONL (one
`{"sender": ..., "body": ...}` object per line):

```bash
python3 scripts/audit_rule_coverage.py corpus.jsonl --min-coverage 80
```

## Sender ID database

The community-maintained sender ID directory lives at
`rules/sender_ids/india_sender_ids.json.gz`. It is compiled into the SQLite asset the
app ships (`app/src/main/assets/sender_ids.db`) with:

```bash
python3 scripts/build_sender_db.py \
  rules/sender_ids/india_sender_ids.json.gz \
  app/src/main/assets/sender_ids.db
```

After editing the JSON, rebuild the `.db` and include both files in your PR.

For small fixes to wrong upstream entries (e.g. a sender ID mapped to an
unrelated business), you do not need to regenerate the large `.db` asset:
add the corrected entry to
[`rules/sender_ids/corrections.json`](../rules/sender_ids/corrections.json)
and copy it to `app/src/main/assets/sender_id_corrections.json` (a unit test
keeps the two identical). Corrections are consulted before the bundled
directory, so they always win for the same normalized sender ID.

## Brand identity table

Sender avatars for well-known brands are drawn from a curated table at
[`rules/brands/brands.json`](../rules/brands/brands.json), bundled into the APK as
`app/src/main/assets/brands.json` (a unit test keeps the two copies identical -
edit the `rules/brands/` master and copy it over). For brands without bundled
logo artwork (see below) the app renders an **original** mark from these
facts - a circular tile in the brand's published primary color, a short
monogram, and a category badge - with text color chosen by WCAG luminance so
it stays legible.

Each entry looks like:

```json
{
  "key": "hdfc",
  "name": "HDFC Bank",
  "category": "BANK",
  "color": "#004C8F",
  "monogram": "H",
  "senders": ["HDFCBK", "HDFCB"],
  "aliases": ["HDFC", "HDFC BANK"]
}
```

- `key` - unique lowercase identifier (also the bundled-logo filename key).
- `category` - one of `BANK`, `CARD`, `WALLET`, `TELECOM`, `ECOMMERCE`,
  `DELIVERY`, `GOVERNMENT`, `UTILITY`, `INVESTMENT`, `HEALTH`, `TRAVEL`, `OTHER`.
- `color` - the brand's widely-published primary color as `#RRGGBB`.
- `monogram` - 1–3 characters drawn on the tile.
- `senders` - exact sender IDs after TRAI normalization (`VM-HDFCBK` → `HDFCBK`).
- `aliases` - whole-word names matched against resolved display names.

## Bundled sender logos

The APK ships real logo artwork for 27 of the curated brands under
`app/src/main/assets/logos/` (~180 KB total, PNG, max 256 px). The images
are assembled by [`scripts/build_logo_pack.py`](../scripts/build_logo_pack.py)
`--bundle` from the latest commits of two MIT-licensed projects
([auraveni/global-bank-logos](https://github.com/auraveni/global-bank-logos)
and [cashfree/payments-icons-library](https://github.com/cashfree/payments-icons-library));
the exact commits each build used are recorded in the manifest, so the
committed asset set stays traceable.
Per-file provenance lives in `app/src/main/assets/logos/MANIFEST.md`; the
upstream MIT licence texts are reproduced in [NOTICE](../NOTICE).

On the legal position: the upstream MIT licences cover those projects'
packaging of the files - the logos themselves remain trademarks of the
banks and merchants they identify, and are bundled solely to label message
senders in your own inbox. Logos are never fetched at runtime (the app
requests no network permission); brands without bundled artwork get the
generated brand tiles described above.

The avatar fallback chain, in order: contact photo → bundled logo →
generated brand tile → category glyph → letter avatar. All of it is gated
behind *Settings → Appearance → Show logos and contact photos*, and every
avatar renders as the same circular tile across the inbox, conversations,
search, Finance and Alerts.
