# Demo corpus & emulator seeding

A fully **synthetic** SMS corpus (`messages.jsonl`, 75 messages) that
exercises nearly every ClearSMS feature, plus a seeding tool (`seed.py`)
that replays it into an Android emulator. No message here belongs to a real
person; every name, account tail, PNR, amount and OTP is invented.

## What the corpus showcases

- **Transactions** — a 6-month HDFC account history (salary credits, UPI
  debits, rent), ICICI card spends & autopay, Axis card, NPS (KFintech)
  contribution, Pluxee wallet, Scapia card.
- **Bills & reminders** — credit-card bills with minimum due, autopay
  notices, LIC insurance premium, electricity bill.
- **Journeys** — an IRCTC train PNR and an IndiGo flight PNR.
- **Deliveries** — Amazon and Blue Dart with tracking ids.
- **OTPs** — bank and Amazon OTPs, including back-to-back duplicates.
- **UPI collect request**, **promos** (Myntra, Domino's, Jio, Ajio),
  a **scam** message, and **personal chats** from phone numbers.

## Prerequisites

- A running Android **emulator** (a non-Google-API image, so `adb root`
  works; the corpus was built on `emulator-5554`).
- `adb` on your PATH, `python3` (no other dependencies).
- ClearSMS installed on the emulator and granted the **default SMS app**
  role (needed for it to receive, categorize and notify).

## Happy path (two commands)

```bash
# optional clean slate: clears the app and deletes ALL provider SMS rows
python3 scripts/demo/seed.py --wipe --yes-i-know

# backfill the history with real dates, then deliver the 5 most recent
# messages through the emulator console (real receive/notify pipeline)
python3 scripts/demo/seed.py --backfill --live 5
```

Open the app: the inbox, Finance dashboard and Alerts view populate from
the seeded corpus (a catch-up import runs when the app holds the SMS role).

## How seeding works

- `--live N` uses `adb emu sms send`, so messages arrive "now", unread,
  through the app's real SmsReceiver — notifications fire, OTPs get the
  big copyable treatment.
- `--backfill` runs `adb root` and inserts rows straight into
  `content://sms` with dates computed from each message's `days_ago`
  (a float, so the corpus stays relatively fresh no matter when you run
  it), preserving read state and direction.
- Bodies never contain `:` — the `content insert --bind` parser would
  mangle them. This is enforced at extraction time; `seed.py` warns and
  skips if one sneaks in.

## Safety design

`seed.py` **refuses any device whose serial does not start with
`emulator-`**. Seeding writes to the shared SMS provider, and `--wipe`
deletes data — pointing this at a real phone must be impossible, which is
why the check is on the serial itself and not just a default.

Re-running the seeder **duplicates rows** (the SMS provider has no unique
key). Use `--wipe --yes-i-know` first for a clean slate.

## Reusing the corpus for rule work

The same JSONL replays through the rule-coverage audit — it only needs the
`sender`/`body` fields and ignores the rest:

```bash
python3 scripts/audit_rule_coverage.py scripts/demo/messages.jsonl
```

Field schema, one JSON object per line, sorted oldest first
(`days_ago` descending):

| field | type | meaning |
| --- | --- | --- |
| `sender` | string | sender id or phone number |
| `body` | string | message text (synthetic, never contains `:`) |
| `days_ago` | float | age relative to "now" at seed time |
| `read` | 0/1 | read flag for backfilled rows |
| `direction` | `"in"`/`"out"` | inbox or sent |
