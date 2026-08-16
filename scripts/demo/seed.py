#!/usr/bin/env python3
"""Seed the ClearSMS demo corpus into an Android EMULATOR.

Replays scripts/demo/messages.jsonl into a running emulator, two ways:

  --live N     send the N most recent incoming messages through the emulator
               console (`adb emu sms send`), exercising the app's real
               receive -> parse -> categorize -> notify pipeline. Live sends
               always arrive "now" and unread.
  --backfill   insert every remaining message directly into the SMS provider
               with its historical date (computed from `days_ago`), read flag
               and direction preserved. Requires `adb root`, so it only works
               on non-Google-API emulator images.

Safety: this script REFUSES to touch any device whose serial does not start
with "emulator-". Seeding writes to the shared SMS provider; running it
against a real phone must be impossible, even deliberately.

Idempotence: the SMS provider has no unique key, so re-running duplicates
every row. For a clean slate use `--wipe --yes-i-know`, which clears the app
(pm clear app.clearsms) and deletes all provider SMS rows first.

Requires only python3 and adb. No other dependencies.
"""

import argparse
import json
import shlex
import subprocess
import sys
import time
from pathlib import Path

APP_PACKAGE = "app.clearsms"
DEFAULT_CORPUS = Path(__file__).resolve().parent / "messages.jsonl"


def run(cmd, check=True, capture=True):
    return subprocess.run(
        cmd, check=check, capture_output=capture, text=True
    )


def adb(serial, *args, check=True):
    return run(["adb", "-s", serial, *args], check=check)


def require_emulator(serial):
    if not serial.startswith("emulator-"):
        sys.exit(
            f"REFUSING to run against '{serial}': this script only seeds "
            "emulators (serial must start with 'emulator-'). Seeding writes "
            "to the SMS provider and must never touch a real phone."
        )
    out = run(["adb", "devices"]).stdout
    line = next(
        (l for l in out.splitlines() if l.split("\t")[0] == serial), None
    )
    if line is None or line.split("\t")[-1] != "device":
        sys.exit(f"device '{serial}' is not connected (adb devices: {out!r})")


def load_corpus(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            for field in ("sender", "body", "days_ago", "read", "direction"):
                if field not in row:
                    sys.exit(f"{path}:{n}: missing field '{field}'")
            rows.append(row)
    # File is sorted oldest-first (days_ago descending); enforce it.
    rows.sort(key=lambda r: -float(r["days_ago"]))
    return rows


def wipe(serial):
    print(f"[wipe] pm clear {APP_PACKAGE}")
    adb(serial, "shell", "pm", "clear", APP_PACKAGE)
    print("[wipe] deleting all rows from content://sms (needs root)")
    adb(serial, "root")
    time.sleep(2)  # adb restarts as root
    adb(
        serial, "shell", "content", "delete", "--user", "0",
        "--uri", "content://sms",
        check=False,  # exits non-zero when already empty
    )


def send_live(serial, row):
    # Emulator console: the sender becomes the "phone number" of the
    # incoming SMS; the message body is the rest of the line.
    adb(serial, "emu", "sms", "send", row["sender"], row["body"])


def backfill(serial, rows):
    adb(serial, "root")
    time.sleep(2)
    now_ms = int(time.time() * 1000)
    skipped = 0
    for row in rows:
        if ":" in row["body"]:
            # `content insert --bind` parses bindings on ':' and mangles
            # values containing one. The corpus is sanitized at extraction
            # time so this should never fire; skip rather than corrupt.
            print(f"[backfill] WARNING: skipping body containing ':' "
                  f"({row['sender']}: {row['body'][:40]}...)")
            skipped += 1
            continue
        date_ms = now_ms - int(float(row["days_ago"]) * 86400_000)
        msg_type = "1" if row["direction"] == "in" else "2"
        adb(
            serial, "shell", "content", "insert", "--user", "0",
            "--uri", "content://sms",
            "--bind", f"address:s:{shlex.quote(row['sender'])}",
            "--bind", f"body:s:{shlex.quote(row['body'])}",
            "--bind", f"date:l:{date_ms}",
            "--bind", f"read:i:{int(row['read'])}",
            "--bind", f"type:i:{msg_type}",
        )
    return skipped


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--serial", default="emulator-5554",
                   help="emulator serial (default: emulator-5554)")
    p.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS,
                   help="path to messages.jsonl")
    p.add_argument("--live", type=int, metavar="N", default=0,
                   help="send the N most recent incoming messages via the "
                        "emulator console (real receive path)")
    p.add_argument("--backfill", action="store_true",
                   help="root-insert the remaining messages with their "
                        "historical dates")
    p.add_argument("--wipe", action="store_true",
                   help="clear the app and delete all provider SMS rows "
                        "first (requires --yes-i-know)")
    p.add_argument("--yes-i-know", action="store_true",
                   help="confirm you understand --wipe destroys all SMS "
                        "data on the emulator")
    args = p.parse_args()

    require_emulator(args.serial)

    if args.wipe:
        if not args.yes_i_know:
            sys.exit("--wipe destroys ALL SMS data on the emulator and "
                     "clears the app. Re-run with --yes-i-know to confirm.")
        wipe(args.serial)

    if not args.live and not args.backfill:
        if args.wipe:
            return
        sys.exit("nothing to do: pass --live N and/or --backfill "
                 "(see --help)")

    rows = load_corpus(args.corpus)
    incoming = [r for r in rows if r["direction"] == "in"]
    live_rows = incoming[-args.live:] if args.live else []
    live_set = {id(r) for r in live_rows}
    backfill_rows = [r for r in rows if id(r) not in live_set]

    if args.backfill:
        skipped = backfill(args.serial, backfill_rows)
        print(f"[backfill] inserted {len(backfill_rows) - skipped} messages"
              f" ({skipped} skipped)")

    for row in live_rows:
        send_live(args.serial, row)
        time.sleep(1)  # let the app receive and parse each one
    if live_rows:
        print(f"[live] sent {len(live_rows)} messages via emulator console")

    print("done. Note: re-running duplicates rows (no unique key in the "
          "SMS provider); use --wipe --yes-i-know for a clean slate.")


if __name__ == "__main__":
    main()
