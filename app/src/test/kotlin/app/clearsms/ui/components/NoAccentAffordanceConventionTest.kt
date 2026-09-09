package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Convention tests (source scan, the repo pattern) for the setting-only
 * accent-stripping contract: the compose bar carries NO accent affordance
 * of any kind - the button shipped in v0.18.0 crowded the bar and was
 * removed. When *Settings → Strip accents when sending* is ON, both send
 * paths fold silently; there is no per-message button, chip, hint or
 * prompt, so nothing here can quietly creep back in.
 */
class NoAccentAffordanceConventionTest {
    private val composerBar = File("src/main/kotlin/app/clearsms/ui/components/MessageComposerBar.kt").readText()
    private val conversationScreen = File("src/main/kotlin/app/clearsms/ui/conversation/ConversationScreen.kt").readText()
    private val composeScreen = File("src/main/kotlin/app/clearsms/ui/composemsg/ComposeMessageScreen.kt").readText()
    private val stringsUi = File("src/main/res/values/strings_ui.xml").readText()

    @Test
    fun `the compose bar contains no accent affordance`() {
        // No fold plan, no strip callback, no e-grave glyph, no AccentFold
        // reference: the bar's only small indicator is the SIM slot.
        assertThat(composerBar).doesNotContain("AccentFold")
        assertThat(composerBar).doesNotContain("accentFoldPlan")
        assertThat(composerBar).doesNotContain("onAccentsStripped")
        assertThat(composerBar).doesNotContain("\"è\"")
        assertThat(composerBar).doesNotContain("compose_strip_accents")
    }

    @Test
    fun `neither compose entry point wires an accent affordance or its snackbar`() {
        for (screen in listOf(conversationScreen, composeScreen)) {
            assertThat(screen).doesNotContain("onAccentsStripped")
            assertThat(screen).doesNotContain("compose_accents_removed")
            assertThat(screen).doesNotContain("AccentFold")
        }
    }

    @Test
    fun `the affordance strings are gone from resources`() {
        assertThat(stringsUi).doesNotContain("compose_strip_accents")
        assertThat(stringsUi).doesNotContain("compose_accents_removed")
        // The settings row stays - it is now the ONLY way in.
        assertThat(stringsUi).contains("settings_strip_accents")
    }

    @Test
    fun `both send paths gate the silent fold on the settings key`() {
        // With the button gone, the DataStore setting is the single switch:
        // the immediate path AND the scheduled path must each read
        // uiPrefs.stripAccents and fold with AccentFold.foldIfItSaves.
        val gate = "if (uiPrefs.stripAccents.first()) AccentFold.foldIfItSaves(body) else body"
        val smsSender = File("src/main/kotlin/app/clearsms/sms/SmsSender.kt").readText()
        val scheduler = File("src/main/kotlin/app/clearsms/work/MessageScheduler.kt").readText()
        assertThat(smsSender).contains(gate)
        assertThat(scheduler).contains(gate)
    }
}
