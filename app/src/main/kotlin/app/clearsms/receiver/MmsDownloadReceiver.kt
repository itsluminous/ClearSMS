package app.clearsms.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import app.clearsms.data.db.MessageDao
import app.clearsms.di.ApplicationScope
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.code
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.diagnostics.DiagField.Companion.id
import app.clearsms.mms.MmsInbound
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Result target of [android.telephony.SmsManager.downloadMultimediaMessage]
 * (see [app.clearsms.mms.SystemMmsDownloader]). NOT exported: only our own
 * PendingIntents (and our own start-failure broadcast) may report a result.
 * The outcome is delegated to [MmsInbound]: success parses and stores the
 * retrieved message; failure retries once, then marks the row FAILED.
 *
 * Every result is logged through [Diag] with the RAW platform result code
 * and the HTTP status the platform attaches to an MMSC refusal - the same
 * treatment the send path gets, since a user who cannot send may not be
 * able to download either.
 */
@AndroidEntryPoint
class MmsDownloadReceiver : BroadcastReceiver() {
    @Inject
    lateinit var mmsInbound: MmsInbound

    @Inject
    lateinit var messageDao: MessageDao

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
        val report = MmsDownloadReport.of(intent, resultCode, attempt)
        report.log(messageId)
        val succeeded = report.succeeded
        val pendingResult = goAsync()
        applicationScope.launch {
            try {
                mmsInbound.onDownloadResult(
                    messageId = messageId,
                    succeeded = succeeded,
                    attempt = attempt,
                    contentLocation = { messageDao.getById(messageId)?.mmsContentLocation },
                )
            } catch (e: Exception) {
                // Content-free by convention; the row simply stays PENDING
                // until a retry, rather than crashing the process.
                Diag.e(TAG, "mms download result handling failed", e, id("message", messageId), count("attempt", attempt))
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val TAG = "MmsDownload"
        const val EXTRA_MESSAGE_ID = "app.clearsms.mms.MESSAGE_ID"
        const val EXTRA_ATTEMPT = "app.clearsms.mms.ATTEMPT"

        /** Set when the download could not even be started (no result code exists). */
        const val EXTRA_START_FAILED = "app.clearsms.mms.START_FAILED"

        /** The explicit (non-exported) intent both the PendingIntent and the start-failure path use. */
        fun intent(
            context: Context,
            messageId: Long,
            attempt: Int,
        ): Intent =
            Intent(context, MmsDownloadReceiver::class.java)
                .putExtra(EXTRA_MESSAGE_ID, messageId)
                .putExtra(EXTRA_ATTEMPT, attempt)
    }
}

/**
 * The loggable shape of one platform MMS download result: the raw code,
 * which attempt it was, whether the transaction could not even be started
 * (our own broadcast, no platform code exists), and the MMSC's HTTP status
 * when the platform attached one. Pure, so the receiver's logging is
 * unit-testable.
 */
data class MmsDownloadReport(
    val resultCode: Int,
    val attempt: Int,
    val startFailed: Boolean,
    val httpStatus: Int?,
) {
    val succeeded: Boolean get() = resultCode == Activity.RESULT_OK && !startFailed

    fun log(messageId: Long) {
        if (succeeded) {
            Diag.i(
                MmsDownloadReceiver.TAG,
                "mms downloaded",
                id("message", messageId),
                code("result", resultCode),
                count("attempt", attempt),
            )
        } else {
            Diag.w(
                MmsDownloadReceiver.TAG,
                "mms download failed",
                null,
                id("message", messageId),
                code("result", resultCode),
                count("attempt", attempt),
                flag("startFailed", startFailed),
                flag("httpStatusPresent", httpStatus != null),
                code("httpStatus", httpStatus ?: 0),
            )
        }
    }

    companion object {
        fun of(
            intent: Intent,
            resultCode: Int,
            attempt: Int,
        ): MmsDownloadReport =
            MmsDownloadReport(
                resultCode = resultCode,
                attempt = attempt,
                startFailed = intent.getBooleanExtra(MmsDownloadReceiver.EXTRA_START_FAILED, false),
                httpStatus =
                    if (intent.hasExtra(SmsManager.EXTRA_MMS_HTTP_STATUS)) {
                        intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
                    } else {
                        null
                    },
            )
    }
}
