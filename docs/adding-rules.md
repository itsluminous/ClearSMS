# Adding a rule: a walkthrough

This is the hands-on companion to [CONTRIBUTING.md](../CONTRIBUTING.md), which is
the field-by-field reference. Here we take one real, mis-categorized message and
turn it into a shipped rule.

You do **not** need to write Kotlin, build the app, or own an Android device to
contribute a rule. Rules are JSON.

---

## 1. Find a message the app gets wrong

Two ways:

**From your own phone (best).** The audit script replays the bundled rules against
a real inbox and tells you exactly what is falling through:

```bash
python3 scripts/audit_rule_coverage.py --from-device
```

Read the **unmatched** groups and the **`generic-*` rule breakdown**: those are the
gaps. Digits are masked by default, but the output can still contain names and
URLs - read it before pasting it anywhere. Full instructions, including enabling
USB debugging, are in the README under
*Finding missing rules using your own messages*.

**From a single message.** If you just have one SMS in front of you that landed in
the wrong pill, that is enough to write a rule.

## 2. Decide what the message *is*

Before touching a regex, answer three questions:

| Question | Where it lands |
|---|---|
| Did money actually move? | a transaction (`sub_category: transaction`) |
| Is money *going to be* owed? | a reminder (`bill`, and a `due_date`) |
| Neither - it is a notice? | `important` with a descriptive sub-category |

Getting this wrong is the most common mistake. A "your bill of ₹1,178 is due on
10-Jun" message is a **reminder**, not a transaction - nothing has been paid yet.
A "payment failed" message is **neither**: no money moved.

## 3. Pick the file

Rules live under `rules/<region>/<domain>/<institution>.json`:

```
rules/india/banks/hdfc.json          rules/india/telecom/airtel.json
rules/india/wallets/paytm.json       rules/india/utilities/electricity.json
rules/india/ecommerce/amazon.json    rules/india/generic/transactions.json
```

Add to an existing institution file when one exists - one institution per file,
with related debit/credit/balance rules grouped together. Only create a new file
for a genuinely new institution.

Put institution-specific rules in the institution's file, not in `generic/`. The
`generic-*` rules are a deliberate last-resort safety net; every message they
catch is a rule someone hasn't written yet.

## 4. Write the rule

Say we get this (digits altered):

```
Rs.55.00 debited from HDFC Bank A/c XX9382 on 30-07-26 to VPA merchant@okhdfc.
Ref 657735305495. Avl Bal Rs.4,120.00
```

```json
{
  "id": "hdfc-upi-debit-01",
  "name": "HDFC UPI debit",
  "priority": 120,
  "match": {
    "sender_pattern": "(?i)HDFCBK",
    "body_must_contain": ["debited"],
    "body_pattern": "debited\\s+from\\s+HDFC\\s+Bank\\s+A/c\\s+[Xx*]*(\\d{4})[^\\n]{0,40}?Rs\\.?\\s*([\\d,]+(?:\\.\\d{1,2})?)",
    "guards_none": ["otp_mention", "settled_payment"]
  },
  "action": {
    "category": "important",
    "sub_category": "transaction",
    "extract": {
      "account_last4": "$1",
      "amount": "$2",
      "type": "debit",
      "bank": "HDFC Bank"
    }
  },
  "contributed_by": "your-github-username"
}
```

Points worth copying:

- **`amount` needs no parsing instructions.** The app knows that key means an
  amount and handles `1,23,456.78` itself. Same for `due_date`, `merchant` and
  `type`. See *Typed extracts* in CONTRIBUTING.md.
- **`guards_none` beats hand-rolled exclusions.** `["otp_mention"]` is better than
  `body_must_not_contain: ["OTP","otp"]` - the guard is maintained centrally and
  already knows the phrasings. Guard ids come from `rules/guards.json` (the
  app's own guards) and `rules/rule_guards.json` (guards written for rules to
  use, such as `otp_mention`).
- **`priority`**: institution-specific rules sit at 100–500, generic ones ≤ 50,
  scam checks ~600. Higher wins.

## 5. Write patterns that cannot hang the app

This matters more than it sounds. Rule patterns run against **every incoming
SMS**, and a badly shaped regex does not merely run slowly - it can hang for
minutes. This project shipped one once: a pattern wrapped in `.*` took **423
seconds** on a single long message. Patterns are now validated at load and a
pattern breaking these rules is rejected and logged:

- **No leading or trailing `.*` / `[\s\S]*`.** The engine already searches
  anywhere in the body, so wrappers add nothing and are what caused the 423 s
  bug. Write `debited\s+from` - never `.*debited.*from.*`.
- **No nested unbounded quantifiers** - no `(\d+)*`, `(\s*\w*)+`.
- **Bound your gaps.** Use `[^\n]{0,40}?` rather than `.*` between two anchors.
- **No variable-length lookbehind**, and `(?i)` is the only inline flag.
- Escape literals: `A/c`, `Rs\.`, `\$`.

Sanity-check timing before you submit:

```bash
python3 scripts/audit_rule_coverage.py corpus.jsonl   # coverage + per-rule hits
```

## 6. Test it

Two things to verify, both offline:

**It matches what you meant, and nothing else.** Re-run the audit script and
confirm your message moved out of the unmatched/generic buckets, and that
coverage went up rather than sideways.

**It does not steal other messages.** The most damaging rules are over-broad
ones. Check a near-miss deliberately: for the rule above, an *OTP* message from
HDFC mentioning "debited" must not match - that is what `guards_none` is for.

If you can run the app's tests, add a fixture to the matching
`app/src/test/kotlin/app/clearsms/data/rules/*RulesTest.kt`, using a **synthetic**
message you wrote yourself, with one assertion that it matches and one that a
near-miss does not:

```bash
./gradlew testDebugUnitTest --tests "*RulesTest*"
```

Never put a real message from your own inbox in a test.

## 7. Regenerate the bundled asset

The app ships one merged file. After editing anything under `rules/`:

```bash
python3 scripts/audit_rule_coverage.py corpus.jsonl   # validates ids/patterns
```

`app/src/main/assets/default_rules.json` must be the union of `rules/**` - a unit
test enforces this, so commit both your rule file and the regenerated asset.

## 8. Open the PR

Include:

- what the message class is and why it was wrong before;
- a **redacted** sample (mask digits as `X`; strip names, emails, order refs);
- the coverage numbers before and after, if you ran the audit.

Never include a real account number, phone number, balance or name - in the rule,
the tests, or the PR description. Rules must contain only generic patterns and
public brand names.

---

## Other things you can contribute without writing code

| You want to… | Edit |
|---|---|
| Map a sender ID to a company | `rules/sender_ids/` (+ rebuild the `.db`, see CONTRIBUTING.md) |
| Fix a wrong sender-directory entry | `rules/sender_ids/corrections.json` |
| Add a brand's colour/logo identity | `rules/brands/brands.json` |
| Categorize a merchant (e.g. a new food app) | `rules/tables/merchant_categories.json` |
| Add a courier | `rules/tables/couriers.json` |
| Add a biller | `rules/tables/billers.json` |
| Broaden a guard's phrasings | `rules/guards.json`, `rules/rule_guards.json` |
| Tune how a reminder type is recognized | `rules/tables/reminder_evidence.json` |

All of these are plain JSON with a bundled asset copy kept identical by a test.

## What is *not* data

Some things deliberately stay in Kotlin, and a rule cannot change them: the
amount grammar, date-format normalization, merchant cleanup, how competing
reminder types are scored, transaction de-duplication, and the guardrails that
decide what becomes an account. Rules supply **knowledge**; the app supplies
**algorithms and safety**. If a fix seems to need one of those changed, say so in
an issue - it is a code change, not a rule change.
