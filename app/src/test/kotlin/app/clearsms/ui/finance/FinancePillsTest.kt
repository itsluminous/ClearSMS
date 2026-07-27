package app.clearsms.ui.finance

import app.clearsms.data.db.AccountEntity
import app.clearsms.data.db.TransactionEntity
import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.TransactionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class FinancePillsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val month = YearMonth.of(2026, 7)

    private fun account(
        id: Long,
        type: AccountType,
    ) = AccountEntity(
        id = id,
        accountNumber = "000$id",
        bankName = "Bank $id",
        type = type,
        lastUpdated = 0L,
    )

    private fun tx(
        year: Int,
        monthOfYear: Int,
        day: Int,
    ) = TransactionEntity(
        id = 0,
        amount = 100.0,
        type = TransactionType.DEBIT,
        accountNumber = "1234",
        bankName = "Test Bank",
        timestamp =
            LocalDateTime
                .of(year, monthOfYear, day, 12, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli(),
        rawSmsId = 0,
    )

    @Test
    fun `accounts and cards are split by account type`() {
        val accounts =
            listOf(
                account(1, AccountType.SAVINGS),
                account(2, AccountType.WALLET),
                account(3, AccountType.CREDIT_CARD),
            )
        val counts = FinancePills.counts(accounts, emptyList(), month, zone)
        assertThat(counts[FinanceTab.ACCOUNTS]).isEqualTo(2)
        assertThat(counts[FinanceTab.CREDIT_CARDS]).isEqualTo(1)
    }

    @Test
    fun `transactions pill only counts the given month`() {
        val transactions =
            listOf(
                tx(2026, 7, 1),
                tx(2026, 7, 31),
                tx(2026, 6, 30),
                tx(2025, 7, 15),
            )
        val counts = FinancePills.counts(emptyList(), transactions, month, zone)
        assertThat(counts[FinanceTab.TRANSACTIONS]).isEqualTo(2)
    }

    @Test
    fun `empty inputs produce zero counts for every pill`() {
        val counts = FinancePills.counts(emptyList(), emptyList(), month, zone)
        assertThat(counts.keys).containsExactlyElementsIn(FinanceTab.entries)
        assertThat(counts.values).containsExactly(0, 0, 0)
    }
}
