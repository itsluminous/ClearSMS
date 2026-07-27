#!/usr/bin/env python3
"""Audit bundled rule coverage against a real SMS corpus.

Replays the app's first two categorization stages over a corpus of messages
and reports how many are confidently categorized:

  1. Bundled rules (``app/src/main/assets/default_rules.json``), evaluated
     with the exact RuleEngine semantics: rules sorted by priority descending,
     first match wins; a rule matches when ALL present conditions hold —
     ``sender_pattern`` found in the sender, ``body_pattern`` found in the
     body (``Regex.find`` semantics, i.e. match anywhere), every
     ``body_must_contain`` term present (case-insensitive) and no
     ``body_must_not_contain`` term present. Only the first
     ``MAX_EVAL_BODY_LENGTH`` (1000) characters of the body are evaluated,
     mirroring ``MessageCategorizer``.
  2. Sender-ID directory (``app/src/main/assets/sender_ids.db``): the sender
     is uppercased, a two-letter TRAI route prefix (``XY-``) and a
     one-letter content-type suffix (``-S``/``-P``/``-T``/``-G``) are
     stripped, and both the stripped and raw forms are looked up.

Messages matched by neither stage are grouped by normalized sender and by
body "shape" (digits masked, whitespace collapsed) so the highest-value
missing rules are easy to spot.

Input is either a JSONL file (one ``{"sender": ..., "body": ...}`` object
per line) or ``--from-device``, which pulls the corpus from a connected
Android device via ``adb shell content query --uri content://sms``.

PRIVACY: output is redacted by default — digits in printed examples are
masked as ``X`` and bodies are truncated. Nothing is ever written back to
the repository; keep raw corpora outside the repo (e.g. under /tmp).

Exit status is non-zero when coverage falls below ``--min-coverage`` so the
tool can gate CI or pre-release checks.

Examples:
    python3 scripts/audit_rule_coverage.py --from-device
    python3 scripts/audit_rule_coverage.py corpus.jsonl --min-coverage 70
    python3 scripts/audit_rule_coverage.py corpus.jsonl --top 40 --no-redact
"""

import argparse
import collections
import json
import os
import re
import sqlite3
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_RULES = os.path.join(REPO, "app/src/main/assets/default_rules.json")
DEFAULT_SENDER_DB = os.path.join(REPO, "app/src/main/assets/sender_ids.db")

# Mirrors MessageCategorizer.MAX_EVAL_BODY_LENGTH.
MAX_EVAL_BODY_LENGTH = 1000

# Mirrors SenderIdStore's TRAI header normalization.
TRAI_PREFIX = re.compile(r"^[A-Z]{2}-")
TRAI_SUFFIX = re.compile(r"-[SPTG]$")

ADB_ROW = re.compile(r"^Row: \d+ address=(.*?), body=(.*), date=(\d*)$", re.DOTALL)


# --------------------------------------------------------------------------
# Corpus loading
# --------------------------------------------------------------------------


def parse_adb_dump(text):
    """Parse ``adb shell content query`` output tolerantly.

    Rows look like ``Row: N address=..., body=..., date=...`` but bodies can
    contain embedded newlines and commas, so records are re-assembled by
    splitting on ``Row: N address=`` boundaries and anchoring on the trailing
    ``, date=<millis>`` of each record.
    """
    messages = []
    record = []
    boundary = re.compile(r"^Row: \d+ address=")
    for line in text.splitlines():
        if boundary.match(line) and record:
            messages.extend(_finish_record("\n".join(record)))
            record = []
        record.append(line)
    if record:
        messages.extend(_finish_record("\n".join(record)))
    return messages


def _finish_record(chunk):
    m = ADB_ROW.match(chunk)
    if not m:
        return []
    sender, body = m.group(1), m.group(2)
    if sender == "NULL" or body == "NULL":
        return []
    return [{"sender": sender, "body": body}]


def load_corpus_jsonl(path):
    messages = []
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
                messages.append({"sender": str(obj["sender"]), "body": str(obj["body"])})
            except (json.JSONDecodeError, KeyError) as e:
                print(f"warning: skipping line {lineno}: {e}", file=sys.stderr)
    return messages


def load_corpus_from_device(adb="adb"):
    cmd = [adb, "shell", "content", "query", "--uri", "content://sms",
           "--projection", "address:body:date"]
    out = subprocess.run(cmd, capture_output=True, text=True, check=True,
                         errors="replace").stdout
    return parse_adb_dump(out)


# --------------------------------------------------------------------------
# Stage 1: rule engine (exact RuleEngine semantics)
# --------------------------------------------------------------------------


class RuleMatcher:
    def __init__(self, rules_path):
        with open(rules_path, encoding="utf-8") as f:
            doc = json.load(f)
        self.rules = sorted(doc["rules"], key=lambda r: -r["priority"])
        self._cache = {}

    def _compiled(self, pattern):
        rx = self._cache.get(pattern)
        if rx is None:
            try:
                rx = re.compile(pattern)
            except re.error:
                rx = False  # invalid patterns are skipped, like the engine
            self._cache[pattern] = rx
        return rx or None

    def match(self, sender, body):
        """Returns the first matching rule id (priority desc), or None."""
        body = body[:MAX_EVAL_BODY_LENGTH]
        lower = body.lower()
        for rule in self.rules:
            m = rule.get("match", {})
            sp = m.get("sender_pattern")
            if sp is not None:
                rx = self._compiled(sp)
                if rx is None or not rx.search(sender):
                    continue
            bp = m.get("body_pattern")
            if bp is not None:
                rx = self._compiled(bp)
                if rx is None or not rx.search(body):
                    continue
            if any(t.lower() not in lower for t in m.get("body_must_contain", [])):
                continue
            if any(t.lower() in lower for t in m.get("body_must_not_contain", [])):
                continue
            return rule["id"]
        return None


# --------------------------------------------------------------------------
# Stage 2: sender-ID directory
# --------------------------------------------------------------------------


class SenderDirectory:
    def __init__(self, db_path):
        self.conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        self._cache = {}

    def lookup(self, sender):
        raw = sender.strip().upper()
        if raw in self._cache:
            return self._cache[raw]
        result = None
        for candidate in normalize_candidates(raw):
            row = self.conn.execute(
                "SELECT name, category FROM sender_ids WHERE sender_id = ?",
                (candidate,),
            ).fetchone()
            if row:
                result = row
                break
        self._cache[raw] = result
        return result


def normalize_candidates(raw):
    stripped = TRAI_SUFFIX.sub("", TRAI_PREFIX.sub("", raw))
    if stripped != raw and stripped:
        return [stripped, raw]
    return [raw]


# --------------------------------------------------------------------------
# Grouping and redaction
# --------------------------------------------------------------------------


def redact(text, limit=110):
    masked = re.sub(r"\d", "X", text)
    masked = re.sub(r"\s+", " ", masked).strip()
    return masked[:limit] + ("…" if len(masked) > limit else "")


def normalized_sender(sender):
    raw = sender.strip().upper()
    stripped = normalize_candidates(raw)[0]
    digits = sum(c.isdigit() for c in stripped)
    if digits >= 7 and digits >= len(stripped) - 3:
        return "<phone-number>"
    return stripped


def body_shape(body):
    """A stable digit-masked fingerprint of the start of the body."""
    masked = re.sub(r"\d+", "N", body[:120].lower())
    masked = re.sub(r"[^a-z<>@./-]+|n(?=[^a-z])|n$", " ", masked)
    return " ".join(masked.split()[:8])


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser(
        description=__doc__.split("\n\n")[0],
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("corpus", nargs="?", help="JSONL corpus file: one "
                    '{"sender":..., "body":...} object per line')
    ap.add_argument("--from-device", action="store_true",
                    help="pull the corpus from a connected device via adb")
    ap.add_argument("--adb", default="adb", help="adb binary (default: adb on PATH)")
    ap.add_argument("--rules", default=DEFAULT_RULES,
                    help="path to default_rules.json")
    ap.add_argument("--sender-db", default=DEFAULT_SENDER_DB,
                    help="path to sender_ids.db")
    ap.add_argument("--top", type=int, default=40,
                    help="number of unmatched groups to print (default 40)")
    ap.add_argument("--min-coverage", type=float, default=0.0,
                    help="exit non-zero if coverage %% falls below this")
    ap.add_argument("--no-redact", dest="redact", action="store_false",
                    help="print examples without masking digits (NEVER use on "
                         "output that leaves your machine)")
    args = ap.parse_args()

    if args.from_device:
        messages = load_corpus_from_device(args.adb)
    elif args.corpus:
        messages = load_corpus_jsonl(args.corpus)
    else:
        ap.error("provide a corpus file or --from-device")

    if not messages:
        print("no messages parsed from the corpus", file=sys.stderr)
        return 2

    matcher = RuleMatcher(args.rules)
    directory = SenderDirectory(args.sender_db)

    rule_hits = collections.Counter()
    senderid_only = 0
    unmatched = []
    for msg in messages:
        rid = matcher.match(msg["sender"], msg["body"])
        if rid:
            rule_hits[rid] += 1
        elif directory.lookup(msg["sender"]):
            senderid_only += 1
        else:
            unmatched.append(msg)

    total = len(messages)
    by_rule = sum(rule_hits.values())
    covered = by_rule + senderid_only
    coverage = 100.0 * covered / total

    print(f"total messages:        {total}")
    print(f"matched by rule:       {by_rule} ({100.0 * by_rule / total:.1f}%)")
    print(f"matched by sender-ID:  {senderid_only} ({100.0 * senderid_only / total:.1f}%)")
    print(f"unmatched:             {len(unmatched)} ({100.0 * len(unmatched) / total:.1f}%)")
    print(f"coverage:              {coverage:.1f}%")

    print("\nper-rule hit counts:")
    for rid, count in rule_hits.most_common():
        print(f"  {count:6d}  {rid}")

    def show(title, key_fn):
        groups = collections.defaultdict(list)
        for msg in unmatched:
            groups[key_fn(msg)].append(msg)
        ranked = sorted(groups.items(), key=lambda kv: -len(kv[1]))
        print(f"\n{title} (top {args.top}):")
        for key, msgs in ranked[: args.top]:
            example = msgs[0]["body"]
            if args.redact:
                example = redact(example)
            print(f"  {len(msgs):6d}  {key!r}\n          e.g. {example}")

    show("UNMATCHED by normalized sender", lambda m: normalized_sender(m["sender"]))
    show("UNMATCHED by body shape", lambda m: body_shape(m["body"]))

    if coverage < args.min_coverage:
        print(f"\nFAIL: coverage {coverage:.1f}% < required {args.min_coverage}%",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
