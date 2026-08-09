package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Attribution precedence: the account's OWN bank (named next to the account)
 * outranks a bank named in remittance narration, and the sender outranks a
 * mere mention - while the aggregator case (the body naming the real card's
 * bank) keeps winning over the sending app.
 */
class BankAttributionPrecedenceTest {
    @Test
    fun `own bank next to the account beats the counterparty bank in IMPS narration`() {
        val body =
            "Received!\n" +
                "INR 1.00 in HDFC Bank A/c xx8709\n" +
                "On 16-06-26\n" +
                "For IMPS -Federal bank- 616715401395\n" +
                "Avl bal INR 2,10,012.98"
        assertThat(SenderNameResolver.bankNameFor("JM-HDFCBK-S", body)).isEqualTo("HDFC Bank")
    }

    @Test
    fun `a known sender naming another bank in narration stays the sender's bank`() {
        // No own-bank alias in the body at all: only the counterparty.
        val body = "UPDATE: Your A/c XX3007 credited with INR 25,000.00 on 12-06-26. For NEFT-Federal Bank- 987654321098."
        assertThat(SenderNameResolver.bankNameFor("VK-HDFCBK", body)).isEqualTo("HDFC Bank")
    }

    @Test
    fun `via narration never re-labels the account`() {
        val body = "Rs 250.00 debited from your A/c XX8709 on 04-05-2026 via Federal Bank UPI."
        // "via Federal Bank" is the rail, not the account; the sender resolves.
        assertThat(SenderNameResolver.bankNameFor("AD-SBIUPI-S", body)).isEqualTo("State Bank of India")
    }

    @Test
    fun `aggregator case - the body's own-account bank still wins over the sending app`() {
        assertThat(
            SenderNameResolver.bankNameFor(
                "JK-CREDIN",
                "Payment of INR 12,345.00 was received for your Axis Bank credit card 1234-XXXX on 12-Jan-2026.",
            ),
        ).isEqualTo("Axis Bank")
    }

    @Test
    fun `signature mention is weaker than the sender but beats the raw id`() {
        // A Citi sender signing off with another bank's name stays Citi...
        assertThat(
            SenderNameResolver.bankNameFor("TM-CITIBA", "Your request is being processed. - Federal Bank"),
        ).isEqualTo("Citi")
        // ...but with an unknown sender the signature is the best evidence left.
        assertThat(
            SenderNameResolver.bankNameFor("VM-QRXYZW", "Your request is being processed. - Federal Bank"),
        ).isEqualTo("Federal Bank")
    }

    @Test
    fun `scapia federal resolves from sender id and from card phrase`() {
        assertThat(SenderNameResolver.bankNameFor("TX-FEDSCP-S", "Some notice")).isEqualTo("Scapia Federal")
        assertThat(
            SenderNameResolver.bankNameFor(
                "TX-FEDSCP-S",
                "Hi! Your txn of \u20b95,696.87 at Discover Qatar Doha Qa on your Scapia Federal Visa credit card " +
                    "was successful. Not you? Go to Scapia support on the app.- Federal Bank",
            ),
        ).isEqualTo("Scapia Federal")
    }

    @Test
    fun `scapia federal is a plausible card-product issuer with a stable synthetic key`() {
        assertThat(SenderNameResolver.isPlausibleIssuer("Scapia Federal")).isTrue()
        assertThat(SenderNameResolver.isCardProductIssuer("Scapia Federal")).isTrue()
        // Full-service banks are not standalone card products.
        assertThat(SenderNameResolver.isCardProductIssuer("HDFC Bank")).isFalse()
        assertThat(SenderNameResolver.isCardProductIssuer(null)).isFalse()
        assertThat(SenderNameResolver.syntheticAccountKey("Scapia Federal")).isEqualTo("SCAPIAFEDERAL")
    }

    @Test
    fun `a vpa handle naming a bank never re-labels the account`() {
        // "credcc@yesbank" is the counterparty's UPI handle, not the account.
        val body =
            "Rs 5000.00 debited from a/c **8709 on 12-06-26 to VPA credcc@yesbank" +
                "(UPI Ref No 616712345678). Not you? Call on 18002586161."
        assertThat(SenderNameResolver.bankNameFor("VD-HDFCBK", body)).isEqualTo("HDFC Bank")
    }

    @Test
    fun `a wallet-brand merchant never outranks the card bank named next to the card`() {
        val body =
            "Rs.399.00 spent on your SBI Credit Card ending 4321 at PAYTM WALLET on 12/06/26. " +
                "Trxn. not done by you? Report at https://example.invalid"
        assertThat(SenderNameResolver.bankNameFor("VM-SBICRD", body)).isEqualTo("State Bank of India")
    }

    @Test
    fun `cibil and cersai mentions of federal bank resolve as weak mentions only`() {
        // These notices merely MENTION the bank; nothing downstream may
        // create an account from them (proved end-to-end in the ingestion
        // tests) - but the resolution itself must not claim an own-account
        // match either. With an unrecognized sender the mention is all that
        // is left, which is fine for display purposes.
        val cibil =
            "Your CIBIL Score & Report was checked by FEDERAL BANK ECN:12345678901 on 2026-06-14 10:12:00. " +
                "Know More? Visit https://example.invalid -CIBIL"
        assertThat(SenderNameResolver.bankNameFor("VA-CIBILA-S", cibil)).isEqualTo("Federal Bank")
    }
}
