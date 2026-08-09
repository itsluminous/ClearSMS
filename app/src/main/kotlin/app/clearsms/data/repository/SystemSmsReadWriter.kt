package app.clearsms.data.repository

/**
 * Propagates message read-state to the system SMS provider by provider `_id`.
 *
 * Implemented by the platform layer (`TelephonyWriter`). As the default SMS
 * app, Clear SMS owns the platform message store, and the importer seeds each
 * message's read-state from it - so marking a message read locally must also
 * update the provider, otherwise the read-state is lost on the next import (or
 * reinstall) and never syncs to other SMS clients. Implementations no-op
 * safely when the app is not the default SMS app.
 */
fun interface SystemSmsReadWriter {
    fun setReadBySystemIds(
        systemIds: List<Long>,
        read: Boolean,
    )
}
