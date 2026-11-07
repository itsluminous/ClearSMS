package app.clearsms.diagnostics

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import app.clearsms.data.db.MessageDao
import app.clearsms.mms.SystemMmsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How far the system message provider is ahead of the app's own store, in
 * ROW IDS - the single number that tells a maintainer "the system holds
 * messages this app does not" without a byte of their content.
 *
 * Row ids are per-device sequence numbers (a count of rows ever inserted),
 * so a gap is a count, never an identifier: the header's privacy contract
 * (`DiagnosticReportTest`) stays intact.
 *
 * @property providerMaxId the highest importable (received or sent) row id
 *   in the provider, null when the provider could not be read or is empty.
 * @property localMaxId the highest provider row id the app has stored, null
 *   when none has been.
 * @property providerRowsAboveLocal how many importable provider rows have an
 *   id above [localMaxId] - rows that exist on the device and not in the
 *   app, exactly what the catch-up import would bring in. Null when the
 *   provider could not be read.
 */
data class ProviderGap(
    val providerMaxId: Long?,
    val localMaxId: Long?,
    val providerRowsAboveLocal: Int?,
) {
    /** `providerMaxId - localMaxId`, null when either side is unknown. Positive means rows the app never saw. */
    val maxIdGap: Long?
        get() = if (providerMaxId == null || localMaxId == null) null else providerMaxId - localMaxId

    /** The header's `key=value` tokens, e.g. `providerMaxId=1234 localMaxId=1230 gap=4 providerRowsAboveLocal=3`. */
    fun render(): String =
        "providerMaxId=${providerMaxId ?: "unknown"} localMaxId=${localMaxId ?: "none"} " +
            "gap=${maxIdGap ?: "unknown"} providerRowsAboveLocal=${providerRowsAboveLocal ?: "unknown"}"

    companion object {
        /** The value when the provider was not consulted at all (no role, no permission, test fixture). */
        val UNKNOWN = ProviderGap(providerMaxId = null, localMaxId = null, providerRowsAboveLocal = null)
    }
}

/**
 * Reads a [ProviderGap] for SMS and for MMS. Read-only and content-free by
 * construction: every query projects `_id` alone (or a COUNT), restricted to
 * the boxes the importer stores, so a draft or a stranded outbox row cannot
 * inflate the gap. Three indexed single-row queries per call - cheap enough
 * for the diagnostics screen, which is the only caller.
 *
 * The catch-up scheduler keeps its own probe: that one DECIDES whether to
 * import, this one only REPORTS, and the two must stay free to differ.
 */
@Singleton
class ProviderGapProbe
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val messageDao: MessageDao,
    ) {
        suspend fun sms(): ProviderGap {
            val local = runCatching { messageDao.maxSystemSmsId() }.getOrNull()
            return gap(
                uri = Telephony.Sms.CONTENT_URI,
                idColumn = Telephony.Sms._ID,
                boxes = SMS_IMPORTED_TYPES,
                localMaxId = local,
            )
        }

        suspend fun mms(): ProviderGap {
            val local = runCatching { messageDao.maxSystemMmsId() }.getOrNull()
            return gap(
                uri = Telephony.Mms.CONTENT_URI,
                idColumn = Telephony.Mms._ID,
                boxes = SystemMmsSource.IMPORTED_BOXES,
                localMaxId = local,
            )
        }

        private fun gap(
            uri: Uri,
            idColumn: String,
            boxes: String,
            localMaxId: Long?,
        ): ProviderGap {
            val resolver = context.contentResolver
            val providerMax =
                try {
                    resolver
                        .query(uri, arrayOf(idColumn), boxes, null, "$idColumn DESC LIMIT 1")
                        ?.use { if (it.moveToFirst()) it.getLong(0) else null }
                } catch (_: Exception) {
                    return ProviderGap(providerMaxId = null, localMaxId = localMaxId, providerRowsAboveLocal = null)
                }
            val above =
                try {
                    resolver
                        .query(
                            uri,
                            arrayOf(idColumn),
                            "$idColumn > ? AND $boxes",
                            arrayOf((localMaxId ?: 0L).toString()),
                            null,
                        )?.use { it.count }
                } catch (_: Exception) {
                    null
                }
            return ProviderGap(providerMaxId = providerMax, localMaxId = localMaxId, providerRowsAboveLocal = above)
        }

        private companion object {
            /** The same boxes `SystemSmsImporter.SELECTION` imports: received and sent. */
            val SMS_IMPORTED_TYPES =
                "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})"
        }
    }
