package app.clearsms.shortcuts

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.core.content.pm.ShortcutManagerCompat
import app.clearsms.R
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.ShortcutCandidateRow
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.di.ApplicationScope
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.count
import app.clearsms.diagnostics.DiagField.Companion.flag
import app.clearsms.notification.NotificationSender
import app.clearsms.notification.NotificationSenderResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the app's dynamic shortcuts equal to the conversations
 * [ConversationShortcutSelection] picks (issue #81): pinned threads first,
 * then the most recent, inside the system's own budget, never a blocked,
 * muted, binned or Spam thread. One shortcut per conversation, keyed
 * `thread:<appThreadId>`, opening that conversation through the SAME
 * explicit deep link a notification tap uses ([app.clearsms.ConversationDeepLink]).
 * The same list feeds every surface the system builds from shortcuts - the
 * launcher's long-press menu, the share sheet's direct-share row (the
 * `<share-target>` in `res/xml/shortcuts.xml` matches the category
 * [ConversationShortcutFactory] attaches) and the conversation identity an
 * Android 11 message notification names by id ([ConversationShortcutRegistry],
 * implemented here) - so the exclusion set above is applied once, here, for
 * all of them, and the single setting switches all of them together.
 *
 * **What drives a refresh.** One collector, alive for the process
 * ([start] from the Application), observes the Room flow of candidates
 * ([MessageDao.shortcutCandidates]) together with the settings that shape
 * the exclusion set (blocklist, muted senders) and the on/off switch. Room
 * invalidates the flow on every insert, bin, restore, delete, archive, pin,
 * block or re-categorisation - so a thread blocked, binned or re-sorted to
 * Spam AFTER its shortcut was published drops out of the next emission and
 * its shortcut is removed, and a message arriving while the app is in the
 * background (the receiver starts the process, so this collector runs)
 * reorders the list the same way. No code path has to remember to call
 * anything: the database is the trigger. Shortcuts the USER pinned to the
 * home screen outlive the dynamic list, so each pass also re-checks every
 * pinned conversation shortcut against the exclusion set and DISABLES the
 * ones that fail it (with an honest message) - the stale-pinned-shortcut
 * bug this feature is otherwise famous for.
 *
 * **Rate limiting.** `setDynamicShortcuts` is rate-limited while the app is
 * in the background. Emissions are debounced ([DEBOUNCE_MS]) and the list
 * is published only when it differs from the last successful publish -
 * same ids, ranks, labels and photo identity - so a burst from one chatty
 * sender costs nothing (its thread stays where it was) and a received
 * message only reaches the system when it actually reorders the launcher
 * menu. When the system still refuses (returns false), the result is
 * remembered and retried the next time an activity resumes: the platform
 * resets the limit when the app comes to the foreground.
 *
 * **Ingest is never touched.** Nothing here runs on the receive path; the
 * collector reads the same Room flow the inbox does and the hot settings
 * caches in DataModule are irrelevant to it.
 *
 * **API 23-24.** `ShortcutManager` is API 25+. Every `ShortcutManagerCompat`
 * call used here is safe below 25 (verified against androidx.core 1.15.0:
 * the compat layer returns a fixed budget of 5, treats set / remove /
 * disable as no-ops and reports no shortcuts), but there is nothing to
 * publish to, so [start] returns at once and no bitmap is ever rendered.
 */
@Singleton
class ConversationShortcutPublisher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: SettingsRepository,
        private val messageDao: MessageDao,
        private val senderResolver: NotificationSenderResolver,
        private val factory: ConversationShortcutFactory,
        @ApplicationScope private val scope: CoroutineScope,
    ) : ConversationShortcutRegistry {
        private val started = AtomicBoolean(false)

        /** Bumped on foreground when a publish was refused; part of the pipeline's inputs. */
        private val retryTick = MutableStateFlow(0)

        @Volatile
        private var retryPending = false

        /** Fingerprint of the last list the system accepted; null until the first publish. */
        @Volatile
        private var published: List<String>? = null

        /**
         * Thread ids of the last list the system ACCEPTED - the shortcuts
         * that exist right now. Empty until the first accepted publish, after
         * a refused (rate-limited) publish that followed a change, when the
         * setting is off, and forever below API 25 where [start] never runs.
         * Read by [isPublished] on the notification path: a plain volatile
         * read, so that path never suspends or touches a system service.
         */
        @Volatile
        private var publishedThreadIds: Set<Long> = emptySet()

        override fun isPublished(threadId: Long): Boolean = threadId in publishedThreadIds

        /** The coalescing window; tests set 0 to drive the pipeline synchronously. */
        internal var debounceMs: Long = DEBOUNCE_MS

        /** Starts the process-lifetime collector; idempotent, a no-op below API 25. */
        fun start() {
            if (Build.VERSION.SDK_INT < SHORTCUT_MANAGER_MIN_SDK) return
            if (!started.compareAndSet(false, true)) return
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(ForegroundRetry())
            scope.launch { observe() }
        }

        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        internal suspend fun observe() {
            combine(
                settings.conversationShortcuts,
                settings.blockedSenders,
                settings.mutedSenders,
                retryTick,
            ) { enabled, blocked, muted, _ -> Inputs(enabled, blocked, muted) }
                .flatMapLatest { inputs ->
                    if (!inputs.enabled) {
                        flowOf(Snapshot(inputs, emptyList()))
                    } else {
                        val limit =
                            ConversationShortcutSelection.queryLimit(
                                budget = budget(),
                                blockedCount = inputs.blocked.size,
                                mutedCount = inputs.muted.size,
                            )
                        messageDao.shortcutCandidates(limit).map { Snapshot(inputs, it) }
                    }
                }.debounce(debounceMs)
                .collect { snapshot ->
                    try {
                        publish(snapshot)
                    } catch (e: Exception) {
                        // IllegalStateException while the user is locked,
                        // IllegalArgumentException from a budget the system
                        // changed under us, a dead contacts provider: the
                        // launcher keeps its last list; the next change
                        // retries. Never a crash of the default SMS app.
                        Diag.w(TAG, "shortcut publish failed", e)
                    }
                }
        }

        private fun budget(): Int =
            ConversationShortcutSelection.budget(
                maxPerActivity = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context),
                staticCount = STATIC_SHORTCUT_COUNT,
            )

        private suspend fun publish(snapshot: Snapshot) {
            val inputs = snapshot.inputs
            if (!inputs.enabled) {
                // The switch is off: no conversation shortcut anywhere, the
                // static "New message" stays (it is the manifest's, not ours).
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                disablePinned(context.getString(R.string.shortcut_disabled_setting_off)) { true }
                published = emptyList()
                publishedThreadIds = emptySet()
                retryPending = false
                return
            }
            val selected =
                ConversationShortcutSelection.select(
                    candidates = snapshot.candidates,
                    budget = budget(),
                    blockedSenders = inputs.blocked,
                    mutedSenders = inputs.muted,
                )
            val resolved = selected.map { row -> row to senderResolver.resolve(row.sender) }
            val fingerprint = resolved.map { (row, sender) -> fingerprint(row, sender) }
            if (fingerprint != published || retryPending) {
                val shortcuts = resolved.mapIndexed { rank, (row, sender) -> factory.build(row, sender, rank) }
                val accepted = ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
                Diag.d(TAG, "conversation shortcuts published", count("count", shortcuts.size), flag("accepted", accepted))
                if (accepted) {
                    published = fingerprint
                    publishedThreadIds = selected.mapTo(HashSet()) { it.threadId }
                    retryPending = false
                } else {
                    // Background rate limit: remembered, retried on foreground.
                    // The system kept its PREVIOUS list, so the registry keeps
                    // reporting that list - a notification for a thread that
                    // only exists in the refused list must not name a
                    // shortcut the system never received.
                    retryPending = true
                }
            }
            // Pinned shortcuts outlive the dynamic list; a thread that became
            // excluded after the user pinned it must not stay launchable.
            disablePinned(context.getString(R.string.shortcut_disabled_unavailable)) { threadId ->
                ConversationShortcutSelection.isExcluded(
                    row = messageDao.shortcutCandidateForThread(threadId),
                    blockedSenders = inputs.blocked,
                    mutedSenders = inputs.muted,
                )
            }
        }

        /**
         * Disables (and removes) every conversation shortcut the user pinned
         * whose thread [shouldDisable] - the launcher greys it out and shows
         * [message] on tap. Other apps' or other kinds of pinned shortcuts
         * (the static one, pinned) are never touched: only ids carrying the
         * conversation prefix are considered. A pinned shortcut that is
         * ALREADY disabled stays in the system's pinned list for as long as
         * the user keeps it on the home screen, so it is skipped: re-disabling
         * it every pass is a pointless system call and made the log claim
         * work that was not happening.
         */
        private suspend fun disablePinned(
            message: String,
            shouldDisable: suspend (threadId: Long) -> Boolean,
        ) {
            val pinnedConversations =
                ShortcutManagerCompat
                    .getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                    .filter { it.isEnabled }
                    .mapNotNull { info -> ConversationShortcutSelection.threadIdOf(info.id)?.let { info.id to it } }
            val stale = pinnedConversations.filter { (_, threadId) -> shouldDisable(threadId) }.map { it.first }
            if (stale.isEmpty()) return
            ShortcutManagerCompat.disableShortcuts(context, stale, message)
            Diag.i(TAG, "stale pinned conversation shortcuts disabled", count("count", stale.size))
        }

        /**
         * Everything the launcher would see change: identity, rank order
         * (list position), label, pin state and the photo the icon was
         * rendered from. A new message in the top thread changes none of
         * these, so it costs no system call.
         */
        private fun fingerprint(
            row: ShortcutCandidateRow,
            sender: NotificationSender,
        ): String = "${row.threadId}|${row.pinned}|${sender.name}|${sender.photoUri.orEmpty()}|${sender.brandKey.orEmpty()}"

        private data class Inputs(
            val enabled: Boolean,
            val blocked: Set<String>,
            val muted: Set<String>,
        )

        private data class Snapshot(
            val inputs: Inputs,
            val candidates: List<ShortcutCandidateRow>,
        )

        /** Foreground resets the system's background rate limit: retry a refused publish then. */
        private inner class ForegroundRetry : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (retryPending) retryTick.value = retryTick.value + 1
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        companion object {
            private const val TAG = "ConversationShortcuts"

            /** `ShortcutManager` exists from API 25 (Android 7.1). */
            internal const val SHORTCUT_MANAGER_MIN_SDK = Build.VERSION_CODES.N_MR1

            /**
             * Static shortcuts declared in `res/xml/shortcuts.xml` - they
             * share the per-activity budget with the dynamic ones.
             * `AppShortcutsContractTest` pins this against the xml.
             */
            internal const val STATIC_SHORTCUT_COUNT = 1

            /** Coalesces the burst of invalidations one receive or bulk action produces. */
            internal const val DEBOUNCE_MS = 1_500L
        }
    }
