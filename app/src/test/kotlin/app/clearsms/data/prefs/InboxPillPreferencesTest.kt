package app.clearsms.data.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.clearsms.data.backup.SettingsBackupCatalog
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.InboxPill
import app.clearsms.testing.InMemoryPreferencesDataStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Storage contract of the Inbox pill customisation (issue #49): the pill
 * order now spans [InboxPill] and keeps decoding what older installs stored
 * as [Category] names, the hidden set and labels decode leniently, the
 * unread switch defaults on, and every new key is claimed by the backup.
 */
class InboxPillPreferencesTest {
    private val dataStore = InMemoryPreferencesDataStore()
    private val repo = SettingsRepositoryImpl(dataStore)

    private val orderKey = stringPreferencesKey("inbox_pill_order")
    private val hiddenKey = stringSetPreferencesKey("inbox_hidden_pills")
    private val labelsKey = stringPreferencesKey("inbox_pill_labels")
    private val unreadKey = booleanPreferencesKey("inbox_unread_toggle")

    @Test
    fun `defaults - every pill shown in declaration order, no labels, unread switch on`() =
        runBlocking<Unit> {
            assertThat(repo.inboxPillOrder.first()).isEqualTo(InboxPill.entries.toList())
            assertThat(repo.inboxHiddenPills.first()).isEmpty()
            assertThat(repo.inboxPillLabels.first()).isEmpty()
            assertThat(repo.inboxUnreadToggle.first()).isTrue()
        }

    @Test
    fun `a pill order stored before the spam pill existed decodes with SCAM appended`() =
        runBlocking<Unit> {
            // Exactly what v0.20.0 wrote: Category names, no SCAM.
            dataStore.edit { it[orderKey] = Category.entries.reversed().joinToString(",") { c -> c.name } }
            val order = repo.inboxPillOrder.first()
            assertThat(order.take(Category.entries.size)).isEqualTo(Category.entries.reversed().map(InboxPill::of))
            assertThat(order.last()).isEqualTo(InboxPill.SCAM)
            assertThat(order).containsExactlyElementsIn(InboxPill.entries)
        }

    @Test
    fun `unknown or stale stored pill names are dropped from order and hidden set`() =
        runBlocking<Unit> {
            dataStore.edit {
                it[orderKey] = "OTP,RETIRED_PILL,PERSONAL,OTP"
                it[hiddenKey] = setOf("UNKNOWN", "RETIRED_PILL", "not a pill")
            }
            val order = repo.inboxPillOrder.first()
            assertThat(order.take(2)).containsExactly(InboxPill.OTP, InboxPill.PERSONAL).inOrder()
            assertThat(order).containsNoDuplicates()
            assertThat(order).containsExactlyElementsIn(InboxPill.entries)
            assertThat(repo.inboxHiddenPills.first()).containsExactly(InboxPill.UNKNOWN)
        }

    @Test
    fun `hidden set and labels round trip and reset`() =
        runBlocking<Unit> {
            repo.setInboxHiddenPills(setOf(InboxPill.PROMOTIONAL, InboxPill.SCAM))
            repo.setInboxPillLabels(mapOf(InboxPill.IMPORTANT to "Bank", InboxPill.SCAM to "Junk"))
            assertThat(repo.inboxHiddenPills.first()).containsExactly(InboxPill.PROMOTIONAL, InboxPill.SCAM)
            assertThat(repo.inboxPillLabels.first()).isEqualTo(mapOf(InboxPill.IMPORTANT to "Bank", InboxPill.SCAM to "Junk"))

            // Reset to defaults: everything shown, built-in names.
            repo.setInboxHiddenPills(emptySet())
            repo.setInboxPillLabels(emptyMap())
            assertThat(repo.inboxHiddenPills.first()).isEmpty()
            assertThat(repo.inboxPillLabels.first()).isEmpty()
        }

    @Test
    fun `renaming a pill never touches the stored order or the default filter`() =
        runBlocking<Unit> {
            repo.setInboxPillOrder(listOf(InboxPill.OTP, InboxPill.IMPORTANT))
            repo.setDefaultInboxFilter(Category.IMPORTANT)
            repo.setInboxPillLabels(mapOf(InboxPill.IMPORTANT to "Bank"))

            val stored = dataStore.data.first()
            assertThat(stored[orderKey]).isEqualTo("OTP,IMPORTANT")
            assertThat(stored[labelsKey]).isEqualTo("IMPORTANT=Bank")
            // The default filter still decodes by CATEGORY name, so the
            // renamed pill and the startup filter agree on identity.
            assertThat(repo.defaultInboxFilter.first()).isEqualTo(Category.IMPORTANT)
            assertThat(repo.inboxPillOrder.first().take(2)).containsExactly(InboxPill.OTP, InboxPill.IMPORTANT).inOrder()
        }

    @Test
    fun `a corrupt labels value decodes to no overrides instead of throwing`() =
        runBlocking<Unit> {
            dataStore.edit { it[labelsKey] = "garbage\n\n===\nNOPE=x" }
            assertThat(repo.inboxPillLabels.first()).isEmpty()
        }

    @Test
    fun `unread switch stores and reads back`() =
        runBlocking<Unit> {
            repo.setInboxUnreadToggle(false)
            assertThat(dataStore.data.first()[unreadKey]).isFalse()
            assertThat(repo.inboxUnreadToggle.first()).isFalse()
            repo.setInboxUnreadToggle(true)
            assertThat(repo.inboxUnreadToggle.first()).isTrue()
        }

    @Test
    fun `every new key is registered for settings backup`() {
        assertThat(SettingsBackupCatalog.byName.keys)
            .containsAtLeast("inbox_pill_order", "inbox_hidden_pills", "inbox_pill_labels", "inbox_unread_toggle")
        assertThat(SettingsBackupCatalog.excludedKeys)
            .containsNoneOf("inbox_hidden_pills", "inbox_pill_labels", "inbox_unread_toggle")
    }
}
