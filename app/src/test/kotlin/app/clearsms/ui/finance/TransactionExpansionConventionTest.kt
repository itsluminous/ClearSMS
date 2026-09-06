package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Source-level convention (in the spirit of SectionRevealConventionTest):
 * every finance transaction list renders its inline expansion through the
 * ONE shared [TransactionExpansionDetails] component. Two hand-rolled
 * expansions would drift - one of them regrowing the old "parsed fields
 * only" rendering that reduced sparse messages to a bare Ref line.
 */
class TransactionExpansionConventionTest {
    private fun source(path: String): String {
        val file = File("src/main/kotlin/app/clearsms/$path")
        assertWithMessage("expected source file $path").that(file.isFile).isTrue()
        return file.readText()
    }

    @Test
    fun `both finance lists route through the shared expansion component`() {
        assertWithMessage("the per-account list must use the shared expansion")
            .that(source("ui/finance/AccountDetailScreen.kt"))
            .contains("TransactionExpansionDetails(")
        val finance = source("ui/finance/FinanceScreen.kt")
        assertWithMessage("the Transactions/Recharges pill rows must use the shared expansion")
            .that(finance)
            .contains("TransactionExpansionDetails(")
        assertWithMessage("both pill sections must render the expandable row")
            .that(Regex("TransactionRow\\(\\s*\\n\\s*tx = tx,").findAll(finance).count())
            .isAtLeast(2)
    }

    @Test
    fun `the expansion detail rendering lives only in the shared component`() {
        // Ref line, Open message button and the raw-body rendering are the
        // expansion's substance; if a screen references these strings it has
        // started growing its own copy.
        val srcRoot = File("src/main/kotlin/app/clearsms")
        for (res in listOf("R.string.account_reference", "R.string.account_open_message")) {
            val offenders =
                srcRoot
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { it.readText().contains(res) }
                    .map { it.name }
                    .toList()
            assertWithMessage("$res must render only inside the shared expansion component")
                .that(offenders)
                .containsExactly("TransactionExpansion.kt")
        }
    }

    @Test
    fun `both lists share the single-expansion toggle`() {
        assertWithMessage("account detail must use the shared one-row-at-a-time toggle")
            .that(source("ui/finance/AccountDetailScreen.kt"))
            .contains("TransactionExpansion.toggle(")
        assertWithMessage("the Finance tab must use the shared one-row-at-a-time toggle")
            .that(source("ui/finance/FinanceScreen.kt"))
            .contains("TransactionExpansion.toggle(")
    }

    @Test
    fun `the shared expansion never gates the raw body behind the balance mask`() {
        val component = source("ui/finance/TransactionExpansion.kt")
        val bodyRender = component.substringAfter("content.body")
        assertWithMessage("the body render must not consult the balance mask")
            .that(bodyRender)
            .doesNotContain("isMasked")
        assertWithMessage("content() must pass the sms body through unconditionally")
            .that(component)
            .contains("body = smsBody,")
    }
}
