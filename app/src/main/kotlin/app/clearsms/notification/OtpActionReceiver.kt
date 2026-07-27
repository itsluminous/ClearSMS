package app.clearsms.notification

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.clearsms.R
import app.clearsms.data.repository.MessageRepository
import app.clearsms.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the Copy / Share / Delete actions on OTP notifications.
 *
 * Copy runs here (rather than at receive time) because on Android Q+ apps may
 * not touch the clipboard from the background; a user-triggered notification
 * action is the supported path.
 */
@AndroidEntryPoint
class OtpActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var otpNotifier: OtpNotifier

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        val otp = intent.getStringExtra(EXTRA_OTP) ?: return
        when (intent.action) {
            ACTION_COPY -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.otp_clip_label), otp))
                Toast.makeText(context, R.string.otp_copied, Toast.LENGTH_SHORT).show()
                otpNotifier.cancel(messageId)
            }
            ACTION_SHARE -> {
                val share =
                    Intent
                        .createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, otp),
                            context.getString(R.string.otp_share_title),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(share)
                otpNotifier.cancel(messageId)
            }
            ACTION_DELETE -> {
                if (messageId <= 0L) return
                val pendingResult = goAsync()
                applicationScope.launch {
                    try {
                        messageRepository.delete(messageId)
                        otpNotifier.cancel(messageId)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_COPY = "app.clearsms.action.OTP_COPY"
        const val ACTION_SHARE = "app.clearsms.action.OTP_SHARE"
        const val ACTION_DELETE = "app.clearsms.action.OTP_DELETE"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_OTP = "otp"
    }
}
