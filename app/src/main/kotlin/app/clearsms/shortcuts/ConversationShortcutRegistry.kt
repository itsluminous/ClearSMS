package app.clearsms.shortcuts

/**
 * What a notifier may ask about the conversation shortcuts: is the shortcut
 * for this thread PUBLISHED right now - accepted by `setDynamicShortcuts`
 * and not since removed?
 *
 * [app.clearsms.notification.MessageNotifier] consults this before naming a
 * shortcut on an Android 11 conversation notification (`setShortcutId`). A
 * shortcut can legitimately be absent - the setting is off, the thread fell
 * outside the system's budget, the thread is excluded, a background publish
 * was rate-limited, the device is below API 25 - and a notification must
 * never be degraded, delayed or dropped for it. Two layers make that so:
 *
 * 1. The platform tolerates a dangling id. `NotificationManagerService`
 *    resolves `getShortcutId()` through `ShortcutHelper.getValidShortcutInfo`,
 *    and when that returns null it logs `"notification ... added an invalid
 *    shortcut"`, stores a null shortcut on the record and CONTINUES to
 *    enqueue and post it (android11-release and main). The record is then
 *    simply not a conversation (`NotificationRecord.isConversation` requires
 *    the shortcut for apps targeting R+), which is exactly how this app's
 *    MessagingStyle notifications were treated before any shortcut existed.
 *    Nothing is dropped; nothing is delayed.
 * 2. This registry keeps the app from relying on (1) in the common case:
 *    the id is attached only when the publisher knows the shortcut was
 *    accepted, so the platform's warning is reserved for a true race (a
 *    shortcut removed between the check and the post - still harmless by (1)).
 *
 * The answer is an in-memory read of a set the publisher maintains - never
 * a settings read, never a system-service call - so the notification path
 * stays non-suspending and never waits on anything.
 */
fun interface ConversationShortcutRegistry {
    fun isPublished(threadId: Long): Boolean
}
