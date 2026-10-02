package app.clearsms.shortcuts

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import app.clearsms.ConversationDeepLink
import app.clearsms.data.db.ShortcutCandidateRow
import app.clearsms.notification.NotificationSender
import app.clearsms.notification.SenderIconFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns one selected conversation into the [ShortcutInfoCompat] the
 * launcher receives: id `thread:<appThreadId>`, the inbox's display name as
 * both labels, the shared avatar chain's icon ([SenderIconFactory]) and the
 * SAME explicit conversation deep link a notification tap fires
 * ([ConversationDeepLink]). Nothing else is attached - no body, no
 * extracted value, no categories, no `Person` - names and thread identity
 * only. Separate from the publisher so the shape can be asserted without
 * the database or the system service.
 *
 * What this sets up: a Direct Share target would add `setCategories` plus a
 * `<share-target>` in `shortcuts.xml`, and an Android 11 conversation
 * notification would add `setLongLived(true)` / `setPerson` here and
 * `setShortcutId` on the notification - both reuse this exact object and id
 * scheme, and are deliberately follow-ups.
 */
@Singleton
class ConversationShortcutFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val iconFactory: SenderIconFactory,
    ) {
        fun build(
            row: ShortcutCandidateRow,
            sender: NotificationSender,
            rank: Int,
        ): ShortcutInfoCompat {
            val label = ConversationShortcutSelection.label(sender.name, row.sender)
            return ShortcutInfoCompat
                .Builder(context, ConversationShortcutSelection.shortcutId(row.threadId))
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(iconFactory.shortcutIconFor(sender))
                .setIntent(ConversationDeepLink.intent(context, row.threadId))
                .setRank(rank)
                .build()
        }
    }
