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

/** One received MMS read out of the system MMS provider. */
data class ProviderMms(
    /** The provider's `_id`, the import's idempotency key. */
    val systemMmsId: Long,
    /** The sender address from the `addr` table; null when the PDU carried none. */
    val sender: String?,
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
    /** Addresses other than the sender - group size, for a future group UI. */
    val recipients: List<String>,
)

/**
 * Read-only view of the system MMS provider (`content://mms`), the source
 * for the MMS history import (issue #94).
 *
 * Received MMS reach the app two ways. A message arriving while Clear SMS
 * holds the default-SMS role comes in as a WAP push and is stored by
 * [MmsInbound] in the app's own tables. Everything older lives ONLY in the
 * shared system provider, which this class reads - without it, an MMS that
 * predates the install (or arrived while another app was default) is
 * invisible forever, which is exactly what issue #94 reported.
 *
 * Three provider tables are involved, and the layout is why this is not a
 * one-query job:
 *
 * - `content://mms` - one row per message: dates, read flag, box, thread.
 *   It holds NO address and NO text.
 * - `content://mms/<id>/addr` - the addresses, typed by PduHeaders:
 *   `137` (FROM) is the sender, `151` (TO) the recipients.
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
         * Received MMS with `_id > afterId`, oldest id first, at most
         * [limit] of them. Empty when the provider cannot be read.
         */
        fun inboxAfter(
            afterId: Long,
            limit: Int,
        ): List<ProviderMms> =
            try {
                context.contentResolver
                    .query(
                        Telephony.Mms.CONTENT_URI,
                        null,
                        "${Telephony.Mms._ID} > ? AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX}",
                        arrayOf(afterId.toString()),
                        "${Telephony.Mms._ID} ASC LIMIT $limit",
                    )?.use { cursor -> readMessages(cursor) }
                    .orEmpty()
            } catch (e: Exception) {
                // No provider, revoked read permission, OEM quirk: the
                // import simply has nothing to do.
                Diag.e(TAG, "mms provider unreadable", e)
                emptyList()
            }

        /** Total received MMS in the provider, for progress; null when unknown. */
        fun inboxCount(): Int? =
            try {
                context.contentResolver
                    .query(
                        Telephony.Mms.CONTENT_URI,
                        arrayOf(Telephony.Mms._ID),
                        "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX}",
                        null,
                        null,
                    )?.use { it.count }
            } catch (e: Exception) {
                Diag.e(TAG, "mms provider count failed", e)
                null
            }

        private fun readMessages(cursor: Cursor): List<ProviderMms> {
            val idIdx = cursor.getColumnIndex(Telephony.Mms._ID)
            if (idIdx < 0) return emptyList()
            val dateIdx = cursor.getColumnIndex(Telephony.Mms.DATE)
            val dateSentIdx = cursor.getColumnIndex(Telephony.Mms.DATE_SENT)
            val readIdx = cursor.getColumnIndex(Telephony.Mms.READ)
            val threadIdx = cursor.getColumnIndex(Telephony.Mms.THREAD_ID)
            val subIdx = cursor.getColumnIndex(Telephony.Mms.SUBSCRIPTION_ID)
            return buildList {
                while (cursor.moveToNext()) {
                    val systemMmsId = cursor.getLong(idIdx)
                    val row =
                        try {
                            readOne(
                                cursor = cursor,
                                systemMmsId = systemMmsId,
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
        }

        private fun readOne(
            cursor: Cursor,
            systemMmsId: Long,
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
                sender = addresses.sender,
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
                                else -> recipients += address
                            }
                        }
                    }
            } catch (e: Exception) {
                Diag.e(TAG, "mms addresses unreadable", e, id("systemMms", systemMmsId))
            }
            return MmsAddresses(sender = sender, recipients = recipients)
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
            val sender: String?,
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

            /** `PduHeaders.FROM`; the addr table's typing is numeric. */
            private const val PDU_HEADER_FROM = 137L

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
        flag("senderKnown", !sender.isNullOrEmpty()),
    )
