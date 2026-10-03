package app.clearsms.testing

import java.io.File

/**
 * The default-locale (English) string resource files, for convention tests
 * that read them as text and assert a string's name or value.
 *
 * WHY: tests used to hardcode `File("src/main/res/values/strings_ui.xml")`
 * and friends, so re-splitting the files broke sixteen of them at once. The
 * split is by TRANSLATABILITY, not by feature (see `docs/translating.md`):
 * `strings.xml` is everything user-facing, `strings_platform.xml` the text
 * Android itself shows (notification channels and the like), and
 * `strings_notranslate.xml` the product name and URLs. Which file a string
 * sits in is not part of any contract - its NAME is - so tests should read
 * [translated] and assert on names and values, reaching for one file only
 * when the test is about that file's own rule.
 */
object DefaultStrings {
    /** `app/src/main/res/values`, whether the JVM runs from the module or the repo root. */
    val valuesDir: File =
        listOf("src/main/res/values", "app/src/main/res/values")
            .map(::File)
            .first { it.isDirectory }

    /** `strings.xml` - every user-facing string that is translated. */
    val ui: String get() = file("strings.xml").readText()

    /** `strings_platform.xml` - text Android's own UI shows (channels, shortcuts, delivery status). */
    val platform: String get() = file("strings_platform.xml").readText()

    /** `strings_notranslate.xml` - the product name and URLs; every entry is `translatable="false"`. */
    val noTranslate: String get() = file("strings_notranslate.xml").readText()

    /** All translated default-locale strings as one text, for "this string exists" assertions. */
    val translated: String get() = ui + platform

    fun file(name: String): File = File(valuesDir, name)
}
