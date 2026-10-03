package app.clearsms.shortcuts

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.ThreadPinEntity
import app.clearsms.diagnostics.Diag
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.notification.NotificationSender
import app.clearsms.notification.NotificationSenderResolver
import app.clearsms.notification.SenderIconFactory
import app.clearsms.sms.ContactsSource
import app.clearsms.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor

/**
 * The publisher end to end against a real in-memory Room database and
 * Robolectric's ShortcutManager: what the launcher is handed, and - the
 * classic bug - that a shortcut is REMOVED when its thread is blocked,
 * binned or re-sorted to Spam after publishing, that a muted sender never
 * appears, that a pinned-to-home-screen shortcut to a now-excluded thread is
 * disabled, and that the setting's OFF state leaves nothing behind. Room is
 * run on direct executors and the publisher on an unconfined scope with a
 * zero debounce, so every database write drives the pipeline synchronously
 * and the assertions need no sleeping. All fixtures are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationShortcutPublisherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var settings: FakeSettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var publisher: ConversationShortcutPublisher

    private val shortcutManager: ShortcutManager
        get() = context.getSystemService(ShortcutManager::class.java)

    /** Names "contact-<n>" for the n-th thread's sender, so labels are inspectable. */
    private val resolver =
        object : NotificationSenderResolver(context, ContactsSource(context), SenderIdLookup { null }) {
            override fun resolve(sender: String) = NotificationSender(name = sender.replace("sender-", "contact-"), monogram = "C")
        }

    @Before
    fun setUp() {
        val direct = Executor { it.run() }
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(direct)
                .setTransactionExecutor(direct)
                .build()
        dao = db.messageDao()
        settings = FakeSettingsRepository()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        shadowOf(shortcutManager).setMaxShortcutCountPerActivity(5)
        publisher =
            ConversationShortcutPublisher(
                context = context,
                settings = settings,
                messageDao = dao,
                senderResolver = resolver,
                factory = ConversationShortcutFactory(context, SenderIconFactory(context)),
                scope = scope,
            ).apply { debounceMs = 0L }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun message(
        id: Long,
        threadId: Long,
        timestamp: Long,
        category: Category = Category.PERSONAL,
    ) = MessageEntity(
        id = id,
        threadId = threadId,
        sender = "sender-$threadId",
        normalizedSender = "sender-$threadId",
        body = "body $id",
        timestamp = timestamp,
        category = category,
    )

    private fun insert(vararg messages: MessageEntity) = runBlocking { dao.insertAll(messages.toList()) }

    private fun dynamicIds(): List<String> = shortcutManager.dynamicShortcuts.sortedBy { it.rank }.map { it.id }

    private fun seedThreads(count: Int) = insert(*(1L..count).map { message(id = it, threadId = it, timestamp = it * 100) }.toTypedArray())

    @Test
    fun `publishes pinned-then-recent conversations within the system budget minus the static shortcut`() {
        seedThreads(6)
        runBlocking { db.threadPinDao().upsertAll(listOf(ThreadPinEntity("sender-2", pinnedAt = 1L))) }
        publisher.start()

        // Budget 5 - 1 static = 4: the pinned thread first, then the newest three.
        assertThat(dynamicIds()).containsExactly("thread:2", "thread:6", "thread:5", "thread:4").inOrder()
        val top = shortcutManager.dynamicShortcuts.single { it.id == "thread:2" }
        assertThat(top.shortLabel).isEqualTo("contact-2")
        assertThat(top.intent?.dataString).isEqualTo("clearsms://conversation/2")
        assertThat(top.rank).isEqualTo(0)
    }

    @Test
    fun `a new message in another thread reorders the launcher`() {
        seedThreads(2)
        publisher.start()
        assertThat(dynamicIds()).containsExactly("thread:2", "thread:1").inOrder()

        insert(message(id = 10, threadId = 1, timestamp = 10_000))
        assertThat(dynamicIds()).containsExactly("thread:1", "thread:2").inOrder()
    }

    @Test
    fun `blocking a sender after publishing removes its shortcut`() {
        seedThreads(3)
        publisher.start()
        assertThat(dynamicIds()).contains("thread:3")

        // Both halves of a block, as SenderBlocker performs them.
        runBlocking {
            settings.setBlockedSenders(setOf("sender-3"))
            dao.setBlockedSender("sender-3", blocked = true)
        }
        assertThat(dynamicIds()).containsExactly("thread:2", "thread:1").inOrder()
    }

    @Test
    fun `the settings blocklist alone is enough - the per-row flag is only a cache`() {
        seedThreads(2)
        publisher.start()
        runBlocking { settings.setBlockedSenders(setOf("sender-2")) }
        assertThat(dynamicIds()).containsExactly("thread:1")
    }

    @Test
    fun `binning every message of a thread after publishing removes its shortcut`() {
        seedThreads(3)
        publisher.start()
        assertThat(dynamicIds()).hasSize(3)

        runBlocking { dao.stageDelete(listOf(3L), deletedAt = 1L) }
        assertThat(dynamicIds()).containsExactly("thread:2", "thread:1").inOrder()

        // Restoring from the bin brings it back.
        runBlocking { dao.undoDelete(listOf(3L)) }
        assertThat(dynamicIds()).containsExactly("thread:3", "thread:2", "thread:1").inOrder()
    }

    @Test
    fun `a thread whose newest message is Spam is never published, and drops out once re-sorted`() {
        insert(message(1, threadId = 1, timestamp = 100), message(2, threadId = 2, timestamp = 200, category = Category.SPAM))
        publisher.start()
        assertThat(dynamicIds()).containsExactly("thread:1")

        // A Spam message arriving in thread 1 makes its newest message Spam.
        insert(message(3, threadId = 1, timestamp = 300, category = Category.SPAM))
        assertThat(dynamicIds()).isEmpty()
    }

    @Test
    fun `a muted sender is never published and muting after publishing removes it`() {
        seedThreads(2)
        runBlocking { settings.setMutedSenders(setOf("sender-1")) }
        publisher.start()
        assertThat(dynamicIds()).containsExactly("thread:2")

        runBlocking { settings.setMutedSenders(setOf("sender-1", "sender-2")) }
        assertThat(dynamicIds()).isEmpty()
    }

    @Test
    fun `turning the setting off removes every conversation shortcut and on restores them`() {
        seedThreads(2)
        publisher.start()
        assertThat(dynamicIds()).hasSize(2)

        runBlocking { settings.setConversationShortcuts(false) }
        assertThat(dynamicIds()).isEmpty()

        runBlocking { settings.setConversationShortcuts(true) }
        assertThat(dynamicIds()).containsExactly("thread:2", "thread:1").inOrder()
    }

    @Test
    fun `a home-screen-pinned shortcut to a thread that became excluded is disabled`() {
        seedThreads(2)
        publisher.start()
        // The user pins thread 2's shortcut to the home screen.
        val pinned = shortcutManager.dynamicShortcuts.single { it.id == "thread:2" }
        shortcutManager.requestPinShortcut(pinned, null)
        assertThat(shortcutManager.pinnedShortcuts.map(ShortcutInfo::getId)).contains("thread:2")

        // Then blocks the sender: the pinned copy must not stay launchable.
        runBlocking {
            settings.setBlockedSenders(setOf("sender-2"))
            dao.setBlockedSender("sender-2", blocked = true)
        }
        assertThat(dynamicIds()).containsExactly("thread:1")
        assertWithMessage("the pinned copy of the blocked thread must be disabled")
            .that(disabledPinnedIds())
            .containsExactly("thread:2")
        // A pinned shortcut to a thread that is still fine is left alone.
        val fine = shortcutManager.dynamicShortcuts.single { it.id == "thread:1" }
        shortcutManager.requestPinShortcut(fine, null)
        insert(message(id = 50, threadId = 1, timestamp = 5_000))
        assertThat(disabledPinnedIds()).containsExactly("thread:2")
        assertThat(shortcutManager.pinnedShortcuts.map(ShortcutInfo::getId)).contains("thread:1")
    }

    @Test
    fun `turning the setting off also disables home-screen-pinned conversation shortcuts`() {
        seedThreads(1)
        publisher.start()
        shortcutManager.requestPinShortcut(shortcutManager.dynamicShortcuts.single(), null)

        runBlocking { settings.setConversationShortcuts(false) }
        assertThat(dynamicIds()).isEmpty()
        assertThat(disabledPinnedIds()).containsExactly("thread:1")
    }

    @Test
    fun `a pinned shortcut that is already disabled is not disabled again on the next pass`() {
        val logStart = System.currentTimeMillis()
        seedThreads(2)
        publisher.start()
        shortcutManager.requestPinShortcut(shortcutManager.dynamicShortcuts.single { it.id == "thread:2" }, null)
        // One pass (the blocklist alone excludes the thread) disables the pinned copy.
        runBlocking { settings.setBlockedSenders(setOf("sender-2")) }
        assertThat(disabledPinnedIds()).containsExactly("thread:2")
        assertThat(disableLogLines(logStart)).hasSize(1)

        // The platform reports a disabled pinned shortcut with isEnabled false
        // for as long as the user keeps it on the home screen. Robolectric's
        // shadow never flips the flag, so flip it the way the framework does.
        markDisabled(shortcutManager.pinnedShortcuts.single { it.id == "thread:2" })

        // Further passes - the block's per-row half, messages in the surviving
        // thread - must not re-disable it: no system call, and the log stays honest.
        runBlocking { dao.setBlockedSender("sender-2", blocked = true) }
        insert(message(id = 50, threadId = 1, timestamp = 5_000))
        insert(message(id = 51, threadId = 1, timestamp = 6_000))
        assertThat(disableLogLines(logStart)).hasSize(1)
    }

    /** The publisher's own "stale pinned ... disabled" diagnostic lines since [sinceMs]. */
    private fun disableLogLines(sinceMs: Long) =
        Diag.buffer.snapshot(sinceMs = sinceMs).map { it.text }.filter { "stale pinned conversation shortcuts disabled" in it }

    /** Sets the framework's hidden `FLAG_DISABLED` on [info], as `disableShortcuts` does on a device. */
    private fun markDisabled(info: ShortcutInfo) {
        val flagDisabled = ShortcutInfo::class.java.getDeclaredField("FLAG_DISABLED").apply { isAccessible = true }.getInt(null)
        ShortcutInfo::class.java
            .getDeclaredMethod("addFlags", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(info, flagDisabled)
        assertThat(info.isEnabled).isFalse()
    }

    /**
     * Robolectric's ShortcutManager moves a disabled pinned shortcut into a
     * private static map and keeps returning it from `getPinnedShortcuts`
     * without flipping `isEnabled`, so the only observable record of a
     * `disableShortcuts` call is that map. Read by reflection, test-only.
     */
    @Suppress("UNCHECKED_CAST")
    private fun disabledPinnedIds(): Set<String> {
        val field =
            Class
                .forName("org.robolectric.shadows.ShadowShortcutManager")
                .getDeclaredField("disabledPinnedShortcuts")
                .apply { isAccessible = true }
        return (field.get(null) as Map<String, ShortcutInfo>).keys.toSet()
    }

    @Test
    fun `an unchanged list is not re-sent to the system`() {
        seedThreads(3)
        publisher.start()
        val before = shortcutManager.dynamicShortcuts.sortedBy { it.rank }

        // A newer message in the thread that is ALREADY on top: same ids,
        // same ranks, same labels - the launcher would see nothing change,
        // so the (rate-limited) system call is skipped and the very same
        // ShortcutInfo objects stay registered.
        insert(message(id = 99, threadId = 3, timestamp = 99_000))
        assertThat(dynamicIds()).containsExactly("thread:3", "thread:2", "thread:1").inOrder()
        val after = shortcutManager.dynamicShortcuts.sortedBy { it.rank }
        assertThat(after).hasSize(before.size)
        before.zip(after).forEach { (b, a) -> assertThat(a).isSameInstanceAs(b) }

        // Whereas a NEW thread on top changes the list and IS re-sent.
        insert(message(id = 100, threadId = 4, timestamp = 100_000))
        assertThat(dynamicIds().first()).isEqualTo("thread:4")
    }

    // --- The registry the notifier reads -----------------------------------

    @Test
    fun `the registry reports exactly the threads the system accepted, and nothing before the first publish`() {
        seedThreads(7) // budget is 5, minus the static "New message" = 4
        // Before start(): nothing is published, so a notification names no shortcut.
        assertThat(publisher.isPublished(7L)).isFalse()

        publisher.start()
        val live = dynamicIds().map { ConversationShortcutSelection.threadIdOf(it) }
        assertThat(live).containsExactly(7L, 6L, 5L, 4L).inOrder()
        live.forEach { assertThat(publisher.isPublished(requireNotNull(it))).isTrue() }
        // Outside the budget: a real conversation, no shortcut, and the
        // registry says so rather than letting the notifier name a ghost.
        assertThat(publisher.isPublished(3L)).isFalse()
        assertThat(publisher.isPublished(1L)).isFalse()
        assertThat(publisher.isPublished(999L)).isFalse()
    }

    @Test
    fun `the registry follows the setting and exclusions the same instant the launcher does`() {
        seedThreads(2)
        publisher.start()
        assertThat(publisher.isPublished(1L)).isTrue()
        assertThat(publisher.isPublished(2L)).isTrue()

        runBlocking { settings.setConversationShortcuts(false) }
        assertThat(dynamicIds()).isEmpty()
        assertThat(publisher.isPublished(1L)).isFalse()
        assertThat(publisher.isPublished(2L)).isFalse()

        runBlocking { settings.setConversationShortcuts(true) }
        assertThat(publisher.isPublished(2L)).isTrue()

        // Blocking thread 2's sender drops its shortcut AND its registry entry together.
        runBlocking { settings.setBlockedSenders(setOf("sender-2")) }
        assertThat(dynamicIds()).containsExactly("thread:1")
        assertThat(publisher.isPublished(2L)).isFalse()
        assertThat(publisher.isPublished(1L)).isTrue()
    }
}
