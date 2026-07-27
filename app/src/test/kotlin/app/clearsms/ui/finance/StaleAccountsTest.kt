package app.clearsms.ui.finance

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

/** The 1-year dormancy boundary: strictly over is stale, at or under is not. */
class StaleAccountsTest {
    private val now = 1_700_000_000_000L
    private val year = StaleAccounts.STALE_AFTER.toMillis()

    @Test
    fun `threshold is exactly one year of 365 days`() {
        assertThat(StaleAccounts.STALE_AFTER).isEqualTo(Duration.ofDays(365))
    }

    @Test
    fun `just under a year is active`() {
        assertThat(StaleAccounts.isStale(now - (year - 1), now)).isFalse()
    }

    @Test
    fun `exactly a year is still active — staleness is strictly over`() {
        assertThat(StaleAccounts.isStale(now - year, now)).isFalse()
    }

    @Test
    fun `just over a year is stale`() {
        assertThat(StaleAccounts.isStale(now - (year + 1), now)).isTrue()
    }

    @Test
    fun `partition splits and preserves order`() {
        data class Row(
            val name: String,
            val lastUpdated: Long,
        )

        val rows =
            listOf(
                Row("fresh-1", now - 1),
                Row("stale-1", now - year - 5),
                Row("fresh-2", now - year),
                Row("stale-2", now - 2 * year),
            )

        val split = StaleAccounts.partition(rows, now) { it.lastUpdated }

        assertThat(split.active.map { it.name }).containsExactly("fresh-1", "fresh-2").inOrder()
        assertThat(split.stale.map { it.name }).containsExactly("stale-1", "stale-2").inOrder()
    }

    @Test
    fun `counts back the show-older label`() {
        val split = StaleAccounts.partition(listOf(now - year - 1, now - year - 2), now) { it }
        assertThat(split.stale).hasSize(2)
        assertThat(split.active).isEmpty()
    }
}
