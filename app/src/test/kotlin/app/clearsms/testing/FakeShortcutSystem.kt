package app.clearsms.testing

import android.content.Context
import android.content.pm.ShortcutInfo
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import app.clearsms.shortcuts.ShortcutSystem

/**
 * [ShortcutSystem] double that models the REAL `ShortcutService` /
 * `ShortcutPackage` state machine for the one thing Robolectric's
 * `ShadowShortcutManager` leaves out: the system's CACHE of long-lived
 * shortcuts. On a device the system caches a long-lived shortcut the
 * moment a conversation notification names it, and a cached copy outlives
 * `removeAllDynamicShortcuts` - which is how a copy with the label, Person
 * and avatar survived the setting being turned off. The shadow deletes
 * everything on `removeAllDynamicShortcuts` and ignores
 * `removeLongLivedShortcuts`, so no test against it can see that.
 *
 * Semantics mirrored, per AOSP `ShortcutPackage`:
 * - `setDynamicShortcuts` / `removeAllDynamicShortcuts`
 *   (`deleteAllDynamicShortcuts`): a dynamic shortcut that is also cached
 *   or pinned KEEPS EXISTING with its dynamic flag cleared; the rest are
 *   deleted.
 * - `removeLongLivedShortcuts` (`deleteLongLivedWithId`): clears the
 *   cached flags, then deletes the shortcut unless it is pinned, in which
 *   case it stays pinned-only.
 * - `disableShortcuts` (`disableWithId`): a pinned or cached shortcut stays,
 *   disabled, with its dynamic flag cleared; any other is deleted.
 * - `cacheShortcuts` (what `NotificationManagerService` does through
 *   `ShortcutHelper`): only a long-lived shortcut can be cached.
 *
 * Every shortcut handed back carries the flags the system would report
 * (`isDynamic`, `isCached`, `isPinned`, `isEnabled`), so the code under
 * test reads exactly what it would read from the platform.
 */
class FakeShortcutSystem(
    private val context: Context,
    var maxPerActivity: Int = 5,
) : ShortcutSystem {
    private class Held(
        var info: ShortcutInfoCompat,
        var dynamic: Boolean = false,
        var cached: Boolean = false,
        var pinned: Boolean = false,
        var enabled: Boolean = true,
    )

    private val held = LinkedHashMap<String, Held>()

    /** When true, `setDynamicShortcuts` is refused the way the background rate limit refuses it. */
    var rateLimited = false

    /** Every id list passed to [removeLongLivedShortcuts], in order. */
    val removeLongLivedCalls = mutableListOf<List<String>>()

    override fun maxShortcutCountPerActivity(): Int = maxPerActivity

    override fun setDynamicShortcuts(shortcuts: List<ShortcutInfoCompat>): Boolean {
        if (rateLimited) return false
        deleteAllDynamic()
        shortcuts.forEach { s -> held.getOrPut(s.id) { Held(s) }.apply { info = s; dynamic = true } }
        return true
    }

    override fun removeAllDynamicShortcuts() = deleteAllDynamic()

    private fun deleteAllDynamic() {
        val it = held.values.iterator()
        while (it.hasNext()) {
            val h = it.next()
            if (!h.dynamic) continue
            if (h.cached || h.pinned) h.dynamic = false else it.remove()
        }
    }

    override fun removeLongLivedShortcuts(shortcutIds: List<String>) {
        removeLongLivedCalls += shortcutIds.toList()
        shortcutIds.forEach { id ->
            val h = held[id] ?: return@forEach
            h.cached = false
            if (h.pinned) h.dynamic = false else held.remove(id)
        }
    }

    override fun getShortcuts(matchFlags: Int): List<ShortcutInfoCompat> =
        held.values
            .filter { h ->
                (matchFlags and ShortcutManagerCompat.FLAG_MATCH_DYNAMIC != 0 && h.dynamic) ||
                    (matchFlags and ShortcutManagerCompat.FLAG_MATCH_CACHED != 0 && h.cached) ||
                    (matchFlags and ShortcutManagerCompat.FLAG_MATCH_PINNED != 0 && h.pinned)
            }.map(::snapshot)

    override fun disableShortcuts(
        shortcutIds: List<String>,
        disabledMessage: CharSequence,
    ) {
        shortcutIds.forEach { id ->
            val h = held[id] ?: return@forEach
            if (h.pinned || h.cached) {
                h.dynamic = false
                h.enabled = false
            } else {
                held.remove(id)
            }
        }
    }

    // --- What the launcher and the notification service do -----------------

    /**
     * The system caching [id] because a conversation notification named it
     * (`ShortcutHelper.cacheShortcut` -> `cacheShortcuts`). The platform
     * refuses anything but a long-lived shortcut; so does this.
     */
    fun cacheForConversationNotification(id: String) {
        val h = requireNotNull(held[id]) { "no shortcut $id" }
        check(isLongLived(h.info.toShortcutInfo())) { "Only long lived shortcuts can get cached. Ignoring id $id" }
        h.cached = true
    }

    /** The launcher pinning [id] to the home screen. */
    fun pin(id: String) {
        val h = requireNotNull(held[id]) { "no shortcut $id" }
        h.pinned = true
    }

    // --- Inspection ---------------------------------------------------------

    /** Ids the system holds in ANY state (dynamic, cached or pinned, enabled or not). */
    fun heldIds(): Set<String> = held.keys.toSet()

    fun isDynamic(id: String): Boolean = held[id]?.dynamic == true

    fun isCached(id: String): Boolean = held[id]?.cached == true

    fun isPinned(id: String): Boolean = held[id]?.pinned == true

    fun isEnabled(id: String): Boolean = held[id]?.enabled == true

    /** The shortcut as the system would hand it back, flags included. */
    fun shortcut(id: String): ShortcutInfoCompat = snapshot(requireNotNull(held[id]) { "no shortcut $id" })

    private fun snapshot(h: Held): ShortcutInfoCompat {
        val real = h.info.toShortcutInfo()
        var flags = 0
        if (h.dynamic) flags = flags or flag("FLAG_DYNAMIC")
        if (h.pinned) flags = flags or flag("FLAG_PINNED")
        if (h.cached) flags = flags or flag("FLAG_CACHED_NOTIFICATIONS")
        if (!h.enabled) flags = flags or flag("FLAG_DISABLED")
        ShortcutInfo::class.java
            .getDeclaredMethod("addFlags", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(real, flags)
        return ShortcutInfoCompat.Builder(context, real).build()
    }

    private companion object {
        /** A hidden `ShortcutInfo.FLAG_*` constant, read off the framework class Robolectric runs. */
        fun flag(name: String): Int = ShortcutInfo::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)

        fun isLongLived(info: ShortcutInfo): Boolean = info.javaClass.getMethod("isLongLived").invoke(info) as Boolean
    }
}
