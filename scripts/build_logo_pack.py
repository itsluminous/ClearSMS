#!/usr/bin/env python3
"""Build the optional Clear SMS sender logo pack from MIT-licensed sources.

The Clear SMS repository and APK deliberately ship NO third-party logo
artwork: bank and merchant logos are trademarks of their owners, and a
third party's MIT licence covers their packaging of the files, not the
marks themselves. This script instead assembles an OPTIONAL, user-installed
pack (`clearsms-logo-pack.zip`) as a release artifact. Users import it via
Settings -> Appearance -> Sender logo pack.

Upstream sources (both MIT, pinned to exact commits for reproducibility):

  1. https://github.com/auraveni/global-bank-logos
     In-repo SVG bank logos. Converted to PNG when an SVG rasterizer
     (rsvg-convert or the cairosvg Python module) is available.
  2. https://github.com/cashfree/payments-icons-library
     An MIT-licensed JS library that maps payment-instrument names to
     PNG icons hosted on Cashfree's public CDN. We download the 128 px
     PNGs at the URLs the pinned commit of that library constructs.

Output file names follow the app's logo-pack matching rules
(app/src/main/kotlin/app/clearsms/ui/components/LogoPack.kt): the lookup
key is the lowercase base file name, and the curated brand key from
rules/brands/brands.json is tried first — so one `<brandkey>.png` per
covered brand is sufficient. PNG only, max 256x256, far under the app's
2 MB per-file cap and 500-entry zip cap.

Usage:
  python3 scripts/build_logo_pack.py --out DIR [--dry-run]

Requires: python3 (stdlib only), git, network access. Optional:
rsvg-convert or cairosvg for the SVG source. Exits non-zero on failure.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BRANDS_JSON = REPO_ROOT / "rules" / "brands" / "brands.json"

# ---------------------------------------------------------------------------
# Pinned upstreams. Bump the SHAs deliberately; never track a moving branch.
# ---------------------------------------------------------------------------
AURAVENI_REPO = "https://github.com/auraveni/global-bank-logos.git"
AURAVENI_COMMIT = "ad33060ca976397a9fcb46dd40c2d77bce5ce7e1"

CASHFREE_REPO = "https://github.com/cashfree/payments-icons-library.git"
CASHFREE_COMMIT = "39862391f964bbb263008b5a1d9802be6589864c"
# IMAGE_URL constant from src/utility.js at CASHFREE_COMMIT (verified at
# clone time below so a drifting pin fails loudly instead of silently).
CASHFREE_CDN = "https://cashfreelogo.cashfree.com/assets_images/pg"

MAX_TILE = 256  # px, longest edge for rasterized SVGs
MAX_BYTES = 2 * 1024 * 1024  # the app skips files larger than this

# brand key (rules/brands/brands.json) -> SVG path inside global-bank-logos.
AURAVENI_MAP = {
    "hdfc": "assets/bank/indian-bank/hdfc.svg",
    "icici": "assets/bank/indian-bank/icici.svg",
    "sbi": "assets/bank/indian-bank/sbi.svg",
    "axis": "assets/bank/indian-bank/axis.svg",
    "kotak": "assets/bank/indian-bank/kotak.svg",
    "pnb": "assets/bank/indian-bank/pnb.svg",
    "bob": "assets/bank/indian-bank/bob.svg",
    "canara": "assets/bank/indian-bank/canara.svg",
    "union": "assets/bank/indian-bank/ubi.svg",
    "idfcfirst": "assets/bank/indian-bank/idfc.svg",
    "indusind": "assets/bank/indian-bank/indus.svg",
    "yesbank": "assets/bank/indian-bank/yes.svg",
    "federal": "assets/bank/indian-bank/federal.svg",
    "citi": "assets/bank/international-bank/citi.svg",
    "hsbc": "assets/bank/international-bank/hsbc.svg",
    "amex": "assets/bank/international-bank/american-express.svg",
    "paytm": "assets/bank/indian-bank/paytm.svg",
    "jio": "assets/bank/indian-bank/jio.svg",
    "indiapost": "assets/bank/indian-bank/indiapost.svg",
}

# brand key -> (payment mode, icon key) per the pinned library's
# nameMapping.js / utility.js. URL: {CDN}/{mode}/128/{icon}.png
CASHFREE_MAP = {
    "hdfc": ("nb", "hdfc"),
    "icici": ("nb", "icici"),
    "sbi": ("nb", "sbi"),
    "axis": ("nb", "axis"),
    "kotak": ("nb", "kotak"),
    "pnb": ("nb", "pnbc"),
    "bob": ("nb", "bobc"),
    "canara": ("nb", "canara"),
    "union": ("nb", "union"),
    "idfcfirst": ("nb", "idfc"),
    "indusind": ("nb", "indusind"),
    "yesbank": ("nb", "yes"),
    "federal": ("nb", "federal"),
    "citi": ("nb", "citi"),
    "amex": ("card", "amex"),
    "paytm": ("wallet", "paytm"),
    "phonepe": ("wallet", "phonepe"),
    "amazonpay": ("wallet", "amazon"),
    "amazon": ("wallet", "amazon"),
    "airtel": ("wallet", "airtel"),
    "jio": ("wallet", "jio"),
    "ola": ("wallet", "ola"),
    "payzapp": ("wallet", "payzapp"),
    "cred": ("upi", "credpay"),
}

TRADEMARK_NOTICE = """\
Trademark notice
----------------
All bank, wallet and merchant logos in this pack are trademarks of their
respective owners. The MIT licences above cover the upstream projects'
packaging and distribution of these files, NOT the marks themselves.
This pack is provided solely so that you can identify senders in your own
SMS inbox. Clear SMS ships no logo artwork inside the app or its source
repository; installing this pack is your choice.
"""


def log(msg: str) -> None:
    print(msg, flush=True)


def fail(msg: str) -> None:
    print(f"ERROR: {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


def run(cmd: list[str], cwd: Path | None = None) -> None:
    proc = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if proc.returncode != 0:
        fail(f"command failed: {' '.join(cmd)}\n{proc.stderr.strip()}")


def clone_pinned(url: str, commit: str, dest: Path) -> None:
    """Fetch exactly one pinned commit (no branch tracking)."""
    dest.mkdir(parents=True)
    run(["git", "init", "--quiet", str(dest)])
    run(["git", "remote", "add", "origin", url], cwd=dest)
    run(["git", "fetch", "--quiet", "--depth", "1", "origin", commit], cwd=dest)
    run(["git", "checkout", "--quiet", "FETCH_HEAD"], cwd=dest)


def is_png(data: bytes) -> bool:
    return data[:8] == b"\x89PNG\r\n\x1a\n"


def find_svg_converter() -> str | None:
    """Return 'rsvg' or 'cairosvg' if an SVG rasterizer is available."""
    if shutil.which("rsvg-convert"):
        return "rsvg"
    try:
        import cairosvg  # noqa: F401

        return "cairosvg"
    except ImportError:
        return None


def svg_to_png(converter: str, svg: Path, out: Path) -> bool:
    if converter == "rsvg":
        proc = subprocess.run(
            [
                "rsvg-convert",
                "-w",
                str(MAX_TILE),
                "-h",
                str(MAX_TILE),
                "--keep-aspect-ratio",
                str(svg),
                "-o",
                str(out),
            ],
            capture_output=True,
        )
        return proc.returncode == 0 and out.exists()
    import cairosvg

    try:
        cairosvg.svg2png(
            url=str(svg),
            write_to=str(out),
            output_width=MAX_TILE,
            output_height=MAX_TILE,
        )
        return out.exists()
    except Exception:
        return False


def download(url: str, timeout: int = 30) -> bytes | None:
    req = urllib.request.Request(url, headers={"User-Agent": "clearsms-logo-pack"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                return None
            return resp.read()
    except Exception:
        return None


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", required=True, help="output directory for the zip")
    ap.add_argument(
        "--dry-run",
        action="store_true",
        help="resolve sources and report coverage without writing the zip",
    )
    args = ap.parse_args()

    if not BRANDS_JSON.exists():
        fail(f"brands table not found: {BRANDS_JSON}")
    brands = json.loads(BRANDS_JSON.read_text())["brands"]
    brand_keys = [b["key"] for b in brands]
    brand_names = {b["key"]: b["name"] for b in brands}
    log(f"Loaded {len(brand_keys)} brands from {BRANDS_JSON.relative_to(REPO_ROOT)}")

    converter = find_svg_converter()
    if converter:
        log(f"SVG rasterizer: {converter}")
    else:
        log(
            "No SVG rasterizer (rsvg-convert / cairosvg) found — "
            "SVG source will be skipped; using CDN PNGs where available.",
        )

    out_dir = Path(args.out)
    work = Path(tempfile.mkdtemp(prefix="clearsms-logo-pack-"))
    try:
        log(f"Cloning global-bank-logos @ {AURAVENI_COMMIT[:12]} ...")
        aura = work / "global-bank-logos"
        clone_pinned(AURAVENI_REPO, AURAVENI_COMMIT, aura)
        aura_license = (aura / "LICENSE").read_text()

        log(f"Cloning payments-icons-library @ {CASHFREE_COMMIT[:12]} ...")
        cash = work / "payments-icons-library"
        clone_pinned(CASHFREE_REPO, CASHFREE_COMMIT, cash)
        cash_license = (cash / "LICENSE").read_text()
        utility_js = (cash / "src" / "utility.js").read_text()
        if CASHFREE_CDN not in utility_js:
            fail(
                "pinned payments-icons-library no longer declares the expected "
                f"IMAGE_URL ({CASHFREE_CDN}); refusing to guess CDN URLs",
            )

        images = work / "images"
        images.mkdir()
        covered: dict[str, str] = {}  # brand key -> provenance line

        for key in brand_keys:
            # Prefer the in-repo SVG (vector, rasterized at 256 px).
            svg_rel = AURAVENI_MAP.get(key)
            if svg_rel and converter:
                svg = aura / svg_rel
                if svg.exists():
                    png = images / f"{key}.png"
                    if svg_to_png(converter, svg, png) and is_png(png.read_bytes()):
                        covered[key] = (
                            f"global-bank-logos@{AURAVENI_COMMIT[:12]} {svg_rel}"
                        )
                        continue
                    png.unlink(missing_ok=True)
            # Fall back to the CDN PNG referenced by the pinned library.
            cdn = CASHFREE_MAP.get(key)
            if cdn:
                mode, icon = cdn
                url = f"{CASHFREE_CDN}/{mode}/128/{icon}.png"
                data = download(url)
                if data and is_png(data) and len(data) <= MAX_BYTES:
                    (images / f"{key}.png").write_bytes(data)
                    covered[key] = (
                        f"payments-icons-library@{CASHFREE_COMMIT[:12]} {url}"
                    )

        uncovered = [k for k in brand_keys if k not in covered]
        log(f"\nCovered {len(covered)}/{len(brand_keys)} brands:")
        for key in sorted(covered):
            log(f"  {key}.png <- {covered[key]}")
        log(f"\nNo logo source for {len(uncovered)} brands (brand tiles apply):")
        log("  " + ", ".join(sorted(uncovered)))

        if not covered:
            fail("no logos could be assembled — upstream fetch failed?")

        if args.dry_run:
            log("\nDry run: no zip written.")
            return

        manifest = ["# Clear SMS sender logo pack — manifest", ""]
        manifest.append(f"- global-bank-logos pinned commit: `{AURAVENI_COMMIT}`")
        manifest.append(f"- payments-icons-library pinned commit: `{CASHFREE_COMMIT}`")
        manifest.append("")
        manifest.append("| File | Brand | Source |")
        manifest.append("| --- | --- | --- |")
        for key in sorted(covered):
            manifest.append(f"| `{key}.png` | {brand_names[key]} | {covered[key]} |")
        manifest.append("")
        manifest.append(
            "Import via Settings -> Appearance -> Sender logo pack. Files are "
            "matched by lowercase base name against the curated brand key. "
            "See LICENSES.txt for licence and trademark information.",
        )

        licenses = [
            "Clear SMS sender logo pack — licences and notices",
            "=" * 52,
            "",
            f"Source 1: https://github.com/auraveni/global-bank-logos",
            f"Pinned commit: {AURAVENI_COMMIT}",
            "Licence (MIT):",
            "",
            aura_license,
            "-" * 52,
            "",
            f"Source 2: https://github.com/cashfree/payments-icons-library",
            f"Pinned commit: {CASHFREE_COMMIT}",
            "Icons downloaded from the public CDN URLs constructed by the",
            "pinned commit of this MIT-licensed library.",
            "Licence (MIT):",
            "",
            cash_license,
            "-" * 52,
            "",
            TRADEMARK_NOTICE,
        ]

        out_dir.mkdir(parents=True, exist_ok=True)
        zip_path = out_dir / "clearsms-logo-pack.zip"
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for png in sorted(images.glob("*.png")):
                if png.stat().st_size > MAX_BYTES:
                    log(f"  skipping oversized {png.name}")
                    continue
                zf.write(png, png.name)
            zf.writestr("MANIFEST.md", "\n".join(manifest) + "\n")
            zf.writestr("LICENSES.txt", "\n".join(licenses))

        log(f"\nWrote {zip_path} ({zip_path.stat().st_size} bytes)")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
