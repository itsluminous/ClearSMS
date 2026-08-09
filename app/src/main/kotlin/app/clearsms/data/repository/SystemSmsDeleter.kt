package app.clearsms.data.repository

/**
 * Deletes rows from the system SMS provider by their provider `_id`s.
 *
 * Implemented by the platform layer (`TelephonyWriter`): as the default SMS
 * app, Clear SMS owns the platform message store, so deleting a message here
 * must also delete it there - otherwise it would reappear on the next import
 * or in any other SMS client. Implementations are expected to no-op safely
 * when the app is not the default SMS app.
 */
fun interface SystemSmsDeleter {
    /** @return the number of provider rows actually deleted. */
    fun deleteBySystemIds(systemIds: List<Long>): Int
}
