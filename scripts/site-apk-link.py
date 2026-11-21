#!/usr/bin/env python3
"""Point the website's Download buttons straight at the latest release's APK.

Run by .github/workflows/pages.yml on the checked-out copy of site/index.html,
just before the site is uploaded. Every Download button in the page carries a
`data-latest-apk` marker next to its committed href, the latest-release page:

    <a ... href="https://github.com/OWNER/REPO/releases/latest" data-latest-apk>

When the latest release has exactly one .apk asset, and GitHub answers for that
asset's download URL, those hrefs are rewritten to it, so a click downloads the
APK. In every other case (the API lookup fails, there are zero or several .apk
assets, the URL is not the shape expected, the asset does not answer) the page
is left as committed, with a warning, so it still links the release page. This
script never fails the deploy for that: it exits 0 unless it was called wrongly.

The page stays static: the lookup happens here, at deploy time, never in the
visitor's browser.

Usage:
    site-apk-link.py HTML_FILE [--release-json FILE] [--no-check]

GITHUB_REPOSITORY (owner/repo) defaults to itsluminous/ClearSMS, and
GITHUB_TOKEN, when set, authenticates the API call. EXPECTED_TAG, when set (the
release workflow passes the tag it just published), makes the script wait up to
about a minute for GitHub to report that tag as the latest release, in case the
API lags just after publishing; if it never does, the script goes on with the
release GitHub calls latest. "Latest" is GitHub's own choice: the newest
non-draft, non-prerelease release, which the release page link also opens.
--release-json reads the release from a file instead of the API (for testing),
and --no-check skips the request that confirms the asset answers.
"""

import argparse
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request

MARKER = "data-latest-apk"
SAFE = re.compile(r"[A-Za-z0-9._+-]+")
# An <a> start tag; the marker attribute in one; its double-quoted href.
A_TAG = re.compile(r"<a\b[^>]*>")
MARKED = re.compile(r"\s" + MARKER + r"(?=[\s/>=])")
HREF = re.compile(r'\shref="([^"]*)"')
TIMEOUT = 20
ATTEMPTS = 3          # per request, for transient failures
EXPECTED_WAITS = 6    # times 10 s, waiting for EXPECTED_TAG to be the latest
reasons = []          # why the page kept its committed links, for the run summary


def warn(message):
    """A GitHub Actions warning annotation (plain text anywhere else)."""
    reasons.append(message)
    # Workflow-command escaping, so text from the API cannot end the annotation
    # early or start another command.
    text = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::warning title=Download link::{text}", flush=True)


def transient(error):
    if isinstance(error, urllib.error.HTTPError):
        return error.code == 429 or error.code >= 500
    return isinstance(error, (urllib.error.URLError, TimeoutError, ConnectionError))


def with_retries(call):
    """call(), retried with backoff on timeouts, resets, 429 and 5xx."""
    for attempt in range(1, ATTEMPTS + 1):
        try:
            return call()
        except Exception as error:  # noqa: BLE001 - re-raised unless transient
            if attempt == ATTEMPTS or not transient(error):
                raise
            time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


# Never follows a redirect, so the token sent to the API goes nowhere else.
OPENER = urllib.request.build_opener(_NoRedirect)


def fetch_release(repo, token):
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases/latest",
        headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "clearsms-pages",
        },
    )
    if token:
        request.add_header("Authorization", f"Bearer {token}")

    def get():
        with OPENER.open(request, timeout=TIMEOUT) as response:
            return json.load(response)
    return with_retries(get)


def latest_release(repo, token, expected):
    """The latest release; when expected is set, wait a little for it to be that tag."""
    release = fetch_release(repo, token)
    waits = 0
    while expected and isinstance(release, dict) and release.get("tag_name") != expected:
        if waits == EXPECTED_WAITS:
            warn(f"GitHub still reports {release.get('tag_name')!r} as the latest release, "
                 f"not {expected!r}; using what it reports.")
            break
        waits += 1
        time.sleep(10)
        release = fetch_release(repo, token)
    return release


def pick_apk_url(release, repo):
    """The single .apk asset's download URL, or None with a warning."""
    if not isinstance(release, dict):
        warn("The release answer is not a JSON object.")
        return None
    if release.get("draft") or release.get("prerelease"):
        warn("The latest release is a draft or a pre-release.")
        return None
    tag = release.get("tag_name")
    assets = release.get("assets")
    if not isinstance(tag, str) or not isinstance(assets, list):
        warn("The release has no tag name or no asset list.")
        return None
    apks = [
        a for a in assets
        if isinstance(a, dict) and str(a.get("name", "")).lower().endswith(".apk")
    ]
    if len(apks) != 1:
        names = ", ".join(repr(a.get("name")) for a in apks) or "none"
        warn(f"Release {tag} has {len(apks)} .apk assets ({names}); expected exactly one.")
        return None
    apk = apks[0]
    name = apk.get("name")
    url = apk.get("browser_download_url")
    size = apk.get("size")
    if apk.get("state") != "uploaded" or not isinstance(size, int) or size <= 0:
        warn(f"Asset {name!r} of {tag!r} is not fully uploaded (state {apk.get('state')!r}, size {size!r}).")
        return None
    if not SAFE.fullmatch(tag) or not SAFE.fullmatch(name):
        warn(f"Release tag or asset name has unexpected characters: {tag!r} {name!r}.")
        return None
    # Only the exact URL GitHub gives a release asset of this repository: no
    # other host, and nothing in it that needs escaping inside an HTML attribute.
    expected = f"https://github.com/{repo}/releases/download/{tag}/{name}"
    if url != expected:
        warn(f"Asset URL {url!r} is not the expected {expected!r}.")
        return None
    return url


def asset_answers(url):
    """True when GitHub serves the asset: 200, or a redirect to its storage.

    Sent without the token, and redirects are not followed."""
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "clearsms-pages"})

    def head():
        try:
            with OPENER.open(request, timeout=TIMEOUT) as response:
                return response.status
        except urllib.error.HTTPError as error:
            if error.code == 429 or error.code >= 500:
                raise
            return error.code
    try:
        status = with_retries(head)
    except urllib.error.HTTPError as error:
        status = error.code
    except (urllib.error.URLError, OSError) as error:
        warn(f"Could not reach {url}: {error}.")
        return False
    if status in (200, 301, 302, 303, 307, 308):
        return True
    warn(f"{url} answered HTTP {status}.")
    return False


def rewrite(html_path, repo, url):
    """Swap the release-page href of every marked <a> for url. Returns how many."""
    with open(html_path, encoding="utf-8", newline="") as f:
        html = f.read()
    fallback = f"https://github.com/{repo}/releases/latest"
    count = 0
    skipped = 0

    def swap(match):
        nonlocal count, skipped
        tag = match.group(0)
        if not MARKED.search(tag):
            return tag
        href = HREF.search(tag)
        if not href or href.group(1) != fallback:
            skipped += 1
            return tag
        count += 1
        return tag[:href.start(1)] + url + tag[href.end(1):]

    html = A_TAG.sub(swap, html)
    if skipped:
        warn(f"{skipped} {MARKER} link(s) in {html_path} do not have the href {fallback}; left as they are.")
    if count == 0:
        warn(f"No {MARKER} link in {html_path} has the href {fallback}, so nothing was changed.")
        return 0
    directory = os.path.dirname(os.path.abspath(html_path))
    # Write beside the page, then swap it in, so a failed write never leaves a
    # half-written page (or a stray temporary file in the deployed folder).
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=directory, delete=False) as f:
        temp = f.name
    try:
        with open(temp, "w", encoding="utf-8", newline="") as f:
            f.write(html)
        os.chmod(temp, os.stat(html_path).st_mode & 0o777)
        os.replace(temp, html_path)
    finally:
        if os.path.exists(temp):
            os.remove(temp)
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("html")
    parser.add_argument("--release-json")
    parser.add_argument("--no-check", action="store_true")
    args = parser.parse_args()

    if not os.path.isfile(args.html):
        print(f"error: {args.html} is not a file", file=sys.stderr)
        return 2
    repo = os.environ.get("GITHUB_REPOSITORY") or "itsluminous/ClearSMS"
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        print(f"error: unexpected GITHUB_REPOSITORY {repo!r}", file=sys.stderr)
        return 2

    expected = os.environ.get("EXPECTED_TAG") or None
    if expected and not SAFE.fullmatch(expected):
        warn(f"Ignoring EXPECTED_TAG {expected!r}: unexpected characters.")
        expected = None

    url = None
    count = 0
    try:
        if args.release_json:
            with open(args.release_json, encoding="utf-8") as f:
                release = json.load(f)
        else:
            release = latest_release(repo, os.environ.get("GITHUB_TOKEN"), expected)
        url = pick_apk_url(release, repo)
        if url and not args.no_check and not asset_answers(url):
            url = None
        if url:
            count = rewrite(args.html, repo, url)
    except Exception as error:  # noqa: BLE001 - fail soft: the committed page still works
        warn(f"Lookup failed ({type(error).__name__}: {error}); keeping the release-page link.")
        count = 0

    if count:
        result = f"Pointed {count} Download button(s) at {url}"
    else:
        result = "Download buttons keep the release-page link: " + (" ".join(reasons) or "no reason given.")
    print(result)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        try:
            with open(summary, "a", encoding="utf-8") as f:
                f.write(result + "\n")
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
