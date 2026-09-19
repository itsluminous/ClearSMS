package app.clearsms

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source contract for the standard sms-link handoff (issue #32) and the
 * default-SMS-app role filters it must not disturb.
 *
 * Two distinct manifest filters carry the four schemes, for two distinct
 * reasons:
 * 1. SEND/SENDTO on sms/smsto/mms/mmsto is what the default-SMS-app ROLE
 *    check requires - weaken it and the app can no longer be the default
 *    messenger.
 * 2. VIEW + BROWSABLE on the same schemes is what BROWSERS fire for a
 *    tapped `sms:` hyperlink (they use ACTION_VIEW, which the SENDTO
 *    filter never matches, and require BROWSABLE to hand off to an app).
 *
 * And the whole handoff only works for an ALREADY-RUNNING app because the
 * activity is singleTop: without it a link tap builds a second activity
 * instance instead of delivering to onNewIntent (the issue-#8 class of bug).
 */
class SmsUriSchemeConventionTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    /** The intent-filter blocks declared on the MainActivity element. */
    private fun mainActivityFilters(): List<String> {
        val activity =
            manifest
                .substringAfter("<activity")
                .substringBefore("</activity>")
        return Regex("""<intent-filter>.*?</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(activity)
            .map { it.value }
            .toList()
    }

    private fun String.declaresAllFourSchemes(): Boolean =
        listOf("sms", "smsto", "mms", "mmsto").all { scheme ->
            contains("""<data android:scheme="$scheme" />""")
        }

    @Test
    fun `a VIEW plus BROWSABLE filter declares all four sms-family schemes`() {
        val viewFilter =
            mainActivityFilters().firstOrNull {
                it.contains("android.intent.action.VIEW") &&
                    it.contains("android.intent.category.BROWSABLE") &&
                    it.declaresAllFourSchemes()
            }
        assertThat(viewFilter).isNotNull()
        // The VIEW filter must never absorb SENDTO: the role filter's shape
        // stays exactly what the platform's role check expects.
        assertThat(viewFilter).doesNotContain("android.intent.action.SENDTO")
    }

    @Test
    fun `the default-SMS-app role filter keeps SEND and SENDTO on all four schemes`() {
        val roleFilter =
            mainActivityFilters().firstOrNull {
                it.contains("android.intent.action.SENDTO") &&
                    it.contains("android.intent.action.SEND") &&
                    it.declaresAllFourSchemes()
            }
        assertThat(roleFilter).isNotNull()
    }

    @Test
    fun `MainActivity stays singleTop so a link into a running app reaches onNewIntent`() {
        val activity = manifest.substringAfter("<activity").substringBefore("</activity>")
        assertThat(activity).contains("""android:launchMode="singleTop"""")
        // ...and onNewIntent relays into the composition, or the tap does
        // nothing while the app is open.
        val mainActivity = File("src/main/kotlin/app/clearsms/MainActivity.kt").readText()
        assertThat(mainActivity).contains("override fun onNewIntent")
        assertThat(mainActivity).contains("laterIntents.tryEmit")
    }
}
