package app.clearsms.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.clearsms.data.db.MessageDao
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.OtpAutoDeletePolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodically deletes OTP messages older than the user's retention policy
 * (24 hours / 3 days / 7 days / 1 month / 3 months / never).
 */
@HiltWorker
class OtpAutoDeleteWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val messageDao: MessageDao,
        private val settingsRepository: SettingsRepository,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val policy = settingsRepository.otpAutoDeletePolicy.first()
            val cutoff = cutoffFor(policy, System.currentTimeMillis()) ?: return Result.success()
            val stale = messageDao.messagesOlderThan(Category.OTP, cutoff)
            if (stale.isNotEmpty()) {
                messageDao.deleteByIds(stale.map { it.id })
            }
            return Result.success()
        }

        companion object {
            const val WORK_NAME = "otp_auto_delete"

            /**
             * Timestamp before which OTP messages should be deleted, or null
             * when the policy is [OtpAutoDeletePolicy.NEVER].
             */
            fun cutoffFor(
                policy: OtpAutoDeletePolicy,
                nowMs: Long,
            ): Long? =
                when (policy) {
                    OtpAutoDeletePolicy.NEVER -> null
                    OtpAutoDeletePolicy.HOURS_24 -> nowMs - TimeUnit.HOURS.toMillis(24)
                    OtpAutoDeletePolicy.DAYS_3 -> nowMs - TimeUnit.DAYS.toMillis(3)
                    OtpAutoDeletePolicy.DAYS_7 -> nowMs - TimeUnit.DAYS.toMillis(7)
                    OtpAutoDeletePolicy.MONTH_1 -> nowMs - TimeUnit.DAYS.toMillis(30)
                    OtpAutoDeletePolicy.MONTHS_3 -> nowMs - TimeUnit.DAYS.toMillis(90)
                }
        }
    }
