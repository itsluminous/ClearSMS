# Translating Clear SMS

The app ships in English and welcomes community translations, including
partial ones. Every user-visible string lives in plain Android resource XML
under [`app/src/main/res/values/`](../app/src/main/res/values/); a translation
is a copy of those files in a sibling directory named for the language. No
code changes are needed and nothing is downloaded at runtime - a translation
ships inside the APK like everything else.

**How to submit: open a pull request.** GitHub rejects `.xml` files as issue
attachments, which is what blocked the first Russian translation in
[#83](https://github.com/itsluminous/ClearSMS/issues/83) - so please do not
try to attach the files to an issue; fork the repository, add your
`values-<code>/` directory and open a PR. If git is new to you, GitHub's web
editor can create the files in your fork directly.

**A complete, real example to copy from:**
[`app/src/main/res/values-hi/`](../app/src/main/res/values-hi/) is the Hindi
translation - every one of the 765 items, in the same two-file layout as the
English. You may copy that directory as your starting template instead of the
English one: the file layout, escaping, plural blocks and placeholder handling
are already in the shape the build expects, and the English text is one
`name=` lookup away. The examples below are taken from it.

## 1. Create the directory

Copy the English files into a new directory whose name uses Android's
*resource qualifier* format, not a bare language name:

| Language | Directory |
| --- | --- |
| Hindi (shipped) | `values-hi` |
| Russian | `values-ru` |
| German | `values-de` |
| Brazilian Portuguese | `values-pt-rBR` (region prefixed with `r`) |
| Serbian in Latin script | `values-b+sr+Latn` (BCP 47 form, `b+` prefix) |

The English strings are split by *whether they are translated*, not by
feature. There are three files under `app/src/main/res/values/`, and you copy
two of them:

```
strings.xml              everything the user sees in the app - copy this
strings_platform.xml     text shown by Android itself (notification channel
                         names and descriptions in system Settings, launcher
                         shortcuts, delivery status) - copy this too; it is
                         separate only because Android's own UI gives these
                         strings tighter length limits than the app does
strings_notranslate.xml  the product name and URLs, every one marked
                         translatable="false" - do NOT copy this
```

Leave `colors.xml` and `themes.xml` alone - they are not strings. Keep the
same `name="..."` attributes; only the text between the tags changes. You may
delete any `<string>` you have not translated yet (see "Partial
translations"), but never rename one or invent a new name: Android Lint's
`ExtraTranslation` check fails the build for a name that does not exist in
English, because it is almost always a typo or a leftover from a string that
was since removed.

**File names inside your directory do not matter - string names do.** Android
reads every `.xml` file in `values-<code>/` and resolves each string by its
`name`; the file a string sits in is invisible to it. We checked this
directly: strings taken from three different English files, put into a single
`values-ru/anything_at_all.xml`, all resolved as Russian in the built APK. So
you may keep everything in one file, mirror the English split, or use any
layout you find convenient. In particular, a translation written against the
earlier seven-file English layout (`strings_ui.xml`, `strings_bin.xml`,
`strings_selection.xml` and so on) is still completely valid and needs no
renaming or reshuffling - as long as each `name` exists in English, it is
picked up.

## 2. Plural quantities - the thing most likely to go wrong

The English `<plurals>` declare only `one` and `other`, because that is all
English needs. **Your language may need more**, and Android picks the item
by your language's [CLDR plural rules](https://www.unicode.org/cldr/charts/latest/supplemental/language_plural_rules.html),
not by English's. Supply exactly the quantities your language uses; a
quantity your language does not use is silently ignored, and a quantity it
does use but you did not provide falls back to `other`, which reads wrongly.

Hindi, like English, uses only `one` and `other` - so the shipped
`values-hi/strings.xml` mirrors the English block item for item:

```xml
<!-- English (values/strings.xml) -->
<plurals name="settings_muted_senders_summary">
    <item quantity="one">%1$d muted sender - messages arrive, nothing notifies</item>
    <item quantity="other">%1$d muted senders - messages arrive, nothing notifies</item>
</plurals>

<!-- Hindi (values-hi/strings.xml) -->
<plurals name="settings_muted_senders_summary">
    <item quantity="one">%1$d म्यूट किया गया भेजने वाला - संदेश आते हैं, सूचना नहीं</item>
    <item quantity="other">%1$d म्यूट किए गए भेजने वाले - संदेश आते हैं, सूचना नहीं</item>
</plurals>
```

Russian, by contrast, uses `one`, `few`, `many` and `other`:

```xml
<!-- English (values/strings.xml) -->
<plurals name="settings_muted_senders_summary">
    <item quantity="one">%1$d muted sender - messages arrive, nothing notifies</item>
    <item quantity="other">%1$d muted senders - messages arrive, nothing notifies</item>
</plurals>

<!-- Russian (values-ru/strings.xml, or any file name you like) -->
<plurals name="settings_muted_senders_summary">
    <item quantity="one">%1$d отключённый отправитель - сообщения приходят, уведомлений нет</item>
    <item quantity="few">%1$d отключённых отправителя - сообщения приходят, уведомлений нет</item>
    <item quantity="many">%1$d отключённых отправителей - сообщения приходят, уведомлений нет</item>
    <item quantity="other">%1$d отключённого отправителя - сообщения приходят, уведомлений нет</item>
</plurals>
```

Polish uses `one`/`few`/`many`/`other`; Arabic uses all six (`zero`, `one`,
`two`, `few`, `many`, `other`); Japanese and Chinese use only `other`. Android's
reference is [Quantity strings (plurals)](https://developer.android.com/guide/topics/resources/string-resource#Plurals).

## 3. Positional arguments

Placeholders such as `%1$s`, `%2$d` and `%3$s` are filled in by the app at
runtime. **Keep every one of them, with its number and its letter**, and feel
free to reorder them to suit your grammar. Hindi puts the total before the
count, so the shipped translation swaps the two arguments and keeps both:

```xml
<!-- English (values/strings.xml) -->
<string name="settings_sort_progress">%1$d of %2$d messages</string>
<string name="finance_month_breakdown">%1$s in · %2$s out</string>

<!-- Hindi (values-hi/strings.xml): %2$d now comes first, nothing is lost -->
<string name="settings_sort_progress">%2$d में से %1$d संदेश</string>
<string name="finance_month_breakdown">%1$s आया · %2$s गया</string>
```

A missing argument, a renumbered one (`%1$s` becoming `%2$s` with no `%1$s`
left), or a changed letter (`%1$d` to `%1$s`) crashes the app the moment that
string is shown. A literal percent sign is written `%%` (as in
`%1$d%% of limit used`); a bare `%` is a format error. Apostrophes and double
quotes inside a string must be escaped (`\'`, `\"`) or the whole string wrapped
in double quotes, exactly as in the English files.

## 4. What not to translate

- Everything in `strings_notranslate.xml` - the app name and the URLs, all
  marked `translatable="false"`. Do not copy this file into your directory
  at all; a convention test fails the build if a URL or the product name is
  placed anywhere else.
- Brand and product names that appear inside otherwise translatable strings:
  *Clear SMS*, *F-Droid*, *UPI*, bank and courier names. Transliterate only
  where that is the local convention. (`avatar_sender_logo`, `%1$s logo`,
  gets the brand name as `%1$s` from data - translate the word "logo", not
  the brand.)
- The `name="..."` attributes and the `%1$s`-style placeholders.

## 5. Partial translations are welcome

You do not have to finish before opening a PR. Android resolves each string
independently and falls back to English for any name your directory does not
define, so a translation covering a third of the app is already useful and
can grow over several PRs. This is policy, not an accident: the repository's
Lint configuration ([`app/lint.xml`](../app/lint.xml)) treats a missing
translation as a warning, not an error, so a partial `values-<code>/` passes
the CI build. Simply delete the `<string>` and `<plurals>` entries you have
not translated yet rather than leaving English text in them - English text in
a translation file looks "done" in the status table and hides the gap.

## 6. Testing a translation locally

Build the app as described in [building.md](building.md) and install the
debug APK on a device or emulator (`./gradlew installDebug`). Then switch
language either way:

- **Per-app language** (Android 13+): *Settings → Apps → Clear SMS →
  Language*, or long-press the app icon → *App info → Language*. Only
  languages the app ships appear here, so your new directory must be in the
  build. The list is generated at build time (`generateLocaleConfig` in
  `app/build.gradle.kts`) from the `values-<code>/` directories that exist,
  so adding your directory is enough - nothing to register by hand.
- **Device language**: *Settings → System → Languages & input → Languages*
  and move your language to the top. This works on every Android version
  Clear SMS supports (6.0+).

Open every screen you translated - long words can overflow buttons and
notification titles that were sized for English. Run the full check before
pushing, exactly as CI will:

```bash
./gradlew ktlintCheck lintDebug testDebugUnitTest
```

Lint reports every untranslated string as a *warning* (expected for a partial
translation), as it does a plural quantity your language does not use, and
fails only on real problems: an `ExtraTranslation` name or a placeholder that
no longer matches the English one.

## 7. The F-Droid store listing (optional, separate)

The description and *What's New* text shown in the F-Droid client are not
in-app strings. They live under
[`fastlane/metadata/android/<locale>/`](../fastlane/metadata/android/) -
`short_description.txt`, `full_description.txt`, `title.txt` and
`changelogs/<versionCode>.txt` - and F-Droid picks the user's locale
directory automatically. Translating the listing is independent of
translating the app: do either, both, or one now and the other later. The
locale directory there uses the `pt-BR` form (hyphen, no `r`), unlike the
`values-pt-rBR` resource directory. See
[publishing-fdroid.md](publishing-fdroid.md) for how the metadata is picked up.

## 8. The status table in the README

The README's *Translations* section shows per-language coverage. It is
**generated** - never edit it by hand - by:

```bash
python3 scripts/translation_status.py          # rewrites the marked block in README.md
python3 scripts/translation_status.py --check  # exits 1 if the block is stale (CI/hook use)
python3 scripts/translation_status.py --print  # shows the table without touching anything
```

Run it after adding or extending a translation and commit the README change
with your PR; the unit test `TranslationStatusTest` recomputes the numbers
from the resource files and fails the build if the README disagrees.

**How coverage is counted.** The denominator is every translatable item in
English - each `<string>`, `<plurals>` and `<string-array>` not marked
`translatable="false"`, with a `<plurals>` counting as *one* item however many
quantities it has. The numerator is how many of those names your directory
defines. The percentage is rounded down, so `100%` means every item is present.

**Coverage measures presence, not quality.** The script only knows whether a
name exists in your directory. A string you copied but left in English counts
exactly like a translated one, which is why section 5 asks you to delete
untranslated entries rather than keep English placeholders. Only a directory
whose qualifier is a language counts as a translation: `values-night`,
`values-v31`, `values-land` and similar configuration directories are not
languages and never appear in the table.
