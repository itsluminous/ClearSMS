package app.clearsms.mms

/*
 * The pure decisions [FrameworkMmsGateway] makes before it hands a staged
 * `m-send-req` PDU to the platform MMS service (issue #51: a Huawei
 * Android 12 phone answered `MMS_ERROR_IO_ERROR` 22-30 ms after every
 * hand-over - the platform never read our PDU, so nothing reached a
 * carrier).
 *
 * In AOSP (`MmsRequest.execute`), a send fails with `MMS_ERROR_IO_ERROR`
 * in exactly one place: `prepareForHttpRequest` -> `readPduFromContentUri`
 * returned null, BEFORE any network activity. That read returns null when
 *
 *  1. `ContentResolver.openFileDescriptor` threw - the reading uid holds no
 *     read grant on our FileProvider URI ([PduReadGrant]);
 *  2. the file was empty ([StagedPduCheck]);
 *  3. the PDU is larger than the carrier config `maxMessageSize`
 *     (AOSP default 300 KiB = 307 200 bytes) - "PDU read is too large".
 *     The reporter's PDU was 326 369 bytes: over that default. This one is
 *     not handled here at all but PREVENTED upstream - attachments are
 *     compressed to fit the carrier's limit when they are staged
 *     ([MmsSizeBudget], [OutgoingAttachmentStager]) and [MmsSender]
 *     refuses to hand over a PDU that still exceeds it. An earlier
 *     revision passed an `MMS_CONFIG_MAX_MESSAGE_SIZE` override instead;
 *     it was dropped so that exactly ONE mechanism decides the limit - an
 *     override that let the platform read what the compressor should have
 *     shrunk would only move the failure to the MMSC.
 *
 * All three are instant and indistinguishable from the result code alone;
 * the gateway therefore logs the outcome of each so the next report is
 * conclusive. Everything here is framework-free and unit-tested.
 */

/**
 * Decides WHICH packages get a read grant on the staged PDU and records
 * how each grant went. The platform resolves the reader for us
 * ([resolvedReaders]: the `CarrierMessagingService` packages - the OEM /
 * carrier override point - and the packages sharing the telephony uid);
 * the two historical AOSP homes of the MMS service stay as fallbacks so a
 * stock build behaves exactly as before. A failed grant (package absent,
 * or refused) is recorded, never thrown: the send goes ahead and the
 * platform's own verdict is the datum.
 */
class PduReadGrant(
    private val resolvedReaders: () -> List<String>,
    private val grantRead: (packageName: String) -> Unit,
) {
    /** One attempted grant. [resolved] is false for a fallback name. */
    data class Outcome(
        val packageName: String,
        val resolved: Boolean,
        val granted: Boolean,
    )

    /**
     * Grants to every resolved package, then to every fallback not already
     * covered, in that order; duplicates collapse to their first mention.
     * A throwing resolver yields the fallbacks alone.
     */
    fun grantAll(): List<Outcome> {
        val resolved =
            try {
                resolvedReaders().filter(::isPackageName).distinct()
            } catch (_: Exception) {
                emptyList()
            }
        val targets = resolved.map { it to true } + FALLBACK_PACKAGES.filterNot { it in resolved }.map { it to false }
        return targets.map { (pkg, wasResolved) ->
            val granted =
                try {
                    grantRead(pkg)
                    true
                } catch (_: Exception) {
                    false
                }
            Outcome(pkg, wasResolved, granted)
        }
    }

    companion object {
        /** The two homes of the AOSP MMS service (they share `android.uid.phone`). */
        val FALLBACK_PACKAGES = listOf("com.android.phone", "com.android.mms.service")

        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        /** True for a well-formed Android package name (two or more dotted Java identifiers). */
        fun isPackageName(value: String): Boolean = value.length <= MAX_PACKAGE_NAME_LENGTH && PACKAGE.matches(value)

        private const val MAX_PACKAGE_NAME_LENGTH = 255
    }
}

/**
 * The staged PDU's state immediately before hand-over: does the file exist
 * and does it hold every byte that was encoded? A racing delete or
 * truncation (a resend rewrites the SAME `<messageId>.pdu` slot the
 * previous attempt used) would show up here rather than as a silent
 * platform IO error.
 */
data class StagedPduCheck(
    val exists: Boolean,
    val lengthBytes: Long,
    val expectedBytes: Int,
) {
    /** Hand-over is worthwhile only for a present, non-empty, complete file. */
    val handoverSafe: Boolean get() = exists && lengthBytes > 0 && lengthBytes == expectedBytes.toLong()

    companion object {
        fun of(
            exists: Boolean,
            lengthBytes: Long,
            expectedBytes: Int,
        ): StagedPduCheck = StagedPduCheck(exists, if (exists) lengthBytes else 0L, expectedBytes)
    }
}

/** Thrown by the gateway when the staged PDU cannot be opened through our own FileProvider. */
class StagedPduUnreadableException(
    cause: Throwable? = null,
) : IllegalStateException("staged PDU is not readable through the FileProvider", cause)
