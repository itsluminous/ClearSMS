package app.clearsms.mms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Telephony
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MmsStatus
import app.clearsms.data.repository.MessageRepositoryImpl
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.model.Category
import app.clearsms.work.SyncCheckpointStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * End-to-end tests for the MMS history import (issues #94 and #112), backed
 * by a fake `content://mms` provider - messages, their `addr` rows and their
 * `part` rows, including part bytes served through `openFile` exactly as the
 * real provider does.
 *
 * What these pin, in the order the bugs were reported:
 * 1. A received MMS already in the provider is imported, with its text,
 *    its sender from the addr table and its image bytes on disk - the
 *    "AOSP Messaging shows it, Clear SMS does not" complaint.
 * 2. `content://mms` dates are in SECONDS, not milliseconds.
 * 3. Re-running the import cannot duplicate a message.
 * 4. A delivery-record row with no text and no parts is not imported as an
 *    empty bubble.
 * 5. A SENT row is imported as the user's own message, addressed to its
 *    recipient (not its FROM, which is this device), at SENT - never
 *    DELIVERED - and drafts, outbox and failed rows are left alone.
 * 6. An install whose walk predates the sent-box import is re-walked once
 *    (the scope marker), without re-reading what it already holds.
 */
@RunWith(RobolectricTestRunner::class)
class SystemMmsImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val openDbs = mutableListOf<ClearSmsDatabase>()

    private inner class Env(
        name: String,
    ) {
        val db: ClearSmsDatabase =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
                .also { openDbs += it }
        private val dataStore =
            PreferenceDataStoreFactory.create(scope = scope) {
                tmp.newFile("$name.preferences_pb")
            }
        val repository =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(),
                        senderIdLookup = { null },
                        contactLookup = { false },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, dataStore),
                json = json,
            )
        val attachments = AttachmentStore(context)
        val checkpoints = SyncCheckpointStore(dataStore)
        val importer =
            SystemMmsImporter(
                source = SystemMmsSource(context),
                repository = repository,
                messageDao = db.messageDao(),
                attachmentStore = attachments,
                checkpointStore = checkpoints,
                ioDispatcher = Dispatchers.IO,
            )
    }

    @Before
    fun setUp() {
        FakeMmsProvider.reset()
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "mms")
    }

    @After
    fun tearDown() {
        openDbs.forEach { it.close() }
        openDbs.clear()
        File(context.filesDir, "mms").deleteRecursively()
        scope.cancel()
    }

    @Test
    fun `a received mms already in the provider is imported with text sender and image`() {
        val imageBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 7L,
                dateSeconds = 1_700_000_000L,
                read = 1,
                threadId = 42L,
                sender = "+4915112345678",
                textParts = listOf("Look at this"),
                binaryParts = listOf(FakeMmsProvider.BinaryPart("image/jpeg", "holiday.jpg", imageBytes)),
            ),
        )
        val env = Env("imported")

        val result = runBlocking { env.importer.importAll() }

        assertThat(result.imported).isEqualTo(1)
        val stored = runBlocking { env.db.messageDao().getAll() }
        assertThat(stored).hasSize(1)
        val message = stored.single()
        assertThat(message.systemMmsId).isEqualTo(7L)
        assertThat(message.sender).isEqualTo("+4915112345678")
        assertThat(message.body).isEqualTo("Look at this")
        assertThat(message.isRead).isTrue()
        assertThat(message.providerThreadId).isEqualTo(42L)
        // DOWNLOADED, never PENDING: the content came from the provider, so
        // there is nothing to fetch and nothing to retry.
        assertThat(message.mmsStatus).isEqualTo(MmsStatus.DOWNLOADED)
        assertThat(message.attachmentKinds).isEqualTo("IMAGE")
        // The bytes are on disk under the stored row's id, and the metadata
        // row's file name points at them.
        val rows = runBlocking { env.db.attachmentDao().forMessage(message.id) }
        assertThat(rows).hasSize(1)
        val file = env.attachments.fileFor(message.id, rows.single().fileName)
        assertThat(file.exists()).isTrue()
        assertThat(file.readBytes()).isEqualTo(imageBytes)
    }

    @Test
    fun `provider seconds become milliseconds`() {
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 1L,
                dateSeconds = 1_650_000_000L,
                dateSentSeconds = 1_649_999_000L,
                sender = "+10000000000",
                textParts = listOf("hi"),
            ),
        )
        val env = Env("seconds")

        runBlocking { env.importer.importAll() }

        val message = runBlocking { env.db.messageDao().getAll() }.single()
        assertThat(message.timestamp).isEqualTo(1_650_000_000_000L)
        assertThat(message.dateSent).isEqualTo(1_649_999_000_000L)
    }

    @Test
    fun `re-running the import never duplicates a message`() {
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 3L,
                dateSeconds = 1_700_000_000L,
                sender = "+10000000001",
                textParts = listOf("once"),
            ),
        )
        val env = Env("idempotent")

        val first = runBlocking { env.importer.importAll() }
        // A second pass from a RESET checkpoint is the worst case: the page
        // is re-read in full and must be rejected by the unique index.
        runBlocking { env.checkpoints.setLastSystemMmsId(0L) }
        val second = runBlocking { env.importer.importAll() }

        assertThat(first.imported).isEqualTo(1)
        assertThat(second.imported).isEqualTo(0)
        assertThat(runBlocking { env.db.messageDao().getAll() }).hasSize(1)
    }

    @Test
    fun `a contentless delivery record is not imported as an empty bubble`() {
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 9L,
                dateSeconds = 1_700_000_000L,
                sender = "+10000000002",
                textParts = emptyList(),
                binaryParts = emptyList(),
            ),
        )
        val env = Env("empty")

        val result = runBlocking { env.importer.importAll() }

        assertThat(result.imported).isEqualTo(0)
        assertThat(runBlocking { env.db.messageDao().getAll() }).isEmpty()
    }

    @Test
    fun `a message whose addr table names no sender is left alone`() {
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 11L,
                dateSeconds = 1_700_000_000L,
                sender = null,
                textParts = listOf("from nobody"),
            ),
        )
        val env = Env("senderless")

        val result = runBlocking { env.importer.importAll() }

        assertThat(result.imported).isEqualTo(0)
        assertThat(runBlocking { env.db.messageDao().getAll() }).isEmpty()
    }

    @Test
    fun `the smil layout part is not stored as an attachment`() {
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 13L,
                dateSeconds = 1_700_000_000L,
                sender = "+10000000003",
                textParts = listOf("with smil"),
                binaryParts =
                    listOf(
                        FakeMmsProvider.BinaryPart("application/smil", "smil.xml", byteArrayOf(0x3C)),
                    ),
            ),
        )
        val env = Env("smil")

        runBlocking { env.importer.importAll() }

        val message = runBlocking { env.db.messageDao().getAll() }.single()
        assertThat(message.attachmentKinds).isNull()
        assertThat(runBlocking { env.db.attachmentDao().forMessage(message.id) }).isEmpty()
    }

    @Test
    fun `a sent mms is imported as outgoing addressed to its recipient at SENT`() {
        val imageBytes = byteArrayOf(0x51, 0x52)
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 21L,
                box = Telephony.Mms.MESSAGE_BOX_SENT,
                dateSeconds = 1_700_000_100L,
                // A sent row's date_sent is often stamped too; it means
                // nothing for the user's own message.
                dateSentSeconds = 1_700_000_100L,
                read = 1,
                threadId = 77L,
                // The provider writes the device's own number as FROM on a
                // sent row (or the insert-address token). Using it as the
                // thread party would file every sent MMS under "me".
                sender = "+4917700000000",
                recipients = listOf("+4915112345678"),
                textParts = listOf("Here you go"),
                binaryParts = listOf(FakeMmsProvider.BinaryPart("image/jpeg", "sent.jpg", imageBytes)),
            ),
        )
        val env = Env("sent")

        val result = runBlocking { env.importer.importAll() }

        assertThat(result.imported).isEqualTo(1)
        val message = runBlocking { env.db.messageDao().getAll() }.single()
        assertThat(message.systemMmsId).isEqualTo(21L)
        assertThat(message.isOutgoing).isTrue()
        assertThat(message.sender).isEqualTo("+4915112345678")
        assertThat(message.body).isEqualTo("Here you go")
        assertThat(message.providerThreadId).isEqualTo(77L)
        // Another app sent it and no MMS delivery report exists: SENT is
        // what the box proves, DELIVERED would be a guess.
        assertThat(message.deliveryStatus).isEqualTo(DeliveryStatus.SENT)
        assertThat(message.isRead).isTrue()
        assertThat(message.category).isEqualTo(Category.PERSONAL)
        assertThat(message.dateSent).isNull()
        assertThat(message.mmsStatus).isEqualTo(MmsStatus.DOWNLOADED)
        assertThat(message.attachmentKinds).isEqualTo("IMAGE")
        val rows = runBlocking { env.db.attachmentDao().forMessage(message.id) }
        assertThat(env.attachments.fileFor(message.id, rows.single().fileName).readBytes()).isEqualTo(imageBytes)
    }

    @Test
    fun `a sent mms whose from is the insert-address token still resolves its recipient`() {
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 22L,
                box = Telephony.Mms.MESSAGE_BOX_SENT,
                dateSeconds = 1_700_000_200L,
                sender = "insert-address-token",
                recipients = listOf("+10000000009"),
                textParts = listOf("token from"),
            ),
        )
        val env = Env("sent-token")

        runBlocking { env.importer.importAll() }

        val message = runBlocking { env.db.messageDao().getAll() }.single()
        assertThat(message.isOutgoing).isTrue()
        assertThat(message.sender).isEqualTo("+10000000009")
    }

    @Test
    fun `a sent mms with no recipient is left alone`() {
        // A sent row the addr table only knows the FROM of cannot be placed
        // in any conversation - the mirror of the senderless received row.
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 23L,
                box = Telephony.Mms.MESSAGE_BOX_SENT,
                dateSeconds = 1_700_000_300L,
                sender = "+4917700000000",
                recipients = emptyList(),
                textParts = listOf("to nobody"),
            ),
        )
        val env = Env("sent-nobody")

        val result = runBlocking { env.importer.importAll() }

        assertThat(result.imported).isEqualTo(0)
        assertThat(runBlocking { env.db.messageDao().getAll() }).isEmpty()
    }

    @Test
    fun `received and sent mms with the same party share one thread in time order`() {
        val them = "+4915112345678"
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 31L,
                dateSeconds = 1_700_000_000L,
                threadId = 9L,
                sender = them,
                recipients = listOf("+4917700000000"),
                textParts = listOf("first, from them"),
            ),
        )
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 32L,
                box = Telephony.Mms.MESSAGE_BOX_SENT,
                dateSeconds = 1_700_000_060L,
                threadId = 9L,
                sender = "+4917700000000",
                recipients = listOf(them),
                textParts = listOf("second, my reply"),
            ),
        )
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 33L,
                dateSeconds = 1_700_000_120L,
                threadId = 9L,
                sender = them,
                recipients = listOf("+4917700000000"),
                textParts = listOf("third, from them again"),
            ),
        )
        val env = Env("mixed")

        val result = runBlocking { env.importer.importAll() }

        assertThat(result.imported).isEqualTo(3)
        val stored = runBlocking { env.db.messageDao().getAll() }.sortedBy { it.timestamp }
        assertThat(stored.map { it.body }).containsExactly("first, from them", "second, my reply", "third, from them again").inOrder()
        assertThat(stored.map { it.isOutgoing }).containsExactly(false, true, false).inOrder()
        // One conversation, keyed by the other party on both directions.
        assertThat(stored.map { it.threadId }.distinct()).hasSize(1)
        assertThat(stored.map { it.normalizedSender }.distinct()).hasSize(1)
    }

    @Test
    fun `drafts outbox and failed rows are never imported`() {
        for (
        (id, box) in
        listOf(
            41L to Telephony.Mms.MESSAGE_BOX_DRAFTS,
            42L to Telephony.Mms.MESSAGE_BOX_OUTBOX,
            43L to Telephony.Mms.MESSAGE_BOX_FAILED,
        )
        ) {
            FakeMmsProvider.add(
                FakeMmsProvider.Message(
                    id = id,
                    box = box,
                    dateSeconds = 1_700_000_000L + id,
                    sender = "+4917700000000",
                    recipients = listOf("+10000000010"),
                    textParts = listOf("box $box"),
                ),
            )
        }
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 44L,
                box = Telephony.Mms.MESSAGE_BOX_SENT,
                dateSeconds = 1_700_000_900L,
                sender = "+4917700000000",
                recipients = listOf("+10000000010"),
                textParts = listOf("actually sent"),
            ),
        )
        val env = Env("boxes")

        val result = runBlocking { env.importer.importAll() }

        // A draft was never sent; an outbox row's fate is unknown; a failed
        // row would offer "tap to retry" on another app's message. Only the
        // row the provider says went out is a message.
        assertThat(result.imported).isEqualTo(1)
        assertThat(runBlocking { env.db.messageDao().getAll() }.single().body).isEqualTo("actually sent")
    }

    @Test
    fun `re-running over sent and received rows never duplicates either`() {
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 51L,
                dateSeconds = 1_700_000_000L,
                sender = "+10000000011",
                textParts = listOf("in"),
            ),
        )
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 52L,
                box = Telephony.Mms.MESSAGE_BOX_SENT,
                dateSeconds = 1_700_000_050L,
                sender = "+4917700000000",
                recipients = listOf("+10000000011"),
                textParts = listOf("out"),
            ),
        )
        val env = Env("idempotent-both")

        val first = runBlocking { env.importer.importAll() }
        // Worst case twice over: the checkpoint reset AND the scope marker
        // gone, as if the walk were owed again - the page is re-read in full
        // and must be rejected by the unique index.
        runBlocking { env.checkpoints.clear() }
        val second = runBlocking { env.importer.importAll() }

        assertThat(first.imported).isEqualTo(2)
        assertThat(second.imported).isEqualTo(0)
        assertThat(runBlocking { env.db.messageDao().getAll() }).hasSize(2)
    }

    @Test
    fun `an inbox-only walk is re-walked once and picks up the sent rows it stepped over`() {
        // The received row at the higher id; the sent row at a LOWER id, the
        // normal shape of a conversation (reply, then answer).
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 61L,
                box = Telephony.Mms.MESSAGE_BOX_SENT,
                dateSeconds = 1_700_000_000L,
                sender = "+4917700000000",
                recipients = listOf("+10000000012"),
                textParts = listOf("my question"),
            ),
        )
        FakeMmsProvider.add(
            FakeMmsProvider.Message(
                id = 62L,
                dateSeconds = 1_700_000_060L,
                sender = "+10000000012",
                textParts = listOf("their answer"),
                binaryParts = listOf(FakeMmsProvider.BinaryPart("image/jpeg", "a.jpg", byteArrayOf(0x01))),
            ),
        )
        val env = Env("rewalk")
        // The exact state 0.23.0 left behind: the received row stored, the
        // checkpoint past BOTH rows, no scope recorded.
        runBlocking {
            env.repository.insertImportedMms(
                systemMmsId = 62L,
                address = "+10000000012",
                isOutgoing = false,
                body = "their answer",
                timestampMs = 1_700_000_060_000L,
                dateSentMs = null,
                isRead = true,
                providerThreadId = null,
                subscriptionId = null,
                recipients = emptyList(),
                attachments = emptyList(),
            )
            env.checkpoints.clear()
            env.checkpoints.setLastSystemMmsId(62L)
        }
        val partReadsBefore = FakeMmsProvider.partQueries.getOrDefault(62L, 0)

        val result = runBlocking { env.importer.importAll() }

        assertThat(result.imported).isEqualTo(1)
        val stored = runBlocking { env.db.messageDao().getAll() }
        assertThat(stored).hasSize(2)
        assertThat(stored.single { it.isOutgoing }.body).isEqualTo("my question")
        // The row already held was skipped by id, before any provider read:
        // the re-walk did not fetch its parts (or its image bytes) again.
        assertThat(FakeMmsProvider.partQueries.getOrDefault(62L, 0)).isEqualTo(partReadsBefore)
        // The walk is recorded under the current scope, and resumes from
        // its own checkpoint afterwards - a second run reads nothing.
        runBlocking {
            assertThat(env.checkpoints.mmsImportScope()).isEqualTo(SyncCheckpointStore.MMS_SCOPE_CURRENT)
            assertThat(env.checkpoints.lastSystemMmsId()).isEqualTo(62L)
        }
        val again = runBlocking { env.importer.importAll() }
        assertThat(again.imported).isEqualTo(0)
        assertThat(FakeMmsProvider.partQueries.getOrDefault(61L, 0)).isEqualTo(1)
    }

    @Test
    fun `a fresh import records the current scope`() {
        val env = Env("fresh-scope")
        runBlocking {
            assertThat(env.checkpoints.mmsImportScope()).isEqualTo(SyncCheckpointStore.MMS_SCOPE_NONE)
            env.importer.importAll()
            assertThat(env.checkpoints.mmsImportScope()).isEqualTo(SyncCheckpointStore.MMS_SCOPE_CURRENT)
        }
    }

    /**
     * Fake `content://mms`: the message table, the per-message `addr` table
     * and the `part` table, with part bytes served over `openFile` the way
     * the real provider does (its `_data` path is not app-readable). Honours
     * the `msg_box IN (...)` clause of the selection, so the box filter is
     * tested against the query the importer actually issues.
     */
    class FakeMmsProvider : ContentProvider() {
        data class BinaryPart(
            val mime: String,
            val name: String?,
            val bytes: ByteArray,
        )

        data class Message(
            val id: Long,
            val box: Int = Telephony.Mms.MESSAGE_BOX_INBOX,
            val dateSeconds: Long,
            val dateSentSeconds: Long = 0L,
            val read: Int = 1,
            val threadId: Long = 0L,
            val subId: Int = -1,
            val sender: String?,
            val textParts: List<String> = emptyList(),
            val binaryParts: List<BinaryPart> = emptyList(),
            val recipients: List<String> = emptyList(),
        )

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val segments = uri.pathSegments
            return when {
                segments.firstOrNull() == "part" -> partCursor(selectionArgs)
                segments.size == 2 && segments[1] == "addr" -> addrCursor(segments[0].toLong())
                else -> messageCursor(selection, selectionArgs, sortOrder)
            }
        }

        private fun messageCursor(
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val afterId = selectionArgs?.firstOrNull()?.toLongOrNull() ?: Long.MIN_VALUE
            // `msg_box IN (1, 2)` -> {1, 2}; no clause -> every box.
            val boxes =
                BOX_CLAUSE
                    .find(selection.orEmpty())
                    ?.groupValues
                    ?.get(1)
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toSet()
            val limit =
                sortOrder
                    ?.substringAfterLast("LIMIT ", missingDelimiterValue = "")
                    ?.trim()
                    ?.toIntOrNull() ?: Int.MAX_VALUE
            val cursor =
                MatrixCursor(
                    arrayOf(
                        Telephony.Mms._ID,
                        Telephony.Mms.DATE,
                        Telephony.Mms.DATE_SENT,
                        Telephony.Mms.READ,
                        Telephony.Mms.THREAD_ID,
                        Telephony.Mms.SUBSCRIPTION_ID,
                        Telephony.Mms.MESSAGE_BOX,
                    ),
                )
            messages
                .asSequence()
                .filter { it.id > afterId }
                .filter { boxes == null || it.box in boxes }
                .sortedBy { it.id }
                .take(limit)
                .forEach {
                    cursor.addRow(
                        arrayOf<Any?>(
                            it.id,
                            it.dateSeconds,
                            it.dateSentSeconds,
                            it.read,
                            it.threadId,
                            it.subId,
                            it.box,
                        ),
                    )
                }
            return cursor
        }

        private fun addrCursor(messageId: Long): Cursor {
            val cursor = MatrixCursor(arrayOf("address", "type"))
            val message = messages.firstOrNull { it.id == messageId } ?: return cursor
            // 137 = PduHeaders.FROM, 151 = TO.
            message.sender?.let { cursor.addRow(arrayOf<Any?>(it, 137)) }
            message.recipients.forEach { cursor.addRow(arrayOf<Any?>(it, 151)) }
            return cursor
        }

        private fun partCursor(selectionArgs: Array<out String>?): Cursor {
            val messageId = selectionArgs?.firstOrNull()?.toLongOrNull()
            if (messageId != null) partQueries[messageId] = partQueries.getOrDefault(messageId, 0) + 1
            val cursor =
                MatrixCursor(
                    arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT, "fn"),
                )
            val message = messages.firstOrNull { it.id == messageId } ?: return cursor
            var partId = messageId!! * 100
            message.textParts.forEach {
                cursor.addRow(arrayOf<Any?>(partId++, "text/plain", it, null))
            }
            message.binaryParts.forEach { part ->
                val id = partId++
                partBytes[id] = part.bytes
                cursor.addRow(arrayOf<Any?>(id, part.mime, null, part.name))
            }
            return cursor
        }

        /** Part bytes come over a real file descriptor, as from the platform. */
        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor? {
            val partId = uri.lastPathSegment?.toLongOrNull() ?: return null
            val bytes = partBytes[partId] ?: return null
            val file = File.createTempFile("mmspart-$partId", ".bin")
            file.writeBytes(bytes)
            file.deleteOnExit()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            val messages = mutableListOf<Message>()
            val partBytes = mutableMapOf<Long, ByteArray>()

            /** How often each message's parts were queried - the re-walk's "did not re-read" proof. */
            val partQueries = mutableMapOf<Long, Int>()

            private val BOX_CLAUSE = Regex("""${Telephony.Mms.MESSAGE_BOX} IN \(([0-9, ]{1,32})\)""")

            fun add(message: Message) {
                messages += message
            }

            fun reset() {
                messages.clear()
                partBytes.clear()
                partQueries.clear()
            }
        }
    }
}
