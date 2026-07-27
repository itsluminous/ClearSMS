package app.clearsms.notification

import android.app.PendingIntent

/**
 * Single source of truth for the [PendingIntent] flags used by notification
 * actions.
 *
 * Every action intent is explicit (targets a class in this package) and
 * immutable. The ONE documented exception is the direct-reply action: a
 * `RemoteInput` result can only be attached by the system if the intent is
 * mutable, so the reply action — and only the reply action — uses
 * [PendingIntent.FLAG_MUTABLE].
 */
object NotificationIntents {
    /** Flags for a notification action; pass `mutable = true` ONLY for the RemoteInput reply. */
    fun flags(mutable: Boolean = false): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
}
