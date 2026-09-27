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
 * as [Category] names, the hidden set decodes leniently, the unread switch
 * defaults on, and every new key is claimed by the backup.
 */
class InboxPillPreferencesTest {
    private val dataStore = InMemoryPreferencesDataStore()
    private val repo = SettingsRepositoryImpl(dataStore)

    private val orderKey = stringPreferencesKey("inbox_pill_order")
    private val hiddenKey = stringSetPreferencesKey("inbox_hidden_pills")
    private val unreadKey = booleanPreferencesKey("inbox_unread_toggle")

    /**
     * The key an unreleased development build of this branch wrote for the
     * (since dropped) pill-renaming feature. No code reads it any more.
     */
    private val staleLabelsKey = stringPreferencesKey("inbox_pill_labels")

    @Test
    fun `defaults - every pill shown in declaration order, unread switch on`() =
        runBlocking<Unit> {
            assertThat(repo.inboxPillOrder.first()).isEqualTo(InboxPill.entries.toList())
            assertThat(repo.inboxHiddenPills.first()).isEmpty()
            assertThat(repo.inboxUnreadToggle.first()).isTrue()
        }

    @Test
    fun `a pill order stored before the Spam pill existed decodes with SPAM appended`() =
        runBlocking<Unit> {
            // Exactly what v0.20.0 wrote: the five Category names of the day, no SPAM.
            val legacy = listOf("OTP", "UNKNOWN", "PERSONAL", "PROMOTIONAL", "IMPORTANT")
            dataStore.edit { it[orderKey] = legacy.joinToString(",") }
            val order = repo.inboxPillOrder.first()
            assertThat(order.take(5).map { it.name }).isEqualTo(legacy)
            assertThat(order.last()).isEqualTo(InboxPill.SPAM)
            assertThat(order).containsExactlyElementsIn(InboxPill.entries)
        }

    @Test
    fun `the unreleased scam-flag pill name SCAM is dropped like any unknown name`() =
        runBlocking<Unit> {
            // A development build of this branch briefly persisted "SCAM" for
            // the interim scam-flag pill; it was never released. Lenient
            // decoding drops it and appends the real Spam pill.
            dataStore.edit {
                it[orderKey] = "SCAM,IMPORTANT,OTP"
                it[hiddenKey] = setOf("SCAM", "PROMOTIONAL")
            }
            val order = repo.inboxPillOrder.first()
            assertThat(order.take(2)).containsExactly(InboxPill.IMPORTANT, InboxPill.OTP).inOrder()
            assertThat(order).containsExactlyElementsIn(InboxPill.entries)
            assertThat(repo.inboxHiddenPills.first()).containsExactly(InboxPill.PROMOTIONAL)
        }

    @Test
    fun `a new install's SPAM name round-trips through order, hidden set and default filter`() =
        runBlocking<Unit> {
            repo.setInboxPillOrder(listOf(InboxPill.SPAM) + InboxPill.entries.filterNot { it == InboxPill.SPAM })
            repo.setInboxHiddenPills(setOf(InboxPill.SPAM))
            repo.setDefaultInboxFilter(Category.SPAM)

            val stored = dataStore.data.first()
            assertThat(stored[orderKey]).startsWith("SPAM,")
            assertThat(stored[hiddenKey]).containsExactly("SPAM")
            assertThat(repo.inboxPillOrder.first().first()).isEqualTo(InboxPill.SPAM)
            assertThat(repo.inboxHiddenPills.first()).containsExactly(InboxPill.SPAM)
            assertThat(repo.defaultInboxFilter.first()).isEqualTo(Category.SPAM)
        }

    @Test
    fun `a default filter name an older app does not know falls back to All`() =
        runBlocking<Unit> {
            // The mirror image: a backup restored onto (or a value written by)
            // a build that knows a category this one does not.
            dataStore.edit { it[stringPreferencesKey("default_inbox_filter")] = "SOME_FUTURE_CATEGORY" }
            assertThat(repo.defaultInboxFilter.first()).isNull()
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
    fun `hidden set round trips and resets`() =
        runBlocking<Unit> {
            repo.setInboxHiddenPills(setOf(InboxPill.PROMOTIONAL, InboxPill.SPAM))
            assertThat(repo.inboxHiddenPills.first()).containsExactly(InboxPill.PROMOTIONAL, InboxPill.SPAM)

            // Reset to defaults: everything shown.
            repo.setInboxHiddenPills(emptySet())
            assertThat(repo.inboxHiddenPills.first()).isEmpty()
        }

    @Test
    fun `hiding a pill never touches the stored order or the default filter`() =
        runBlocking<Unit> {
            repo.setInboxPillOrder(listOf(InboxPill.OTP, InboxPill.IMPORTANT))
            repo.setDefaultInboxFilter(Category.IMPORTANT)
            repo.setInboxHiddenPills(setOf(InboxPill.IMPORTANT))

            val stored = dataStore.data.first()
            assertThat(stored[orderKey]).isEqualTo("OTP,IMPORTANT")
            assertThat(repo.defaultInboxFilter.first()).isEqualTo(Category.IMPORTANT)
            assertThat(repo.inboxPillOrder.first().take(2)).containsExactly(InboxPill.OTP, InboxPill.IMPORTANT).inOrder()
        }

    @Test
    fun `a leftover pill-labels key from a development build is inert`() =
        runBlocking<Unit> {
            // Renaming never shipped, so there is no migration: the key simply
            // stops being read. It must neither crash nor influence anything.
            dataStore.edit {
                it[staleLabelsKey] = "IMPORTANT=Bank\nSPAM=Junk\ngarbage\n==="
                it[orderKey] = "SPAM,IMPORTANT"
            }
            assertThat(repo.inboxPillOrder.first().take(2)).containsExactly(InboxPill.SPAM, InboxPill.IMPORTANT).inOrder()
            assertThat(repo.inboxHiddenPills.first()).isEmpty()
            // The stale value is left alone (not exported, see the backup
            // catalog, and not read), so nothing has to migrate or delete it.
            assertThat(dataStore.data.first()[staleLabelsKey]).startsWith("IMPORTANT=Bank")
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
    fun `every pill key is registered for settings backup, the dropped labels key is not`() {
        assertThat(SettingsBackupCatalog.byName.keys)
            .containsAtLeast("inbox_pill_order", "inbox_hidden_pills", "inbox_unread_toggle")
        assertThat(SettingsBackupCatalog.excludedKeys)
            .containsNoneOf("inbox_hidden_pills", "inbox_unread_toggle")
        // Renaming was removed before release: a backup written by a
        // development build still carries the key, and restore treats it as
        // any other unknown entry - skipped and counted, never applied.
        assertThat(SettingsBackupCatalog.byName.keys).doesNotContain("inbox_pill_labels")
        assertThat(SettingsBackupCatalog.excludedKeys).doesNotContain("inbox_pill_labels")
    }
}
