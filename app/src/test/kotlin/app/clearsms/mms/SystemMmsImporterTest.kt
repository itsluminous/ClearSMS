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
import app.clearsms.data.db.MmsStatus
import app.clearsms.data.repository.MessageRepositoryImpl
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.MessageCategorizer
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
 * End-to-end tests for the MMS history import (issue #94), backed by a fake
 * `content://mms` provider - messages, their `addr` rows and their `part`
 * rows, including part bytes served through `openFile` exactly as the real
 * provider does.
 *
 * What these pin, in the order the bug was reported:
 * 1. A received MMS already in the provider is imported, with its text,
 *    its sender from the addr table and its image bytes on disk - the
 *    "AOSP Messaging shows it, Clear SMS does not" complaint.
 * 2. `content://mms` dates are in SECONDS, not milliseconds.
 * 3. Re-running the import cannot duplicate a message.
 * 4. A delivery-record row with no text and no parts is not imported as an
 *    empty bubble.
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

    /**
     * Fake `content://mms`: the message table, the per-message `addr` table
     * and the `part` table, with part bytes served over `openFile` the way
     * the real provider does (its `_data` path is not app-readable).
     */
    class FakeMmsProvider : ContentProvider() {
        data class BinaryPart(
            val mime: String,
            val name: String?,
            val bytes: ByteArray,
        )

        data class Message(
            val id: Long,
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
                else -> messageCursor(selectionArgs, sortOrder)
            }
        }

        private fun messageCursor(
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val afterId = selectionArgs?.firstOrNull()?.toLongOrNull() ?: Long.MIN_VALUE
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
                            Telephony.Mms.MESSAGE_BOX_INBOX,
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

            fun add(message: Message) {
                messages += message
            }

            fun reset() {
                messages.clear()
                partBytes.clear()
            }
        }
    }
}
