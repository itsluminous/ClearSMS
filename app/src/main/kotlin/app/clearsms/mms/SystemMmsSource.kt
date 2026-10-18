package app.clearsms.mms

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.id
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One MMS - received or sent - read out of the system MMS provider. */
data class ProviderMms(
    /** The provider's `_id`, the import's idempotency key. */
    val systemMmsId: Long,
    /**
     * True for a row in the provider's SENT box (a message this device sent,
     * through whichever app held the role at the time), false for INBOX.
     */
    val isOutgoing: Boolean,
    /**
     * The other party, from the `addr` table - the thread this message
     * belongs to. For a received message that is the sender (`FROM`, 137);
     * for a sent message it is the first recipient (`TO`, 151) - a sent
     * row's `FROM` is the device itself (or the insert-address token) and
     * must never key a thread. Null when the PDU carried no such address.
     */
    val address: String?,
    /** Text parts joined in part order; empty when the message was image-only. */
    val body: String,
    /** When this device received the message, in MILLIseconds. */
    val timestampMs: Long,
    /** The sender's network timestamp in milliseconds, or null when unreported. */
    val sentAtMs: Long?,
    val isRead: Boolean,
    /** The provider's `thread_id`, the app's primary thread anchor; null when absent. */
    val providerThreadId: Long?,
    /** The provider's `sub_id` (which SIM carried it); null when unknown. */
    val subscriptionId: Int?,
    /** Every non-text part, already read into memory. */
    val attachments: List<MmsPart>,
    /**
     * The `TO` addresses - group size, for a future group UI. For a sent
     * row this includes [address] itself (it IS the first recipient).
     */
    val recipients: List<String>,
)

/**
 * One page of provider rows in `_id` order. [lastId] is the highest `_id`
 * the page covered, INCLUDING rows skipped as already stored, so the
 * checkpoint can advance past them; null when the page was empty.
 */
data class ProviderMmsPage(
    val messages: List<ProviderMms>,
    val lastId: Long?,
    /** Rows the query returned (stored + skipped) - the paging signal. */
    val rowCount: Int,
)

/**
 * Read-only view of the system MMS provider (`content://mms`), the source
 * for the MMS history import (issues #94 and #112).
 *
 * Received MMS reach the app two ways. A message arriving while Clear SMS
 * holds the default-SMS role comes in as a WAP push and is stored by
 * [MmsInbound] in the app's own tables. Everything older lives ONLY in the
 * shared system provider, which this class reads - without it, an MMS that
 * predates the install (or arrived while another app was default) is
 * invisible forever, which is exactly what issue #94 reported.
 *
 * Sent MMS have only ONE way in. An MMS sent from Clear SMS is written to
 * the app's own tables by [MmsSender]; one sent from any OTHER app exists
 * solely in the provider's SENT box. Issue #112: the first import read the
 * INBOX only, so a conversation transferred from another phone showed
 * every received picture and none of the user's own replies.
 *
 * Which `msg_box` values are imported, and why:
 * - `INBOX` (1) - received, as before.
 * - `SENT` (2) - the other app's send succeeded; imported as an outgoing
 *   message. No delivery report exists for it (another app sent it, and
 *   the provider records none for MMS), so it is stored as SENT, never
 *   DELIVERED.
 * - `DRAFTS` (3) - never sent; importing one would fabricate a message the
 *   other party never received. Skipped.
 * - `OUTBOX` (4) - another app's send still in flight (or stranded). Its
 *   outcome is unknown and not ours to retry. Skipped.
 * - `FAILED` (5) - skipped, as the SMS import skips failed SMS: a FAILED
 *   bubble offers "tap to retry", which would resend another app's message
 *   through this one with no idea why it failed.
 *
 * Three provider tables are involved, and the layout is why this is not a
 * one-query job:
 *
 * - `content://mms` - one row per message: dates, read flag, box, thread.
 *   It holds NO address and NO text.
 * - `content://mms/<id>/addr` - the addresses, typed by PduHeaders:
 *   `137` (FROM) is the sender, `151` (TO) the recipients. Which one names
 *   the thread depends on the box - see [ProviderMms.address].
 * - `content://mms/part` (`mid = <id>`) - the content. A `text/plain` part
 *   keeps its text in the `text` column; a binary part's bytes are read
 *   through `openInputStream` on the part uri, never from `_data` (that
 *   raw filesystem path is not readable by apps).
 *
 * Every read is defensive: a missing column, an unreadable part or a
 * malformed row degrades that one message (or that one field) instead of
 * failing the import.
 */
@Singleton
class SystemMmsSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * Received and sent MMS with `_id > afterId`, oldest id first, at
         * most [limit] rows. Rows whose id is in [alreadyStored] are not
         * read (no addr/part queries, no attachment bytes) but still count
         * towards the page and its [ProviderMmsPage.lastId] - this is what
         * makes a widened re-scan of an already-imported history cheap.
         * An empty page when the provider cannot be read.
         */
        fun messagesAfter(
            afterId: Long,
            limit: Int,
            alreadyStored: Set<Long> = emptySet(),
        ): ProviderMmsPage =
            try {
                context.contentResolver
                    .query(
                        Telephony.Mms.CONTENT_URI,
                        null,
                        "${Telephony.Mms._ID} > ? AND $IMPORTED_BOXES",
                        arrayOf(afterId.toString()),
                        "${Telephony.Mms._ID} ASC LIMIT $limit",
                    )?.use { cursor -> readMessages(cursor, alreadyStored) }
                    ?: EMPTY_PAGE
            } catch (e: Exception) {
                // No provider, revoked read permission, OEM quirk: the
                // import simply has nothing to do.
                Diag.e(TAG, "mms provider unreadable", e)
                EMPTY_PAGE
            }

        /** Total importable (received + sent) MMS in the provider, for progress; null when unknown. */
        fun importableCount(): Int? =
            try {
                context.contentResolver
                    .query(
                        Telephony.Mms.CONTENT_URI,
                        arrayOf(Telephony.Mms._ID),
                        IMPORTED_BOXES,
                        null,
                        null,
                    )?.use { it.count }
            } catch (e: Exception) {
                Diag.e(TAG, "mms provider count failed", e)
                null
            }

        private fun readMessages(
            cursor: Cursor,
            alreadyStored: Set<Long>,
        ): ProviderMmsPage {
            val idIdx = cursor.getColumnIndex(Telephony.Mms._ID)
            if (idIdx < 0) return EMPTY_PAGE
            val boxIdx = cursor.getColumnIndex(Telephony.Mms.MESSAGE_BOX)
            val dateIdx = cursor.getColumnIndex(Telephony.Mms.DATE)
            val dateSentIdx = cursor.getColumnIndex(Telephony.Mms.DATE_SENT)
            val readIdx = cursor.getColumnIndex(Telephony.Mms.READ)
            val threadIdx = cursor.getColumnIndex(Telephony.Mms.THREAD_ID)
            val subIdx = cursor.getColumnIndex(Telephony.Mms.SUBSCRIPTION_ID)
            var lastId: Long? = null
            var rowCount = 0
            val messages =
                buildList {
                    while (cursor.moveToNext()) {
                        val systemMmsId = cursor.getLong(idIdx)
                        rowCount++
                        lastId = systemMmsId
                        if (systemMmsId in alreadyStored) continue
                        // A row the selection should have excluded (a
                        // provider that ignores `msg_box`) is dropped here
                        // rather than imported under a guessed direction.
                        val isOutgoing =
                            when (cursor.longOrNull(boxIdx)?.toInt()) {
                                Telephony.Mms.MESSAGE_BOX_INBOX, null -> false
                                Telephony.Mms.MESSAGE_BOX_SENT -> true
                                else -> continue
                            }
                        val row =
                            try {
                                readOne(
                                    cursor = cursor,
                                    systemMmsId = systemMmsId,
                                    isOutgoing = isOutgoing,
                                    dateIdx = dateIdx,
                                    dateSentIdx = dateSentIdx,
                                    readIdx = readIdx,
                                    threadIdx = threadIdx,
                                    subIdx = subIdx,
                                )
                            } catch (e: Exception) {
                                // One bad message must not end the import: it is
                                // skipped, and the checkpoint still advances past
                                // it so the next run does not retry it forever.
                                Diag.e(TAG, "mms row skipped", e, id("systemMms", systemMmsId))
                                null
                            }
                        if (row != null) add(row)
                    }
                }
            return ProviderMmsPage(messages = messages, lastId = lastId, rowCount = rowCount)
        }

        private fun readOne(
            cursor: Cursor,
            systemMmsId: Long,
            isOutgoing: Boolean,
            dateIdx: Int,
            dateSentIdx: Int,
            readIdx: Int,
            threadIdx: Int,
            subIdx: Int,
        ): ProviderMms {
            val addresses = addresses(systemMmsId)
            val parts = parts(systemMmsId)
            return ProviderMms(
                systemMmsId = systemMmsId,
                isOutgoing = isOutgoing,
                // A sent row's FROM is this device; the party that names the
                // thread is who it was sent TO.
                address = if (isOutgoing) addresses.firstTo else addresses.sender,
                body = parts.text,
                timestampMs = secondsToMillis(cursor.longOrNull(dateIdx)) ?: System.currentTimeMillis(),
                sentAtMs = secondsToMillis(cursor.longOrNull(dateSentIdx)),
                isRead = (cursor.longOrNull(readIdx) ?: 1L) != 0L,
                providerThreadId = cursor.longOrNull(threadIdx)?.takeIf { it > 0 },
                subscriptionId = cursor.longOrNull(subIdx)?.toInt()?.takeIf { it >= 0 },
                attachments = parts.attachments,
                recipients = addresses.recipients,
            )
        }

        /** Sender + recipients from `content://mms/<id>/addr`. */
        private fun addresses(systemMmsId: Long): MmsAddresses {
            val uri =
                Telephony.Mms.CONTENT_URI
                    .buildUpon()
                    .appendPath(systemMmsId.toString())
                    .appendPath("addr")
                    .build()
            var sender: String? = null
            var firstTo: String? = null
            val recipients = mutableListOf<String>()
            try {
                context.contentResolver
                    .query(uri, arrayOf(ADDR_ADDRESS, ADDR_TYPE), null, null, null)
                    ?.use { cursor ->
                        val addressIdx = cursor.getColumnIndex(ADDR_ADDRESS)
                        val typeIdx = cursor.getColumnIndex(ADDR_TYPE)
                        if (addressIdx < 0) return@use
                        while (cursor.moveToNext()) {
                            val address = cursor.getString(addressIdx)?.trim().orEmpty()
                            // The insert-address token is a placeholder the
                            // PDU uses for "the device itself", never a real
                            // party, so it is not a sender or a recipient.
                            if (address.isEmpty() || address == INSERT_ADDRESS_TOKEN) continue
                            when (if (typeIdx >= 0) cursor.longOrNull(typeIdx) else null) {
                                PDU_HEADER_FROM -> if (sender == null) sender = address
                                PDU_HEADER_TO -> {
                                    if (firstTo == null) firstTo = address
                                    recipients += address
                                }
                                // CC / BCC: part of the group, never the thread party.
                                else -> recipients += address
                            }
                        }
                    }
            } catch (e: Exception) {
                Diag.e(TAG, "mms addresses unreadable", e, id("systemMms", systemMmsId))
            }
            return MmsAddresses(sender = sender, firstTo = firstTo, recipients = recipients)
        }

        /** Text + binary parts from `content://mms/part` for one message. */
        private fun parts(systemMmsId: Long): MmsParts {
            val text = StringBuilder()
            val attachments = mutableListOf<MmsPart>()
            try {
                context.contentResolver
                    .query(PART_URI, null, "$PART_MSG_ID = ?", arrayOf(systemMmsId.toString()), null)
                    ?.use { cursor ->
                        val partIdIdx = cursor.getColumnIndex(Telephony.Mms.Part._ID)
                        val typeIdx = cursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
                        val textIdx = cursor.getColumnIndex(Telephony.Mms.Part.TEXT)
                        while (cursor.moveToNext()) {
                            val mime = (if (typeIdx >= 0) cursor.getString(typeIdx) else null)?.lowercase().orEmpty()
                            // SMIL is the slide-layout document, not content.
                            if (mime.isEmpty() || mime == MIME_SMIL || mime == MIME_TEXT_HTML) continue
                            if (mime == MIME_TEXT_PLAIN) {
                                val value = if (textIdx >= 0) cursor.getString(textIdx) else null
                                if (!value.isNullOrBlank()) {
                                    if (text.isNotEmpty()) text.append('\n')
                                    text.append(value)
                                }
                                continue
                            }
                            if (partIdIdx < 0) continue
                            val part = readBinaryPart(cursor, cursor.getLong(partIdIdx), mime)
                            if (part != null) attachments += part
                        }
                    }
            } catch (e: Exception) {
                Diag.e(TAG, "mms parts unreadable", e, id("systemMms", systemMmsId))
            }
            return MmsParts(text = text.toString(), attachments = attachments)
        }

        /**
         * Reads one binary part's bytes through the provider stream. The
         * `_data` column holds a path in the telephony process's own
         * storage, which this app cannot open - the documented route is
         * `openInputStream` on `content://mms/part/<id>`.
         */
        private fun readBinaryPart(
            cursor: Cursor,
            partId: Long,
            mime: String,
        ): MmsPart? {
            val uri = ContentUris.withAppendedId(PART_URI, partId)
            val bytes =
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    Diag.e(TAG, "mms part stream unreadable", e, id("part", partId))
                    null
                }
            if (bytes == null) {
                // The provider refused to open the part and said so only in
                // its OWN log - for instance when the row's `_data` path is
                // outside the directory it will serve from. Without this
                // line the attachment just vanishes from an otherwise
                // successful import, which is the hardest kind of bug to
                // read back from a user's diagnostic report.
                Diag.w(TAG, "mms part not served by the provider", null, id("part", partId))
                return null
            }
            if (bytes.isEmpty()) {
                Diag.w(TAG, "mms part is empty", null, id("part", partId))
                return null
            }
            if (bytes.size > MAX_PART_BYTES) {
                // A carrier MMS cannot legitimately be this large; refusing
                // keeps a corrupt provider row from exhausting memory.
                Diag.w(TAG, "mms part too large to import", null, id("part", partId), count("bytes", bytes.size.toLong()))
                return null
            }
            val name =
                sequenceOf(PART_FILENAME, PART_NAME, PART_CONTENT_LOCATION)
                    .mapNotNull { column ->
                        cursor
                            .getColumnIndex(column)
                            .takeIf { it >= 0 }
                            ?.let(cursor::getString)
                            ?.trim()
                    }.firstOrNull { it.isNotEmpty() }
            return MmsPart(mimeType = mime, fileName = name, data = bytes)
        }

        private fun Cursor.longOrNull(index: Int): Long? =
            if (index < 0 || isNull(index)) {
                null
            } else {
                try {
                    getLong(index)
                } catch (_: Exception) {
                    null
                }
            }

        private data class MmsAddresses(
            /** The `FROM` (137) address. */
            val sender: String?,
            /** The first `TO` (151) address - a sent row's thread party. */
            val firstTo: String?,
            /** Every non-FROM address (TO, CC, BCC), in table order. */
            val recipients: List<String>,
        )

        private data class MmsParts(
            val text: String,
            val attachments: List<MmsPart>,
        )

        companion object {
            private const val TAG = "SystemMmsSource"

            /**
             * `content://mms` stores dates in SECONDS (unlike `content://sms`,
             * which uses milliseconds) - the single most common bug when
             * reading this provider, so the conversion lives in one place.
             * A non-positive value is the provider's "unknown".
             */
            internal fun secondsToMillis(seconds: Long?): Long? = seconds?.takeIf { it > 0 }?.times(1000L)

            private val PART_URI: Uri = Uri.parse("content://mms/part")

            /**
             * `Telephony.Mms.Part.MSG_ID` is hidden on some API levels, so the
             * column name is spelled out - it has been `mid` since the
             * provider existed.
             */
            private const val PART_MSG_ID = "mid"
            private const val PART_FILENAME = "fn"
            private const val PART_NAME = "name"
            private const val PART_CONTENT_LOCATION = "cl"

            private const val ADDR_ADDRESS = "address"
            private const val ADDR_TYPE = "type"

            /**
             * The boxes the import reads: received and sent. Drafts, the
             * outbox and failed sends are excluded by the query itself - see
             * the class doc for why each is left alone.
             */
            internal val IMPORTED_BOXES =
                "${Telephony.Mms.MESSAGE_BOX} IN (${Telephony.Mms.MESSAGE_BOX_INBOX}, ${Telephony.Mms.MESSAGE_BOX_SENT})"

            private val EMPTY_PAGE = ProviderMmsPage(messages = emptyList(), lastId = null, rowCount = 0)

            /** `PduHeaders.FROM`; the addr table's typing is numeric. */
            private const val PDU_HEADER_FROM = 137L

            /** `PduHeaders.TO` - the recipients; for a sent row, the other party. */
            private const val PDU_HEADER_TO = 151L

            /** `PduHeaders.INSERT_ADDRESS_TOKEN` - "this device", not a party. */
            private const val INSERT_ADDRESS_TOKEN = "insert-address-token"

            private const val MIME_TEXT_PLAIN = "text/plain"
            private const val MIME_TEXT_HTML = "text/html"
            private const val MIME_SMIL = "application/smil"

            /** Generous ceiling for a single part; carrier MMS caps well below. */
            private const val MAX_PART_BYTES = 16 * 1024 * 1024
        }
    }

/** Whether a provider MMS carried anything worth storing. */
internal fun ProviderMms.hasContent(): Boolean = body.isNotBlank() || attachments.isNotEmpty()

internal fun ProviderMms.diagFields() =
    arrayOf(
        id("systemMms", systemMmsId),
        count("attachments", attachments.size.toLong()),
        flag("addressKnown", !address.isNullOrEmpty()),
        flag("outgoing", isOutgoing),
    )
