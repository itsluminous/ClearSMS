# The website

The project site at <https://itsluminous.github.io/ClearSMS/> is a single
self-contained page, [`site/index.html`](../site/index.html): inline CSS, an
inline SVG icon sprite, no JavaScript, no framework, no external fonts, no
third-party requests, no analytics. The app has no telemetry; neither does
its website. Screenshots are WebP copies of `docs/screenshots/*.png` under
`site/screenshots/` (the README keeps using the PNGs).

## Deploying

`.github/workflows/pages.yml` deploys `site/` to GitHub Pages. It runs on a
push to `main` that touches `site/**`, `scripts/site-apk-link.py` or the
workflow itself; on `workflow_dispatch` (with an optional `tag` input); and
daily at 05:41 UTC. The `redeploy-site` job at the end of `android.yml`
dispatches it after every `v*` release with that tag, so the Download
buttons move to the new APK.

Just before upload, `scripts/site-apk-link.py` rewrites every link marked
`data-latest-apk` from the committed `releases/latest` page to the latest
release's single `ClearSMS.apk`. It never fails the deploy: on any problem
(API error, zero or several APKs, an unexpected URL shape, an asset that
does not answer) it warns and leaves the release-page link in place.

GitHub Pages must be enabled once in the repository settings, with
**Source: GitHub Actions**. That is a settings change, not a file in this
repository.

## Truthfulness

The site must not claim more than the README's feature checklist does,
including the unchecked items. Check wording against `README.md`
(especially *Privacy Principles*) and
`fastlane/metadata/android/en-US/full_description.txt` before editing.

## Adding a language (not built yet; the recommended approach)

The page is English only today. Every user-visible string sits in two
marked places in `site/index.html`: the `<title>`, description and social
tags in `<head>` (introduced by an `i18n:` comment), and the body between
`<!-- i18n:strings-begin -->` and `<!-- i18n:strings-end -->`. The CSS
carries no words, so a translation touches nothing else.

When a second language is wanted, do this:

1. Rename `site/index.html` to `site/index.template.html` and replace each
   string with a `{{key}}` placeholder; keep the English text in
   `site/i18n/en.json`, one flat object of key to string, with the same
   keys as the app's string resources where a string exists in both (the
   hero lead, category names, settings paths), so translators can reuse the
   app's `values-<code>/strings.xml` work.
2. Add a short, dependency-free `scripts/site-build.py` (stdlib only, like
   `site-apk-link.py`) that writes `site/index.html` for English and
   `site/<code>/index.html` for every other `site/i18n/<code>.json`,
   falling back to the English string for any missing key, exactly as the
   app does for partial translations. Run it as a step in `pages.yml`
   before `site-apk-link.py`, and point `site-apk-link.py` at each
   generated page (its `data-latest-apk` rewrite is per file).
3. Each generated page sets `<html lang="<code>">`, a
   `<link rel="alternate" hreflang="...">` for every language plus
   `x-default` pointing at the English root, and a small language switcher
   in the footer. No JavaScript redirect by browser language: the English
   page stays at `/`, and search engines and people choose via the links.
4. Screenshots stay shared (`../screenshots/` from a language folder); a
   translated screenshot set is optional and, when added, goes under
   `site/screenshots/<code>/`.

Why this and not the alternatives: hand-maintained copies of the HTML drift
(a fix to the English page has to be repeated in every copy); a client-side
string swap needs JavaScript the site deliberately does not have and shows
English first; a static-site framework adds a dependency and a build the
page does not otherwise need. A fifty-line stdlib generator run in CI keeps
the committed page readable, the translation files plain JSON, and the
deployed output identical in every other respect to today's.
