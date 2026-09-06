package app.clearsms.ui.finance

import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behaviour of the shared inline expansion (see [TransactionExpansion]):
 * the one-row-at-a-time toggle, and the guarantee that the expansion's
 * content ALWAYS carries the full SMS body - the historical bug was a
 * sparse parse (few extracted fields) plus the balance gate leaving the
 * expansion showing little more than a "Ref:" line.
 *
 * All values are synthetic; no real message content appears here.
 */
class TransactionExpansionTest {
    // A card-UPI shape whose parse yields almost nothing beyond a reference:
    // no balance, no merchant. Digits and VPA are synthetic.
    private val sparseBody =
        "Txn Rs.549.00\nOn ExBank Card 4321\nAt shop.11110000@exbank\nby UPI 123400005678\n" +
            "On 05-09\nNot You?\nCall 18002586161/SMS BLOCK CC 4321 to 7308080808"

    private fun tx(
        id: Long = 1L,
        balance: Double? = null,
        referenceNumber: String? = null,
    ) = TransactionEntity(
        id = id,
        amount = 549.0,
        type = TransactionType.DEBIT,
        merchantName = null,
        accountNumber = "4321",
        bankName = "ExBank",
        timestamp = 1_757_000_000_000L,
        balance = balance,
        referenceNumber = referenceNumber,
        rawSmsId = 10L,
    )

    // --- which row is expanded ---

    @Test
    fun `tapping a collapsed row expands it`() {
        assertThat(TransactionExpansion.toggle(expandedId = null, tappedId = 7L)).isEqualTo(7L)
    }

    @Test
    fun `tapping the expanded row collapses it`() {
        assertThat(TransactionExpansion.toggle(expandedId = 7L, tappedId = 7L)).isNull()
    }

    @Test
    fun `tapping another row moves the single expansion there`() {
        assertThat(TransactionExpansion.toggle(expandedId = 7L, tappedId = 9L)).isEqualTo(9L)
    }

    // --- expansion content ---

    @Test
    fun `sparse parse still carries the full message body`() {
        val content =
            TransactionExpansion.content(
                tx = tx(balance = null, referenceNumber = "123400005678"),
                smsBody = sparseBody,
                balanceMasked = false,
            )
        assertThat(content.body).isEqualTo(sparseBody)
        assertThat(content.balance).isNull()
        assertThat(content.referenceNumber).isEqualTo("123400005678")
    }

    @Test
    fun `body survives the balance privacy gate`() {
        // Regression pin: the raw body used to hide whenever balances were
        // masked, which is exactly what reduced the expansion to a Ref line.
        val content =
            TransactionExpansion.content(
                tx = tx(balance = 1234.56, referenceNumber = "123400005678"),
                smsBody = sparseBody,
                balanceMasked = true,
            )
        assertThat(content.body).isEqualTo(sparseBody)
        assertThat(content.balanceMasked).isTrue()
        // The PARSED balance figure remains maskable.
        assertThat(content.balance).isEqualTo(1234.56)
    }

    @Test
    fun `a deleted source message yields no body without hiding parsed detail`() {
        val content =
            TransactionExpansion.content(
                tx = tx(balance = 99.0, referenceNumber = "123400005678"),
                smsBody = null,
                balanceMasked = false,
            )
        assertThat(content.body).isNull()
        assertThat(content.balance).isEqualTo(99.0)
        assertThat(content.referenceNumber).isEqualTo("123400005678")
    }
}
