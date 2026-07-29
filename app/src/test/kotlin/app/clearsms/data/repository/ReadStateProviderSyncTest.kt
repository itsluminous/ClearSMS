package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Marking a message read must propagate to the system SMS provider (by
 * provider `_id`), so the read-state survives a re-import / reinstall and
 * stays in sync with other SMS apps — not just the app's own Room copy.
 */
@RunWith(RobolectricTestRunner::class)
class ReadStateProviderSyncTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repo: MessageRepositoryImpl
    private val provider = RecordingReadWriter()

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    private class RecordingReadWriter : SystemSmsReadWriter {
        val calls = mutableListOf<Pair<List<Long>, Boolean>>()

        override fun setReadBySystemIds(
            systemIds: List<Long>,
            read: Boolean,
        ) {
            calls += systemIds.sorted() to read
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val json = Json { ignoreUnknownKeys = true }
        repo =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(),
                        senderIdLookup = SenderIdLookup { null },
                        contactLookup = ContactLookup { false },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopDataStore),
                json = json,
                systemSmsReadWriter = provider,
            )
    }

    @After
    fun tearDown() = db.close()

    private fun message(
        id: Long,
        threadId: Long,
        systemSmsId: Long?,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "s$threadId",
        normalizedSender = "s$threadId",
        body = "b$id",
        timestamp = id,
        isRead = false,
        isArchived = false,
        category = Category.IMPORTANT,
        systemSmsId = systemSmsId,
    )

    @Test
    fun `markRead pushes the provider id`() =
        runBlocking<Unit> {
            db.messageDao().insert(message(id = 1, threadId = 1, systemSmsId = 555))

            repo.markRead(messageId = 1, read = true)

            assertThat(provider.calls).containsExactly(listOf(555L) to true)
        }

    @Test
    fun `setReadForThreads pushes every provider id in the thread`() =
        runBlocking<Unit> {
            db.messageDao().insert(message(id = 1, threadId = 7, systemSmsId = 100))
            db.messageDao().insert(message(id = 2, threadId = 7, systemSmsId = 101))

            repo.setReadForThreads(listOf(7), read = true)

            assertThat(provider.calls).hasSize(1)
            assertThat(provider.calls.single().first).containsExactly(100L, 101L)
            assertThat(provider.calls.single().second).isTrue()
        }

    @Test
    fun `messages without a provider id do not call the writer`() =
        runBlocking<Unit> {
            db.messageDao().insert(message(id = 1, threadId = 1, systemSmsId = null))

            repo.markRead(messageId = 1, read = true)

            assertThat(provider.calls).isEmpty()
        }
}
