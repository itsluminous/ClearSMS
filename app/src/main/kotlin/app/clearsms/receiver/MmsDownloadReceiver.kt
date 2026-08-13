package app.clearsms.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.clearsms.data.db.MessageDao
import app.clearsms.di.ApplicationScope
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
        val succeeded = resultCode == Activity.RESULT_OK && !intent.getBooleanExtra(EXTRA_START_FAILED, false)
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
                Log.e(TAG, "Failed to handle MMS download result", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MmsDownloadReceiver"
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
