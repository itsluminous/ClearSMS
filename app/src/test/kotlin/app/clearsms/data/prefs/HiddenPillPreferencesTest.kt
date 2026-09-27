package app.clearsms.data.prefs

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.clearsms.data.backup.SettingsBackupCatalog
import app.clearsms.data.backup.SettingsBackupEntry
import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.InboxPill
import app.clearsms.testing.InMemoryPreferencesDataStore
import app.clearsms.ui.alerts.AlertFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Storage contract of the Finance and Alerts hidden-pill sets: persisted
 * like `inbox_hidden_pills` (a set of enum names), read through the one
 * lenient decoder all three screens share (unknown or stale names dropped,
 * never a crash), independent of the order preference, and claimed by the
 * settings backup.
 */
class HiddenPillPreferencesTest {
    private val dataStore = InMemoryPreferencesDataStore()
    private val repo = SettingsRepositoryImpl(dataStore)

    private val financeKey = stringSetPreferencesKey("finance_hidden_pills")
    private val alertsKey = stringSetPreferencesKey("alerts_hidden_pills")
    private val inboxKey = stringSetPreferencesKey("inbox_hidden_pills")

    @Test
    fun `defaults - nothing hidden on either screen`() =
        runBlocking<Unit> {
            assertThat(repo.financeHiddenPills.first()).isEmpty()
            assertThat(repo.alertsHiddenPills.first()).isEmpty()
        }

    @Test
    fun `round trip - stored as enum names, read back as the same set`() =
        runBlocking<Unit> {
            repo.setFinanceHiddenPills(setOf(FinanceTab.RECHARGES, FinanceTab.CREDIT_CARDS))
            repo.setAlertsHiddenPills(setOf(AlertFilter.TRAVEL))
            assertThat(dataStore.data.first()[financeKey]).containsExactly("RECHARGES", "CREDIT_CARDS")
            assertThat(dataStore.data.first()[alertsKey]).containsExactly("TRAVEL")
            assertThat(repo.financeHiddenPills.first()).containsExactly(FinanceTab.RECHARGES, FinanceTab.CREDIT_CARDS)
            assertThat(repo.alertsHiddenPills.first()).containsExactly(AlertFilter.TRAVEL)
        }

    @Test
    fun `unknown or stale stored names decode leniently on every screen`() =
        runBlocking<Unit> {
            dataStore.edit {
                // A pill removed in a later version, a typo, an empty name, a
                // name from the WRONG screen - all dropped, the rest kept.
                it[financeKey] = setOf("RECHARGES", "LOANS", "accounts", "", "EMI")
                it[alertsKey] = setOf("OTHERS", "DELIVERY", "TRANSACTIONS", "SCAM")
                it[inboxKey] = setOf("SCAM", "SPAM", "Bogus")
            }
            assertThat(repo.financeHiddenPills.first()).containsExactly(FinanceTab.RECHARGES)
            assertThat(repo.alertsHiddenPills.first()).containsExactly(AlertFilter.DELIVERY)
            assertThat(repo.inboxHiddenPills.first()).containsExactly(InboxPill.SPAM)
        }

    @Test
    fun `hiding is independent of the stored order`() =
        runBlocking<Unit> {
            repo.setAlertsPillOrder(listOf(AlertFilter.TRAVEL, AlertFilter.EMI))
            repo.setAlertsHiddenPills(setOf(AlertFilter.TRAVEL))
            assertThat(repo.alertsPillOrder.first().take(2)).containsExactly(AlertFilter.TRAVEL, AlertFilter.EMI).inOrder()
            repo.setAlertsHiddenPills(emptySet())
            assertThat(repo.alertsPillOrder.first().take(2)).containsExactly(AlertFilter.TRAVEL, AlertFilter.EMI).inOrder()
        }

    @Test
    fun `all hidden is a legitimate stored value - no floor at the storage level either`() =
        runBlocking<Unit> {
            repo.setFinanceHiddenPills(FinanceTab.entries.toSet())
            repo.setAlertsHiddenPills(AlertFilter.entries.toSet())
            assertThat(repo.financeHiddenPills.first()).containsExactlyElementsIn(FinanceTab.entries)
            assertThat(repo.alertsHiddenPills.first()).containsExactlyElementsIn(AlertFilter.entries)
        }

    @Test
    fun `both keys are registered for settings backup as string sets, like the inbox's`() {
        for (key in listOf("inbox_hidden_pills", "finance_hidden_pills", "alerts_hidden_pills")) {
            val entry = SettingsBackupCatalog.byName[key]
            assertThat(entry).isInstanceOf(SettingsBackupEntry.StringSetEntry::class.java)
            assertThat(SettingsBackupCatalog.excludedKeys).doesNotContain(key)
        }
    }
}
