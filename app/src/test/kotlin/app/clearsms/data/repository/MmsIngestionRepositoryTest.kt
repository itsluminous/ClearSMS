package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MmsStatus
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
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
 * The MMS half of the ingestion contract: pending row on notification,
 * finalized body/attachments/categorization on download, failure marking
 * and the user-initiated retry flip, attachment cleanup on hard delete.
 */
@RunWith(RobolectricTestRunner::class)
class MmsIngestionRepositoryTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }
    private val cleanedIds = mutableListOf<Long>()
    private var blockedSenders = emptySet<String>()

    private object NoopStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(),
                        senderIdLookup = SenderIdLookup { null },
                        contactLookup = ContactLookup { false },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopStore),
                json = json,
                blockedSenders = { blockedSenders },
                attachmentFileCleaner = { cleanedIds += it },
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertPending(sender: String = "+15551234567") =
        repository.insertMmsNotification(
            sender = sender,
            timestampMs = 1_000L,
            transactionId = "T1",
            contentLocation = "http://mmsc.example.com/1",
        )

    @Test
    fun `notification stores a pending empty-bodied row carrying the retrieve coordinates`() =
        runBlocking {
            val entity = insertPending()

            val stored = db.messageDao().getById(entity.id)!!
            assertThat(stored.mmsStatus).isEqualTo(MmsStatus.PENDING)
            assertThat(stored.body).isEmpty()
            assertThat(stored.mmsTransactionId).isEqualTo("T1")
            assertThat(stored.mmsContentLocation).isEqualTo("http://mmsc.example.com/1")
            assertThat(stored.isOutgoing).isFalse()
        }

    @Test
    fun `download completion makes the text part the body and runs categorization`() =
        runBlocking {
            val pending = insertPending()

            val updated =
                repository.completeMmsDownload(
                    messageId = pending.id,
                    sender = null,
                    body = "Dinner at 8?",
                    recipients = emptyList(),
                    attachments = emptyList(),
                )!!

            assertThat(updated.body).isEqualTo("Dinner at 8?")
            assertThat(updated.mmsStatus).isEqualTo(MmsStatus.DOWNLOADED)
            val stored = db.messageDao().getById(pending.id)!!
            assertThat(stored.body).isEqualTo("Dinner at 8?")
            // Categorization ran: the row no longer sits in the pre-download
            // default state unconditionally - it holds whatever the pipeline
            // decided for this sender/body (an unknown number stays UNKNOWN,
            // but via the pipeline, with extraction fields populated).
            assertThat(stored.category).isNotNull()
        }

    @Test
    fun `attachments persist with their mime type and size`() =
        runBlocking {
            val pending = insertPending()

            repository.completeMmsDownload(
                messageId = pending.id,
                sender = null,
                body = "",
                recipients = emptyList(),
                attachments =
                    listOf(
                        MmsAttachmentDraft(mimeType = "image/jpeg", fileName = "0-photo.jpg", sizeBytes = 7),
                        MmsAttachmentDraft(mimeType = "text/x-vcard", fileName = "1-contact.vcf", sizeBytes = 11),
                    ),
            )

            val rows = db.attachmentDao().forMessage(pending.id)
            assertThat(rows).hasSize(2)
            assertThat(rows[0].mimeType).isEqualTo("image/jpeg")
            assertThat(rows[0].sizeBytes).isEqualTo(7)
            assertThat(rows[0].isImage).isTrue()
            assertThat(rows[1].mimeType).isEqualTo("text/x-vcard")
            assertThat(rows[1].isImage).isFalse()
            assertThat(db.messageDao().getById(pending.id)!!.attachmentKinds).isEqualTo("IMAGE,FILE")
        }

    @Test
    fun `image-only message keeps an empty body and IMAGE kind`() =
        runBlocking {
            val pending = insertPending()

            repository.completeMmsDownload(
                messageId = pending.id,
                sender = null,
                body = "",
                recipients = emptyList(),
                attachments = listOf(MmsAttachmentDraft("image/png", "0-pic.png", 5)),
            )

            val stored = db.messageDao().getById(pending.id)!!
            assertThat(stored.body).isEmpty()
            assertThat(stored.attachmentKinds).isEqualTo("IMAGE")
        }

    @Test
    fun `group recipients are recorded on the row, attributed to the sender`() =
        runBlocking {
            val pending = insertPending(sender = "+15551234567")

            val updated =
                repository.completeMmsDownload(
                    messageId = pending.id,
                    sender = "+15551234567",
                    body = "group hi",
                    recipients = listOf("+15550001111", "+15550002222"),
                    attachments = emptyList(),
                )!!

            assertThat(updated.sender).isEqualTo("+15551234567")
            assertThat(updated.mmsRecipients).contains("+15550001111")
            assertThat(updated.mmsRecipients).contains("+15550002222")
        }

    @Test
    fun `failure marks the row FAILED and retry flips it back to PENDING`() =
        runBlocking {
            val pending = insertPending()

            repository.markMmsFailed(pending.id)
            assertThat(db.messageDao().getById(pending.id)!!.mmsStatus).isEqualTo(MmsStatus.FAILED)

            val retry = repository.markMmsPendingForRetry(pending.id)!!
            assertThat(retry.mmsContentLocation).isEqualTo("http://mmsc.example.com/1")
            assertThat(db.messageDao().getById(pending.id)!!.mmsStatus).isEqualTo(MmsStatus.PENDING)
        }

    @Test
    fun `retry is refused for rows that are not FAILED`() =
        runBlocking {
            val pending = insertPending()
            assertThat(repository.markMmsPendingForRetry(pending.id)).isNull()
        }

    @Test
    fun `a blocked sender's MMS row is flagged blocked and born-deleted`() =
        runBlocking {
            // Blocking authority is the settings set, never the rows - so no
            // pre-existing thread is needed for the block to hold.
            blockedSenders = setOf("5551234567")

            val pending = insertPending(sender = "+15551234567")
            val updated =
                repository.completeMmsDownload(
                    messageId = pending.id,
                    sender = null,
                    body = "spam image",
                    recipients = emptyList(),
                    attachments = emptyList(),
                )!!

            assertThat(pending.isBlockedSender).isTrue()
            assertThat(updated.isBlockedSender).isTrue()
            // Born-deleted like a blocked SMS: bin-resident, read, silent-eligible.
            assertThat(updated.deletedAt).isNotNull()
            assertThat(updated.isRead).isTrue()
            assertThat(db.transactionDao().getAll()).isEmpty()
        }

    @Test
    fun `hard delete removes attachment rows and requests file cleanup`() =
        runBlocking {
            val pending = insertPending()
            repository.completeMmsDownload(
                messageId = pending.id,
                sender = null,
                body = "",
                recipients = emptyList(),
                attachments = listOf(MmsAttachmentDraft("image/jpeg", "0-a.jpg", 3)),
            )

            repository.deleteMessages(listOf(pending.id))

            assertThat(db.attachmentDao().forMessage(pending.id)).isEmpty()
            assertThat(cleanedIds).contains(pending.id)
        }
}
