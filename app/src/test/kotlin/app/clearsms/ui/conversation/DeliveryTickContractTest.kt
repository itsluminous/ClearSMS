package app.clearsms.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source-level contract for the bubble delivery ticks (GitHub #44). The
 * repo deliberately has no Compose UI test harness, so - same style as
 * [MessageDetailsMenuContractTest] - this pins the rendering decisions the
 * pure [DeliveryTickTest] cannot see: the tick sits in the time label's own
 * row, is drawn from the tick mapping (never from the raw status), carries
 * a spoken content description, scales with the font, and is tinted with
 * the on-bubble colour so both themes stay legible.
 */
class DeliveryTickContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    @Test
    fun `the bubble draws the tick from the pure mapping, beside the time label`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        assertThat(screen).contains("tick = DeliveryTicks.tickFor(item)")
        // Time and tick share one Row so the tick never crowds the time
        // label; the SIM tag stays in the metadata line beneath.
        val timeRow = screen.substringAfter("text = item.timeLabel,").substringBefore("DeliveryTickIcon(")
        assertThat(timeRow.length).isLessThan(300)
        assertThat(screen).contains("fun DeliveryTickIcon(")
    }

    @Test
    fun `single vs double check icons, spoken as Sent or Delivered`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        assertThat(screen).contains("if (tick == DeliveryTick.DOUBLE) Icons.Outlined.DoneAll else Icons.Outlined.Done")
        // Accessibility: never a silent icon - the state is announced.
        assertThat(screen).contains(
            "if (tick == DeliveryTick.DOUBLE) R.string.conversation_delivered else R.string.conversation_sent",
        )
        assertThat(screen).doesNotContain("contentDescription = null,\n        tint = tint")
        val strings = File("src/main/res/values/strings_ui.xml").readText()
        assertThat(strings).contains("<string name=\"conversation_sent\">Sent</string>")
        assertThat(strings).contains("<string name=\"conversation_delivered\">Delivered</string>")
    }

    @Test
    fun `NONE renders nothing, the icon scales with the font and takes the on-bubble tint`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        val icon = screen.substringAfter("fun DeliveryTickIcon(")
        assertThat(icon).contains("if (tick == DeliveryTick.NONE) return")
        // sp → dp through LocalDensity honours the user's font scale.
        assertThat(icon).contains("MaterialTheme.typography.labelSmall.fontSize")
        assertThat(icon).contains("LocalDensity.current")
        assertThat(icon).contains("tint = tint")
        // The caller passes the same colour the time label uses.
        assertThat(screen).contains("tint = textColor.copy(alpha = 0.7f)")
    }

    @Test
    fun `failed and sending keep their explicit bubble line - the tick never replaces it`() {
        val screen = source("ui/conversation/ConversationScreen.kt")
        assertThat(screen).contains("DeliveryStatus.SENDING, DeliveryStatus.FAILED ->")
        assertThat(screen).contains("stringResource(R.string.conversation_not_sent)")
        assertThat(screen).contains("MaterialTheme.colorScheme.error")
        assertThat(screen).contains("DeliveryStatus.SCHEDULED -> {")
    }
}
