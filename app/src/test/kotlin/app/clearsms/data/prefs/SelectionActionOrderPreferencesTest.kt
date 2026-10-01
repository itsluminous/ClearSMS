package app.clearsms.data.prefs

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.clearsms.data.backup.SettingsBackupCatalog
import app.clearsms.data.backup.SettingsBackupEntry
import app.clearsms.testing.InMemoryPreferencesDataStore
import app.clearsms.ui.conversation.ConversationSelectionBarLayout
import app.clearsms.ui.conversation.MessageSelectionAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Storage contract of the conversation selection-bar action order (issue
 * #61): persisted as action NAMES with the same lenient decoding as the
 * pill orders (unknown dropped, duplicates collapsed, missing appended), so
 * a stale backup or a future action can never lose a button; and the key
 * is claimed by the settings backup.
 */
class SelectionActionOrderPreferencesTest {
    private val dataStore = InMemoryPreferencesDataStore()
    private val repo = SettingsRepositoryImpl(dataStore)
    private val key = stringPreferencesKey("message_selection_action_order")

    @Test
    fun `default - the layout's default order, which is the enum's declaration order`() =
        runBlocking<Unit> {
            assertThat(repo.messageSelectionActionOrder.first()).isEqualTo(ConversationSelectionBarLayout.defaultOrder)
            assertThat(repo.messageSelectionActionOrder.first()).isEqualTo(MessageSelectionAction.entries.toList())
        }

    @Test
    fun `stores action names, comma separated, and reads them back in order`() =
        runBlocking<Unit> {
            val order =
                listOf(MessageSelectionAction.FORWARD, MessageSelectionAction.MORE_DETAILS) +
                    MessageSelectionAction.entries.filterNot {
                        it == MessageSelectionAction.FORWARD || it == MessageSelectionAction.MORE_DETAILS
                    }
            repo.setMessageSelectionActionOrder(order)
            assertThat(dataStore.data.first()[key]).startsWith("FORWARD,MORE_DETAILS,")
            assertThat(repo.messageSelectionActionOrder.first()).isEqualTo(order)
        }

    @Test
    fun `an unknown or stale stored name is dropped, duplicates collapse, and nothing else is lost`() =
        runBlocking<Unit> {
            dataStore.edit { it[key] = "FORWARD,RETIRED_ACTION,COPY,FORWARD,not an action" }
            val order = repo.messageSelectionActionOrder.first()
            assertThat(order.take(2)).containsExactly(MessageSelectionAction.FORWARD, MessageSelectionAction.COPY).inOrder()
            assertThat(order).containsNoDuplicates()
            assertThat(order).containsExactlyElementsIn(MessageSelectionAction.entries)
        }

    @Test
    fun `an order stored before an action existed decodes with that action appended - no migration`() =
        runBlocking<Unit> {
            // What a build without MORE_DETAILS and ADD_RULE would have written.
            dataStore.edit { it[key] = "COPY,DELETE,FORWARD,SHARE,SELECT_ALL,COPY_OTP" }
            val order = repo.messageSelectionActionOrder.first()
            assertThat(order.take(6).map { it.name }).isEqualTo(listOf("COPY", "DELETE", "FORWARD", "SHARE", "SELECT_ALL", "COPY_OTP"))
            assertThat(order.drop(6)).containsExactly(MessageSelectionAction.MORE_DETAILS, MessageSelectionAction.ADD_RULE).inOrder()
            // And the bar still offers it: in the overflow for a single message.
            val bar = ConversationSelectionBarLayout.resolve(order, singleMessage = true, hasOtp = false)
            assertThat(bar.inline + bar.overflow).contains(MessageSelectionAction.MORE_DETAILS)
        }

    @Test
    fun `an empty stored value yields the default order`() =
        runBlocking<Unit> {
            dataStore.edit { it[key] = "" }
            assertThat(repo.messageSelectionActionOrder.first()).isEqualTo(MessageSelectionAction.entries.toList())
        }

    @Test
    fun `the key is registered for settings backup as a string entry and is not excluded`() {
        assertThat(SettingsBackupCatalog.byName.keys).contains("message_selection_action_order")
        assertThat(SettingsBackupCatalog.byName["message_selection_action_order"])
            .isInstanceOf(SettingsBackupEntry.StringEntry::class.java)
        assertThat(SettingsBackupCatalog.excludedKeys).doesNotContain("message_selection_action_order")
    }
}
