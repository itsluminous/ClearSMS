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
import app.clearsms.diagnostics.DiagField.Companion.label
import app.clearsms.diagnostics.DiagField.Companion.packageName
import app.clearsms.mms.MmsInbound
import app.clearsms.mms.MmsRadioConditions
import app.clearsms.mms.MmsRadioConditionsProbe
import app.clearsms.mms.TriState
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
 * able to download either. A failure also carries the radio conditions at
 * that instant ([MmsRadioConditions], the send path's probe) and what the
 * PDU write grant reached (count and package names, forwarded by
 * [app.clearsms.mms.SystemMmsDownloader] on the result intent): issue
 * #135 was undiagnosable from the code alone.
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

    @Inject
    lateinit var conditions: MmsRadioConditionsProbe

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
        val report = MmsDownloadReport.of(intent, resultCode, attempt, conditions.probe(subscriptionId = null))
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

        /** How many packages the PDU write grant was attempted for (resolved + fallbacks). */
        const val EXTRA_GRANTS_ATTEMPTED = "app.clearsms.mms.GRANTS_ATTEMPTED"

        /** How many of those grants the platform accepted. */
        const val EXTRA_GRANTS_LANDED = "app.clearsms.mms.GRANTS_LANDED"

        /** The package names the grant landed on - system software identifiers, never user data. */
        const val EXTRA_GRANTED_PACKAGES = "app.clearsms.mms.GRANTED_PACKAGES"

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
 * (our own broadcast, no platform code exists), the MMSC's HTTP status
 * when the platform attached one, what the PDU write grant reached, and
 * the radio at the instant of the result. Pure, so the receiver's logging
 * is unit-testable.
 */
data class MmsDownloadReport(
    val resultCode: Int,
    val attempt: Int,
    val startFailed: Boolean,
    val httpStatus: Int?,
    /** Packages the write grant was attempted for; 0 when the intent predates the extra. */
    val grantsAttempted: Int = 0,
    /** Packages that accepted the grant. */
    val grantsLanded: Int = 0,
    /** Names of the packages that accepted the grant (system package names). */
    val grantedPackages: List<String> = emptyList(),
    /** Network / data / SIM conditions at the result; null when not probed. */
    val conditions: MmsRadioConditions? = null,
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
                count("grantsAttempted", grantsAttempted),
                count("grantsLanded", grantsLanded),
                label("network", TriState.of(conditions?.networkConnected)),
                label("cellular", TriState.of(conditions?.cellular)),
                label("mobileData", TriState.of(conditions?.mobileDataEnabled)),
                count("slot", conditions?.slot ?: 0),
                count("dataSlot", conditions?.dataSlot ?: 0),
                label("onDataSim", TriState.of(conditions?.onDataSim)),
                // One field per package the grant landed on, so the line
                // itself says whether anything plausible can write the PDU.
                *grantedPackages.map { packageName(it) }.toTypedArray(),
            )
        }
    }

    companion object {
        /**
         * Builds the report from the result intent. [conditions] is the
         * radio as probed at receipt (the download rides the system-default
         * SmsManager, so the default SMS subscription is the one measured).
         */
        fun of(
            intent: Intent,
            resultCode: Int,
            attempt: Int,
            conditions: MmsRadioConditions? = null,
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
                grantsAttempted = intent.getIntExtra(MmsDownloadReceiver.EXTRA_GRANTS_ATTEMPTED, 0),
                grantsLanded = intent.getIntExtra(MmsDownloadReceiver.EXTRA_GRANTS_LANDED, 0),
                grantedPackages = intent.getStringArrayExtra(MmsDownloadReceiver.EXTRA_GRANTED_PACKAGES)?.toList().orEmpty(),
                conditions = conditions,
            )
    }
}
