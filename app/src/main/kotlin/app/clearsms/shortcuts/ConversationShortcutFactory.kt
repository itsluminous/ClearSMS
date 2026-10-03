package app.clearsms.shortcuts

import android.content.Context
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import app.clearsms.ConversationDeepLink
import app.clearsms.data.db.ShortcutCandidateRow
import app.clearsms.notification.NotificationSender
import app.clearsms.notification.SenderIconFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns one selected conversation into the [ShortcutInfoCompat] the system
 * receives: id `thread:<appThreadId>`, the inbox's display name as both
 * labels, the shared avatar chain's icon ([SenderIconFactory]) and the SAME
 * explicit conversation deep link a notification tap fires
 * ([ConversationDeepLink]). Separate from the publisher so the shape can be
 * asserted without the database or the system service.
 *
 * One object serves three system surfaces, which is why it carries more
 * than a launcher entry needs:
 * - the **launcher** (long-press menu, pinning) uses the labels, icon, rank
 *   and intent;
 * - **Direct Share** (the share sheet's direct-share row) matches the
 *   shortcut's category against the `<share-target>` in
 *   `res/xml/shortcuts.xml`, so [ConversationShortcutSelection.SHARE_TARGET_CATEGORY]
 *   is attached - but only when the sender is one the composer can address
 *   ([ConversationShortcutSelection.acceptsShares]); a shortcut for an
 *   alphanumeric sender id stays launcher-only;
 * - **Android 11 conversation notifications** require a long-lived shortcut
 *   with a [Person] attached: `setLongLived(true)` and a Person whose key is
 *   the normalized sender - the SAME key [app.clearsms.notification.MessageNotifier]
 *   gives its MessagingStyle sender, so the platform sees one identity when
 *   a notification names this shortcut. The [LocusIdCompat] is the shortcut
 *   id too, as the platform recommends.
 *
 * Still deliberately absent: a body, an OTP, an amount or an account
 * number - names and thread identity only, in every surface.
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
            val shortcutId = ConversationShortcutSelection.shortcutId(row.threadId)
            val person =
                Person
                    .Builder()
                    .setName(label)
                    .setKey(row.normalizedSender)
                    .setIcon(iconFactory.iconFor(sender))
                    .build()
            val builder =
                ShortcutInfoCompat
                    .Builder(context, shortcutId)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .setIcon(iconFactory.shortcutIconFor(sender))
                    .setIntent(ConversationDeepLink.intent(context, row.threadId))
                    .setRank(rank)
                    .setLongLived(true)
                    .setPerson(person)
                    .setLocusId(LocusIdCompat(shortcutId))
            if (ConversationShortcutSelection.acceptsShares(row.sender)) {
                builder.setCategories(setOf(ConversationShortcutSelection.SHARE_TARGET_CATEGORY))
            }
            return builder.build()
        }
    }
