package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
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
 * Direction persistence at the write paths: live-received messages
 * ([MessageRepositoryImpl.insertIncoming]) are incoming with no delivery
 * status; imported sent rows are outgoing carrying their provider-known
 * delivery state.
 */
@RunWith(RobolectricTestRunner::class)
class MessageDirectionPersistenceTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val json = Json { ignoreUnknownKeys = true }
        repository =
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
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insertIncoming persists an incoming row without delivery status`() =
        runBlocking {
            val entity = repository.insertIncoming("9876543210", "hello!", 1_000L)

            val stored = db.messageDao().getById(entity.id)!!
            assertThat(stored.isOutgoing).isFalse()
            assertThat(stored.deliveryStatus).isNull()
        }

    @Test
    fun `imported sent rows are outgoing with their provider delivery state`() =
        runBlocking {
            val snapshot = repository.rulesSnapshot()
            repository.persistImportedPage(
                listOf(
                    ImportedSmsRow(
                        systemSmsId = 1,
                        sender = "9876543210",
                        body = "incoming text",
                        timestampMs = 1_000,
                        isRead = false,
                        enriched = repository.classify(snapshot, "9876543210", "incoming text", 1_000L),
                    ),
                    ImportedSmsRow(
                        systemSmsId = 2,
                        sender = "9876543210",
                        body = "sent, no delivery report",
                        timestampMs = 2_000,
                        isRead = true,
                        enriched = null,
                    ),
                    ImportedSmsRow(
                        systemSmsId = 3,
                        sender = "9876543210",
                        body = "sent and delivered",
                        timestampMs = 3_000,
                        isRead = true,
                        enriched = null,
                        delivered = true,
                    ),
                ),
            )

            val byBody = db.messageDao().getAll().associateBy { it.body }
            with(byBody.getValue("incoming text")) {
                assertThat(isOutgoing).isFalse()
                assertThat(deliveryStatus).isNull()
            }
            with(byBody.getValue("sent, no delivery report")) {
                assertThat(isOutgoing).isTrue()
                assertThat(deliveryStatus).isEqualTo(DeliveryStatus.SENT)
            }
            with(byBody.getValue("sent and delivered")) {
                assertThat(isOutgoing).isTrue()
                assertThat(deliveryStatus).isEqualTo(DeliveryStatus.DELIVERED)
            }
        }

    @Test
    fun `an import cannot promote an in-flight outgoing MMS to SENT`() =
        runBlocking {
            // Exactly the row MmsSender persists at handover: SENDING, with
            // attachments and NO provider id (the app writes no MMS provider
            // row). An outgoing SMS to the same address then arrives through
            // the importer / catch-up path as the provider reports it: SENT.
            val mmsId =
                db.messageDao().insert(
                    MessageEntity(
                        threadId = 1L,
                        sender = "9876543210",
                        normalizedSender = "9876543210",
                        body = "photo",
                        timestamp = 1_000L,
                        isRead = true,
                        category = Category.PERSONAL,
                        isOutgoing = true,
                        deliveryStatus = DeliveryStatus.SENDING,
                        attachmentKinds = "IMAGE",
                    ),
                )

            repository.persistImportedPage(
                listOf(
                    ImportedSmsRow(
                        systemSmsId = 7,
                        sender = "9876543210",
                        body = "photo",
                        timestampMs = 1_000L,
                        isRead = true,
                        enriched = null,
                    ),
                ),
            )

            val rows = db.messageDao().getAll()
            // The MMS row is untouched: still SENDING, still the only MMS in
            // the thread, and it did not acquire a provider id.
            with(rows.single { it.id == mmsId }) {
                assertThat(deliveryStatus).isEqualTo(DeliveryStatus.SENDING)
                assertThat(systemSmsId).isNull()
                assertThat(attachmentKinds).isEqualTo("IMAGE")
            }
            assertThat(rows.count { it.attachmentKinds != null }).isEqualTo(1)
            // The imported SMS is its own row, in the same thread.
            with(rows.single { it.systemSmsId == 7L }) {
                assertThat(deliveryStatus).isEqualTo(DeliveryStatus.SENT)
                assertThat(attachmentKinds).isNull()
                assertThat(threadId).isEqualTo(rows.single { it.id == mmsId }.threadId)
            }
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
