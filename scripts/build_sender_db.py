#!/usr/bin/env python3
"""Build the bundled sender ID SQLite asset from a community JSON file.

Converts a sender ID directory in JSON (optionally gzip-compressed) into the
SQLite database that Clear SMS ships in ``app/src/main/assets/sender_ids.db``.

Input JSON format::

    {
      "version": "1.0",
      "generated_from": "community_data",
      "entries": {
        "SENDERID": { "name": "Company Name", "category": "important", "sub": "banking" },
        ...
      }
    }

``category`` must be ``important`` or ``promotional``. ``sub`` is optional.

Output schema::

    CREATE TABLE sender_ids (
        sender_id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        category TEXT NOT NULL,
        sub TEXT
    );
    CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
    -- meta rows: version=<version>, entry_count=<n>

Usage::

    python3 scripts/build_sender_db.py \\
        rules/sender_ids/india_sender_ids.json.gz \\
        app/src/main/assets/sender_ids.db
"""

import argparse
import gzip
import json
import os
import sqlite3
import sys

VALID_CATEGORIES = {"important", "promotional"}


def load_entries(path):
    """Load the JSON document from a plain or gzip-compressed file."""
    if path.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            return json.load(f)
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def build_db(doc, out_path):
    """Write the sender_ids SQLite database. Returns the entry count."""
    entries = doc.get("entries", {})
    version = doc.get("version", "1.0")

    if os.path.exists(out_path):
        os.remove(out_path)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)

    conn = sqlite3.connect(out_path)
    try:
        conn.execute(
            "CREATE TABLE sender_ids ("
            "sender_id TEXT PRIMARY KEY, "
            "name TEXT NOT NULL, "
            "category TEXT NOT NULL, "
            "sub TEXT)"
        )
        conn.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")

        rows = []
        for sender_id, info in entries.items():
            name = info.get("name")
            category = info.get("category")
            sub = info.get("sub")
            if not sender_id or not name:
                continue
            if category not in VALID_CATEGORIES:
                raise ValueError(
                    f"Invalid category {category!r} for sender {sender_id!r}; "
                    f"expected one of {sorted(VALID_CATEGORIES)}"
                )
            rows.append((sender_id, name, category, sub))

        conn.executemany(
            "INSERT OR REPLACE INTO sender_ids (sender_id, name, category, sub) "
            "VALUES (?, ?, ?, ?)",
            rows,
        )
        conn.execute(
            "INSERT INTO meta (key, value) VALUES (?, ?)", ("version", version)
        )
        count = conn.execute("SELECT COUNT(*) FROM sender_ids").fetchone()[0]
        conn.execute(
            "INSERT INTO meta (key, value) VALUES (?, ?)",
            ("entry_count", str(count)),
        )
        conn.commit()
        conn.execute("VACUUM")
    finally:
        conn.close()
    return count


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Convert a sender ID JSON(.gz) file into the SQLite asset "
        "bundled with Clear SMS."
    )
    parser.add_argument(
        "input_json",
        help="Path to the sender ID JSON file (plain .json or .json.gz)",
    )
    parser.add_argument(
        "output_db",
        help="Path of the SQLite database to write (e.g. "
        "app/src/main/assets/sender_ids.db)",
    )
    args = parser.parse_args(argv)

    doc = load_entries(args.input_json)
    count = build_db(doc, args.output_db)
    size = os.path.getsize(args.output_db)
    print(f"Wrote {count} sender IDs to {args.output_db} ({size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
