package app.clearsms.shortcuts

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin seam over the [ShortcutManagerCompat] calls
 * [ConversationShortcutPublisher] makes, so unit tests can fake the
 * system's shortcut store instead of shadowing it. Robolectric's
 * `ShadowShortcutManager` keeps no CACHED state at all - its
 * `removeAllDynamicShortcuts` deletes everything and its
 * `removeLongLivedShortcuts` is a no-op - which is exactly how a cached
 * long-lived shortcut surviving the setting being turned off went
 * unnoticed; and a custom `@Config(shadows = ...)` is banned here (see
 * RobolectricSandboxConventionTest). The test double models the real
 * `ShortcutService` semantics instead.
 *
 * Implementations must be pure delegation: no behaviour of their own.
 */
interface ShortcutSystem {
    /** [ShortcutManagerCompat.getMaxShortcutCountPerActivity]. */
    fun maxShortcutCountPerActivity(): Int

    /** [ShortcutManagerCompat.setDynamicShortcuts]: false when rate-limited. */
    fun setDynamicShortcuts(shortcuts: List<ShortcutInfoCompat>): Boolean

    /**
     * [ShortcutManagerCompat.removeAllDynamicShortcuts]. Leaves a dynamic
     * shortcut that is ALSO cached or pinned in the system (dynamic flag
     * cleared) - see [removeLongLivedShortcuts].
     */
    fun removeAllDynamicShortcuts()

    /**
     * [ShortcutManagerCompat.removeLongLivedShortcuts]: deletes the
     * dynamic AND cached copies of each id (a copy the user pinned stays,
     * pinned-only). Below API 30 the compat layer removes the dynamic
     * copies, which is all that exists there.
     */
    fun removeLongLivedShortcuts(shortcutIds: List<String>)

    /** [ShortcutManagerCompat.getShortcuts] for the `FLAG_MATCH_*` bits in [matchFlags]. */
    fun getShortcuts(matchFlags: Int): List<ShortcutInfoCompat>

    /** [ShortcutManagerCompat.disableShortcuts] with [disabledMessage] shown on tap. */
    fun disableShortcuts(
        shortcutIds: List<String>,
        disabledMessage: CharSequence,
    )
}

/** Production [ShortcutSystem]: delegates straight to [ShortcutManagerCompat]. */
@Singleton
class AndroidShortcutSystem
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ShortcutSystem {
        override fun maxShortcutCountPerActivity(): Int = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)

        override fun setDynamicShortcuts(shortcuts: List<ShortcutInfoCompat>): Boolean =
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)

        override fun removeAllDynamicShortcuts() = ShortcutManagerCompat.removeAllDynamicShortcuts(context)

        override fun removeLongLivedShortcuts(shortcutIds: List<String>) =
            ShortcutManagerCompat.removeLongLivedShortcuts(context, shortcutIds)

        override fun getShortcuts(matchFlags: Int): List<ShortcutInfoCompat> = ShortcutManagerCompat.getShortcuts(context, matchFlags)

        override fun disableShortcuts(
            shortcutIds: List<String>,
            disabledMessage: CharSequence,
        ) = ShortcutManagerCompat.disableShortcuts(context, shortcutIds, disabledMessage)
    }
