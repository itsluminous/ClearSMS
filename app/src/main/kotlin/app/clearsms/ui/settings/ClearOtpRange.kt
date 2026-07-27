package app.clearsms.ui.settings

import java.util.concurrent.TimeUnit

/**
 * Age choices for the one-shot "Clear older OTPs" action in Settings.
 *
 * This is deliberately NOT a persisted preference: picking a range runs one
 * cleanup now and nothing is saved. Recurring cleanup remains the separate
 * `otpAutoDeletePolicy` setting.
 */
enum class ClearOtpRange {
    ALL,
    OLDER_THAN_1_DAY,
    OLDER_THAN_3_DAYS,
    OLDER_THAN_1_WEEK,
    OLDER_THAN_2_WEEKS,
    OLDER_THAN_1_MONTH,
    ;

    /**
     * Timestamp strictly before which OTP messages are deleted. [ALL] has no
     * lower bound, expressed as [Long.MAX_VALUE] so the DAO's strict
     * `timestamp < cutoff` comparison matches every message.
     */
    fun cutoffMs(nowMs: Long): Long =
        when (this) {
            ALL -> Long.MAX_VALUE
            OLDER_THAN_1_DAY -> nowMs - TimeUnit.DAYS.toMillis(1)
            OLDER_THAN_3_DAYS -> nowMs - TimeUnit.DAYS.toMillis(3)
            OLDER_THAN_1_WEEK -> nowMs - TimeUnit.DAYS.toMillis(7)
            OLDER_THAN_2_WEEKS -> nowMs - TimeUnit.DAYS.toMillis(14)
            // "1 month" is a cleanup horizon, not a calendar contract: a
            // fixed 30 days keeps the cutoff deterministic and testable.
            OLDER_THAN_1_MONTH -> nowMs - TimeUnit.DAYS.toMillis(30)
        }
}
