package app.clearsms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.DeliveryStatus
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import app.clearsms.domain.model.Category
import app.clearsms.sms.SystemSmsImporter
import app.clearsms.testing.InMemoryPreferencesDataStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Issue #153: "every time a message is sent or received the app freezes;
 * you can type and send, but the message is not visible". SQLite has ONE
 * writer, so a message written while a bulk worker's page transaction is
 * open is stored only when that transaction commits - the bubble appears
 * late by exactly the page's duration. The fix bounds the page; this test
 * pins the bound, shows the mechanism it bounds, and checks that a send
 * during a running re-sort commits promptly.
 */
@RunWith(RobolectricTestRunner::class)
class BulkWriterContentionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    /** Every SQL statement Room executed, in order (see [transactionSizes]). */
    private val statements = Collections.synchronizedList(mutableListOf<String>())
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // File-backed, Room's own executors: the writer contention under
        // test does not exist on an in-memory database with main-thread
        // queries.
        db =
            Room
                .databaseBuilder(context, ClearSmsDatabase::class.java, File(tmp.root, "contention.db").absolutePath)
                .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> statements += sql }, callbackExecutor)
                .build()
        val json = Json { ignoreUnknownKeys = true }
        repository =
            MessageRepositoryImpl(
                database = db,
                categorizer =
                    MessageCategorizer(
                        ruleEngine = RuleEngine(),
                        senderIdLookup = SenderIdLookup { null },
                        contactLookup = ContactLookup { false },
                    ),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, InMemoryPreferencesDataStore()),
                json = json,
            )
    }

    @After
    fun tearDown() {
        db.close()
        callbackExecutor.shutdownNow()
    }

    private fun outgoing(i: Int) =
        MessageEntity(
            threadId = 1L,
            sender = "+919876543210",
            normalizedSender = "919876543210",
            body = "reply $i",
            timestamp = 2_000_000_000_000L + i,
            isRead = true,
            category = Category.PERSONAL,
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.SENDING,
        )

    private suspend fun seed(count: Int) {
        for (i in 0 until count) {
            repository.insertIncoming("VM-ICICIB", "Rs.${100 + i}.00 debited from A/c XX9805 Ref $i - ICICI Bank.", 1_000L + i * 60_000L)
        }
    }

    /**
     * Number of `messages` rows written per OUTERMOST transaction, in commit
     * order. Room nests a savepoint transaction around every DAO call made
     * inside `withTransaction`, and the callback logs those too, so the log
     * is read with a depth counter: a transaction is one stretch from the
     * BEGIN that takes depth 0 -> 1 to the END that brings it back to 0,
     * and the rows matching [rowStatement] inside it are counted.
     */
    private fun transactionSizes(rowStatement: String): List<Int> {
        val sizes = mutableListOf<Int>()
        var depth = 0
        var rows = 0
        synchronized(statements) {
            for (sql in statements) {
                when {
                    sql.startsWith("BEGIN") -> {
                        if (depth == 0) rows = 0
                        depth++
                    }

                    sql.startsWith("END") || sql.startsWith("COMMIT") -> {
                        depth = (depth - 1).coerceAtLeast(0)
                        if (depth == 0) sizes += rows
                    }

                    depth > 0 && sql.startsWith(rowStatement) -> {
                        rows++
                    }
                }
            }
        }
        return sizes
    }

    @Test
    fun `the bulk writers hold the single writer for at most 50 rows per transaction`() {
        // The two bulk writers and the one number each pins. A bigger page is
        // a longer window in which a just-sent message cannot be stored.
        assertThat(MessageRepositoryImpl.RECATEGORIZE_PAGE_SIZE).isEqualTo(50)
        assertThat(SystemSmsImporter.PAGE_SIZE).isEqualTo(50)
    }

    @Test
    fun `a full re-sort never updates more than one page of messages in one transaction`() =
        runBlocking {
            seed(130)
            // Let the seed's own statements drain, then log only the re-sort.
            callbackExecutor.submit {}.get()
            statements.clear()

            repository.recategorizeAll()
            callbackExecutor.submit {}.get()

            val sizes = transactionSizes("UPDATE OR ABORT `messages`").filter { it > 0 }
            assertThat(sizes).isNotEmpty()
            assertThat(sizes.max()).isEqualTo(MessageRepositoryImpl.RECATEGORIZE_PAGE_SIZE)
            // 130 rows in pages of 50: the first two pages full, the last short.
            assertThat(sizes).containsExactly(50, 50, 30).inOrder()
        }

    @Test
    fun `a write transaction left open delays a concurrent outgoing insert for exactly its duration`() =
        runBlocking {
            // The mechanism behind the report, in miniature: one page
            // transaction that holds the writer for 300 ms. An insert made
            // meanwhile (the sent bubble) cannot commit before it ends.
            val hold =
                async(Dispatchers.IO) {
                    db.withTransaction {
                        db.messageDao().insert(outgoing(1))
                        delay(300)
                    }
                }
            delay(50)
            val started = System.nanoTime()
            withContext(Dispatchers.IO) { db.messageDao().insert(outgoing(2)) }
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            hold.await()

            assertThat(waitedMs).isAtLeast(200)
        }

    @Test
    fun `an outgoing insert during a running re-sort commits promptly`() =
        runBlocking {
            seed(200)
            val resort = async(Dispatchers.IO) { repository.recategorizeAll() }
            // Sends made while the re-sort pages through the database.
            val waits = mutableListOf<Long>()
            var i = 0
            while (resort.isActive && i < 40) {
                val started = System.nanoTime()
                withContext(Dispatchers.IO) { db.messageDao().insert(outgoing(1_000 + i++)) }
                waits += (System.nanoTime() - started) / 1_000_000
                delay(2)
            }
            resort.await()

            assertThat(waits).isNotEmpty()
            // A page is tens of milliseconds here, a few hundred on a phone;
            // "a few seconds" - the reported symptom - is what must never
            // happen. The bound is loose on purpose: this is a CI-safe sanity
            // check of the symptom, the page size test above is the contract.
            assertThat(waits.max()).isLessThan(1_000)
            // Every send landed as its own row - none lost in the contention.
            assertThat(db.messageDao().getAll().count { it.isOutgoing }).isEqualTo(waits.size)
        }
}
