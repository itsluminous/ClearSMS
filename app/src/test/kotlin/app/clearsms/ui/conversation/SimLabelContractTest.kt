package app.clearsms.ui.conversation

import app.clearsms.sms.SimInfo
import app.clearsms.sms.SimLabel
import app.clearsms.ui.components.SimUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Contract: the compose bar's SIM hint and the "More details" SIM row name
 * a SIM through ONE formatter ([SimLabel.slotFirst]), slot first - so the
 * two surfaces can never drift (one saying "SIM 1 - Airtel", the other
 * "Airtel (SIM 1)" or a bare "SIM 1"). Source-level, same style as
 * [MessageDetailsMenuContractTest]: the repo has no Compose UI harness.
 * The dialog also gets its SIM list from the UI state, never from a system
 * service of its own.
 */
class SimLabelContractTest {
    private fun source(path: String) = File("src/main/kotlin/app/clearsms/$path").readText()

    @Test
    fun `composer hint and details row produce byte-identical SIM text for the same SIM`() {
        val sim = SimInfo(subscriptionId = 10, slotIndex = 0, displayName = "Carrier A")
        val composer = SimUiState(visible = true, slot = 1, simCount = 2, operatorName = sim.displayName)
        val details = MessageDetails.simRowFor(listOf(sim), subscriptionId = 10)!!

        assertThat(details.label).isEqualTo(composer.tapLabel)
        assertThat(composer.hintLabel).isEqualTo("Sends with ${details.label}")
        assertThat(details.label).isEqualTo(SimLabel.slotFirst(1, "Carrier A"))
        // And with a blank name both degrade the same way.
        val blank = SimInfo(subscriptionId = 20, slotIndex = 1, displayName = "")
        assertThat(MessageDetails.simRowFor(listOf(blank), 20)!!.label)
            .isEqualTo(SimUiState(visible = true, slot = 2, simCount = 2, operatorName = "").tapLabel)
    }

    @Test
    fun `both surfaces format through SimLabel - no second hand-rolled SIM string`() {
        val bar = source("ui/components/MessageComposerBar.kt")
        val mapper = source("ui/conversation/MessageDetails.kt")
        val dialog = source("ui/conversation/MessageDetailsDialog.kt")

        // The compose bar's labels come from the shared formatter...
        val state = bar.substringAfter("data class SimUiState(").substringBefore("\n}\n")
        assertThat(state).contains("SimLabel.slotFirst(slot, operatorName)")
        assertThat(state).contains("SimLabel.nameSuffix(operatorName)")
        // ...and the private suffix helper it replaced must not resurface.
        assertThat(state).doesNotContain("private val nameSuffix")
        assertThat(state).doesNotContain("\"SIM \$slot\$nameSuffix\"")

        // The details row formats through the SAME call.
        assertThat(mapper).contains("SimLabel.slotFirst(slot, operatorName)")
        // The dialog shows the row's label verbatim - no re-formatting.
        assertThat(dialog).contains("is MessageDetails.Row.Sim -> row.label")

        // Nobody else builds a "SIM n - name" string by hand: every
        // interpolated "SIM $" literal outside SimLabel.kt is the shared
        // object's or the bubble tag's slot-only form.
        val root = File("src/main/kotlin/app/clearsms")
        val handRolled =
            root
                .walkTopDown()
                .filter { it.extension == "kt" && it.name != "SimLabel.kt" }
                .flatMap { file ->
                    Regex(""""SIM \$\{?\w+\}?\$""").findAll(file.readText()).map { "${file.name}: ${it.value}" }
                }.toList()
        assertThat(handRolled).isEmpty()
    }

    @Test
    fun `the dialog resolves SIMs from the UI state, never a system service`() {
        val dialog = source("ui/conversation/MessageDetailsDialog.kt")
        val mapper = source("ui/conversation/MessageDetails.kt")
        val screen = source("ui/conversation/ConversationScreen.kt")
        val viewModel = source("ui/conversation/ConversationViewModel.kt")

        assertThat(dialog).contains("activeSims: List<SimInfo>")
        assertThat(dialog).contains("MessageDetails.rowsFor(message, resolvedName, activeSims, dataSimHint)")
        for (file in listOf(dialog, mapper)) {
            assertThat(file).doesNotContain("SubscriptionManager")
            assertThat(file).doesNotContain("SubscriptionSource")
            assertThat(file).doesNotContain("getSystemService")
        }
        // The list travels ViewModel -> UiState -> screen -> dialog.
        assertThat(viewModel).contains("val activeSims: List<SimInfo> = emptyList(),")
        assertThat(viewModel).contains("state.copy(activeSims = sims)")
        assertThat(screen).contains("activeSims = state.activeSims,")
        // The pre-change precomputed label plumbing is gone.
        assertThat(screen).doesNotContain("detailsSimLabel")
        assertThat(mapper).doesNotContain("simLabel")
    }

    @Test
    fun `the SIM row keeps its own label resource and the other rows are untouched`() {
        val dialog = source("ui/conversation/MessageDetailsDialog.kt")
        val strings = File("src/main/res/values/strings_ui.xml").readText()
        assertThat(dialog).contains("is MessageDetails.Row.Sim -> R.string.message_details_sim")
        assertThat(strings).contains("<string name=\"message_details_sim\">")
        // One merged accessibility node per row still: "SIM, SIM 1 - Carrier A".
        assertThat(dialog).contains("Column(modifier = Modifier.semantics(mergeDescendants = true) {})")
    }
}
