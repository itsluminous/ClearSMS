package app.clearsms.shortcuts

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.notification.NotificationSender
import app.clearsms.notification.NotificationSenderResolver
import app.clearsms.notification.SenderIconFactory
import app.clearsms.sms.ContactsSource
import app.clearsms.testing.FakeSettingsRepository
import app.clearsms.testing.FakeShortcutSystem
import app.clearsms.testing.FileProviderTestSupport
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

/**
 * The privacy defect device verification caught: with the conversation-
 * shortcuts setting turned OFF, a copy the system had CACHED for a
 * conversation notification survived - `thread:1 flags=0x6288
 * [Ic-fIc-aStrLiv]`, label, Person and avatar still in the system -
 * because `removeAllDynamicShortcuts` does not touch cached shortcuts. The
 * existing OFF-state test proved only that the DYNAMIC list emptied, which
 * is what let this through; Robolectric's shadow keeps no cache at all, so
 * it could never have failed.
 *
 * These tests drive the real publisher against [FakeShortcutSystem], a
 * model of AOSP `ShortcutPackage`'s dynamic / cached / pinned state
 * machine, and pin that OFF removes every copy the system holds in EVERY
 * state - and what the two edge cases leave behind: a pinned copy (stays
 * on the home screen, disabled, with no dynamic or cached copy) and the
 * setting being off from the first run (nothing is ever published,
 * rendered or asked of the system).
 */
@RunWith(RobolectricTestRunner::class)
class ConversationShortcutCachedCopiesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: ClearSmsDatabase
    private lateinit var dao: MessageDao
    private lateinit var settings: FakeSettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var system: FakeShortcutSystem
    private lateinit var icons: ConversationShortcutIcons
    private lateinit var publisher: ConversationShortcutPublisher

    private val resolver =
        object : NotificationSenderResolver(context, ContactsSource(context), SenderIdLookup { null }) {
            override fun resolve(sender: String) = NotificationSender(name = sender.replace("sender-", "contact-"), monogram = "C")
        }

    @Before
    fun setUp() {
        FileProviderTestSupport.resetPathStrategyCache()
        val direct = java.util.concurrent.Executor { it.run() }
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
        system = FakeShortcutSystem(context, maxPerActivity = 5)
        val iconFactory = SenderIconFactory(context)
        icons = ConversationShortcutIcons(context, iconFactory)
        publisher =
            ConversationShortcutPublisher(
                context = context,
                settings = settings,
                messageDao = dao,
                senderResolver = resolver,
                factory = ConversationShortcutFactory(context, iconFactory, icons),
                icons = icons,
                system = system,
                scope = scope,
            ).apply { debounceMs = 0L }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun seedThreads(count: Int) =
        runBlocking {
            dao.insertAll(
                (1L..count).map {
                    MessageEntity(
                        id = it,
                        threadId = it,
                        sender = "sender-$it",
                        normalizedSender = "sender-$it",
                        body = "body $it",
                        timestamp = it * 100,
                        category = Category.PERSONAL,
                    )
                },
            )
        }

    private fun dynamicIds(): Set<String> = system.getShortcuts(ShortcutManagerCompat.FLAG_MATCH_DYNAMIC).map { it.id }.toSet()

    private fun avatarFiles(): List<String> = icons.fileFor(0L).parentFile?.list()?.sorted().orEmpty()

    @Test
    fun `the model reproduces the defect - a dynamic-only removal leaves the cached copy in the system`() {
        // Negative control for the fake itself, so the tests below cannot
        // pass vacuously: this is what the shadow could not show.
        seedThreads(2)
        publisher.start()
        assertThat(dynamicIds()).containsExactly("thread:1", "thread:2")
        system.cacheForConversationNotification("thread:1")

        system.removeAllDynamicShortcuts()

        assertThat(dynamicIds()).isEmpty()
        assertWithMessage("the cached copy survives a dynamic-only removal, as on the device")
            .that(system.heldIds())
            .containsExactly("thread:1")
        assertThat(system.isCached("thread:1")).isTrue()
        assertThat(system.shortcut("thread:1").shortLabel).isEqualTo("contact-1")
    }

    @Test
    fun `turning the setting off removes the copies the system cached for notifications, not merely the dynamic list`() {
        seedThreads(3)
        publisher.start()
        assertThat(dynamicIds()).containsExactly("thread:1", "thread:2", "thread:3")
        // Conversation notifications for threads 1 and 2 named their shortcuts: the system cached them.
        system.cacheForConversationNotification("thread:1")
        system.cacheForConversationNotification("thread:2")
        assertThat(system.isCached("thread:1")).isTrue()
        assertThat(avatarFiles()).hasSize(3)

        runBlocking { settings.setConversationShortcuts(false) }

        // Nothing conversation-derived remains in the system, in any state.
        assertWithMessage("no conversation shortcut may survive the switch in ANY state")
            .that(system.heldIds())
            .isEmpty()
        assertThat(system.getShortcuts(ShortcutManagerCompat.FLAG_MATCH_CACHED)).isEmpty()
        // The removal went through the API that reaches cached copies, with
        // every id the system held - read from the system, not from memory.
        assertThat(system.removeLongLivedCalls).hasSize(1)
        assertThat(system.removeLongLivedCalls.single()).containsExactly("thread:1", "thread:2", "thread:3")
        // The avatar files and the notifier's registry go with them.
        assertThat(avatarFiles()).isEmpty()
        (1L..3L).forEach { assertThat(publisher.isPublished(it)).isFalse() }
    }

    @Test
    fun `a copy cached by an earlier process is removed too - the ids come from the system, not from memory`() {
        // The shortcut exists in the system before this process ever
        // publishes (a previous run published and a notification cached it).
        seedThreads(1)
        publisher.start()
        system.cacheForConversationNotification("thread:1")
        // A fresh publisher - fresh memory - with the setting off.
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val iconFactory = SenderIconFactory(context)
        val fresh =
            ConversationShortcutPublisher(
                context = context,
                settings = FakeSettingsRepository().apply { runBlocking { setConversationShortcuts(false) } },
                messageDao = dao,
                senderResolver = resolver,
                factory = ConversationShortcutFactory(context, iconFactory, icons),
                icons = icons,
                system = system,
                scope = scope,
            ).apply { debounceMs = 0L }

        fresh.start()

        assertThat(system.heldIds()).isEmpty()
        assertThat(system.removeLongLivedCalls.last()).containsExactly("thread:1")
    }

    @Test
    fun `a copy the user pinned survives the switch only as a disabled home-screen item, with no dynamic or cached copy`() {
        seedThreads(2)
        publisher.start()
        system.pin("thread:1")
        system.cacheForConversationNotification("thread:1")

        runBlocking { settings.setConversationShortcuts(false) }

        // The pinned copy is the launcher's, so it stays - disabled, with
        // the setting-off message on tap - but the cached and dynamic
        // copies of the same shortcut are gone, and thread 2 is gone entirely.
        assertThat(system.heldIds()).containsExactly("thread:1")
        assertThat(system.isPinned("thread:1")).isTrue()
        assertThat(system.isEnabled("thread:1")).isFalse()
        assertThat(system.isCached("thread:1")).isFalse()
        assertThat(system.isDynamic("thread:1")).isFalse()
        assertThat(system.getShortcuts(ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED)).isEmpty()
        // Its avatar file goes too: the user turned this off.
        assertThat(avatarFiles()).isEmpty()
    }

    @Test
    fun `with the setting off from the first run nothing is published, rendered or removed - the system is asked for nothing`() {
        runBlocking { settings.setConversationShortcuts(false) }
        seedThreads(3)

        publisher.start()

        assertThat(system.heldIds()).isEmpty()
        // No ids to remove, so the removal API is never even called.
        assertThat(system.removeLongLivedCalls).isEmpty()
        // No avatar is rendered: the directory does not exist.
        assertThat(icons.fileFor(0L).parentFile!!.exists()).isFalse()
        (1L..3L).forEach { assertThat(publisher.isPublished(it)).isFalse() }
    }

    @Test
    fun `turning the setting back on republishes, and a cached copy of a still-selected thread is simply replaced`() {
        seedThreads(2)
        publisher.start()
        system.cacheForConversationNotification("thread:2")
        runBlocking { settings.setConversationShortcuts(false) }
        assertThat(system.heldIds()).isEmpty()

        runBlocking { settings.setConversationShortcuts(true) }

        assertThat(dynamicIds()).containsExactly("thread:1", "thread:2")
        assertThat(system.isCached("thread:2")).isFalse()
        assertThat(avatarFiles()).containsExactly("thread-1.png", "thread-2.png")
        assertThat(publisher.isPublished(2L)).isTrue()
    }
}
