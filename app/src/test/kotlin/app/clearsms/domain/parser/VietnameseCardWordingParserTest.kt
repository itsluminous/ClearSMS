package app.clearsms.domain.parser

import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Vietnamese card alerts say "THE TIN DUNG" (thẻ tín dụng) or the
 * abbreviation "The TD" where English alerts say "credit card". The global
 * `CREDIT_CARD_REGEX` learnt both forms, but it runs for every locale, so
 * the Vietnamese branch must carry the same number cue the English
 * branches do - otherwise an English savings-account SMS mentioning
 * TD Bank ("the TD account ending 1234") types as a credit-card spend.
 *
 * All senders, tails and amounts are synthetic.
 */
class VietnameseCardWordingParserTest {
    private val parser = TransactionParser()

    @Test
    fun `THE TIN DUNG SO followed by a masked number is a credit card`() {
        assertThat(
            parser.accountTypeOf("TPBank: THE TIN DUNG SO 4512****1234 PS: -1,250,000 VND tai SHOPEE luc 10:15 05/10"),
        ).isEqualTo(AccountType.CREDIT_CARD)
    }

    @Test
    fun `The TD followed by a masked number is a credit card`() {
        assertThat(
            parser.accountTypeOf("TPBank: The TD 4512****1234 Du no: 3,400,000 VND. Ngay den han TT: 25/10/2026"),
        ).isEqualTo(AccountType.CREDIT_CARD)
    }

    @Test
    fun `an English TD Bank account mention is not a credit card`() {
        val result =
            parser.parse(
                "VM-HDFCBK",
                "Rs.5000.00 debited from A/c XX5678 towards the TD account ending 1234 on 05-10-26. Avl Bal Rs.12,000.00",
            )
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(TransactionType.DEBIT)
        assertThat(result.accountType).isEqualTo(AccountType.SAVINGS)
    }

    @Test
    fun `the td inside another word or with no number is not a credit card`() {
        assertThat(parser.accountTypeOf("Please bathe td 1234 before 5pm")).isEqualTo(AccountType.SAVINGS)
        assertThat(parser.accountTypeOf("Rs 500 debited; see the TD for details")).isEqualTo(AccountType.SAVINGS)
        assertThat(parser.accountTypeOf("Funds moved to the TD Ameritrade account")).isEqualTo(AccountType.SAVINGS)
    }
}
