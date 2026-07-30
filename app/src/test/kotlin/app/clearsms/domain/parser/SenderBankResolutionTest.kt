package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Bank resolution chain and canonicalization: "SBI" / "State Bank of India"
 * and "Citi" / "Citi Bank" must never split into separate account cards, and
 * an account must never end up nameless.
 */
class SenderBankResolutionTest {
    @Test
    fun `sbi variants canonicalize to one institution`() {
        assertThat(SenderNameResolver.canonicalize("SBI")).isEqualTo("State Bank of India")
        assertThat(SenderNameResolver.canonicalize("State Bank of India")).isEqualTo("State Bank of India")
    }

    @Test
    fun `citi variants canonicalize to one institution`() {
        assertThat(SenderNameResolver.canonicalize("Citi")).isEqualTo("Citi")
        assertThat(SenderNameResolver.canonicalize("Citi Bank")).isEqualTo("Citi")
        assertThat(SenderNameResolver.canonicalize("CITIBANK")).isEqualTo("Citi")
    }

    @Test
    fun `unknown names pass through and blank collapses to null`() {
        assertThat(SenderNameResolver.canonicalize("Some Local Coop Bank")).isEqualTo("Some Local Coop Bank")
        assertThat(SenderNameResolver.canonicalize("  ")).isNull()
        assertThat(SenderNameResolver.canonicalize(null)).isNull()
    }

    @Test
    fun `chain step 1 - bank named in the body wins over the sending app`() {
        // A card-payment app confirms a payment on an Axis card: the account
        // belongs to Axis Bank, not to the app that sent the SMS.
        assertThat(
            SenderNameResolver.bankNameFor(
                "JK-CREDIN",
                "Payment of INR 12,345.00 was received for your Axis Bank credit card 1234-XXXX on 12-Jan-2026.",
            ),
        ).isEqualTo("Axis Bank")
    }

    @Test
    fun `body mention without account context does not hijack the bank`() {
        // "paytm" here is the merchant VPA, not the account's institution.
        assertThat(
            SenderNameResolver.bankNameFor(
                "SBIINB",
                "Rs.250.00 debited from A/c XX9805 to paytm-merchant on 20-07-26.",
            ),
        ).isEqualTo("State Bank of India")
    }

    @Test
    fun `chain step 2 - sender id resolves via the institution table`() {
        assertThat(SenderNameResolver.bankNameFor("TM-CITIBA", "Spent on card xx0310")).isEqualTo("Citi")
        assertThat(SenderNameResolver.bankNameFor("VD-Pluxee-S", "Spent from wallet")).isEqualTo("Pluxee")
        // Sodexo was rebranded to Pluxee — both ids are the same wallet.
        assertThat(SenderNameResolver.bankNameFor("VD-SODEXO", "Spent from wallet")).isEqualTo("Pluxee")
    }

    @Test
    fun `chain step 3 - unknown sender falls back to the normalized sender id, never nothing`() {
        assertThat(SenderNameResolver.bankNameFor("VM-NBHood-S", "Rs 100 spent")).isEqualTo("NBHOOD")
        assertThat(SenderNameResolver.bankNameFor("", "")).isNull()
    }

    @Test
    fun `wallet body context resolves the wallet institution`() {
        assertThat(
            SenderNameResolver.bankNameFor(
                "VM-Pluxee-S",
                "Rs. 350.00 spent from Pluxee Reimbursement wallet, card no.xx5919 at STORE. Avl bal Rs.10.",
            ),
        ).isEqualTo("Pluxee")
    }
}
