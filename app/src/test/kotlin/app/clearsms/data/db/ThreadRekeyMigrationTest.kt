package app.clearsms.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.clearsms.data.repository.SenderNormalizer
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v21→v22 (issue #42): the conversations users ALREADY have split - one per
 * dialling variant of one person - are merged in place, with pins, drafts
 * and per-row read / archived / blocked state intact; everything else is
 * left exactly as it was. Rows are inserted against the committed v21
 * schema (the pre-fix key, `digits.takeLast(10)`) and read back after the
 * real migration ran. Every number is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class ThreadRekeyMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClearSmsDatabase::class.java,
        )

    private class FakeProviderThreads(
        private val rows: List<ProviderThreadSource.ProviderThread>,
    ) : ProviderThreadSource {
        override fun threads() = rows
    }

    @Before
    fun setUp() {
        SenderNormalizer.defaultRegion = "PL"
        RekeyThreads.providerThreadSource = null
    }

    @After
    fun tearDown() {
        SenderNormalizer.defaultRegion = null
        RekeyThreads.providerThreadSource = null
    }

    /** A v21 row under the OLD key: `normalizedSender` is the last ten digits. */
    private fun SupportSQLiteDatabase.insertV21(
        threadId: Long,
        sender: String,
        body: String,
        timestamp: Long,
        isRead: Boolean = true,
        isArchived: Boolean = false,
        isBlocked: Boolean = false,
        systemSmsId: Long? = null,
        normalizedSender: String = legacyKey(sender),
    ) {
        execSQL(
            """
            INSERT INTO messages (threadId, sender, normalizedSender, body, timestamp, isRead, isArchived,
                                  category, isBlockedSender, systemSmsId)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'PERSONAL', ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                threadId,
                sender,
                normalizedSender,
                body,
                timestamp,
                if (isRead) 1 else 0,
                if (isArchived) 1 else 0,
                if (isBlocked) 1 else 0,
                systemSmsId,
            ),
        )
    }

    /** The pre-#42 normalizer, verbatim. */
    private fun legacyKey(sender: String): String {
        val trimmed = sender.trim()
        val digits = trimmed.replace(Regex("\\D"), "")
        if (digits.length >= 7 && digits.length >= trimmed.count { !it.isWhitespace() } - 3) return digits.takeLast(10)
        return trimmed.uppercase().replace(Regex("^[A-Z]{2}-"), "").replace(Regex("-[SPTG]$"), "")
    }

    private fun SupportSQLiteDatabase.rows(): List<Row> =
        query(
            "SELECT id, threadId, sender, normalizedSender, isRead, isArchived, isBlockedSender, providerThreadId " +
                "FROM messages ORDER BY id",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Row(
                            id = c.getLong(0),
                            threadId = c.getLong(1),
                            sender = c.getString(2),
                            key = c.getString(3),
                            isRead = c.getInt(4) == 1,
                            isArchived = c.getInt(5) == 1,
                            isBlocked = c.getInt(6) == 1,
                            providerThreadId = if (c.isNull(7)) null else c.getLong(7),
                        ),
                    )
                }
            }
        }

    private data class Row(
        val id: Long,
        val threadId: Long,
        val sender: String,
        val key: String,
        val isRead: Boolean,
        val isArchived: Boolean,
        val isBlocked: Boolean,
        val providerThreadId: Long?,
    )

    private fun SupportSQLiteDatabase.pins(): Map<String, Long> =
        query("SELECT normalizedSender, pinnedAt FROM thread_pins").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }

    private fun SupportSQLiteDatabase.drafts(): Map<Long, Pair<String, Long>> =
        query("SELECT threadId, text, updatedAt FROM drafts").use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1) to c.getLong(2)) }
        }

    @Test
    fun `two split threads of one polish number merge into one, pins drafts and row state intact`() {
        helper.createDatabase(TEST_DB, 21).apply {
            // The bug as reported: 48601234567 -> 8601234567 vs 601234567.
            insertV21(threadId = 1, sender = "+48601234567", body = "hi", timestamp = 1_000, isRead = true, isArchived = true)
            insertV21(threadId = 1, sender = "+48601234567", body = "again", timestamp = 3_000, isRead = false, isArchived = true)
            insertV21(threadId = 2, sender = "601234567", body = "reply", timestamp = 2_000, isRead = true, isBlocked = true)
            query("SELECT DISTINCT normalizedSender FROM messages").use { c ->
                val keys = buildSet { while (c.moveToNext()) add(c.getString(0)) }
                assertThat(keys).containsExactly("8601234567", "601234567")
            }
            execSQL("INSERT INTO thread_pins (normalizedSender, pinnedAt) VALUES ('8601234567', 500)")
            execSQL("INSERT INTO drafts (threadId, text, updatedAt) VALUES (1, 'older draft', 10)")
            execSQL("INSERT INTO drafts (threadId, text, updatedAt) VALUES (2, 'newer draft', 20)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true)

        val rows = db.rows()
        assertThat(rows).hasSize(3)
        // One thread - the lower id - and one key.
        assertThat(rows.map { it.threadId }.toSet()).containsExactly(1L)
        assertThat(rows.map { it.key }.toSet()).containsExactly("601234567")
        // Raw senders and per-row state are exactly what they were.
        assertThat(rows.map { it.sender }).containsExactly("+48601234567", "+48601234567", "601234567").inOrder()
        assertThat(rows.map { it.isRead }).containsExactly(true, false, true).inOrder()
        assertThat(rows.map { it.isArchived }).containsExactly(true, true, false).inOrder()
        assertThat(rows.map { it.isBlocked }).containsExactly(false, false, true).inOrder()
        // The pin followed the rows to the new key; the dead key is gone.
        assertThat(db.pins()).containsExactly("601234567", 500L)
        // One thread, one draft: the newest survives on the surviving id.
        assertThat(db.drafts()).containsExactly(1L, "newer draft" to 20L)
        // No provider was consulted: nothing anchored.
        assertThat(rows.map { it.providerThreadId }).containsExactly(null, null, null)
    }

    @Test
    fun `a single unaffected thread is untouched - indian keys do not move`() {
        SenderNormalizer.defaultRegion = "IN"
        helper.createDatabase(TEST_DB, 21).apply {
            insertV21(threadId = 7, sender = "+919876543210", body = "a", timestamp = 1_000)
            insertV21(threadId = 7, sender = "9876543210", body = "b", timestamp = 2_000, isRead = false)
            insertV21(threadId = 8, sender = "VM-HDFCBK-S", body = "c", timestamp = 3_000)
            insertV21(threadId = 8, sender = "AD-HDFCBK", body = "d", timestamp = 4_000)
            insertV21(threadId = 9, sender = "56767", body = "e", timestamp = 5_000)
            execSQL("INSERT INTO thread_pins (normalizedSender, pinnedAt) VALUES ('9876543210', 1)")
            execSQL("INSERT INTO thread_pins (normalizedSender, pinnedAt) VALUES ('HDFCBK', 2)")
            execSQL("INSERT INTO drafts (threadId, text, updatedAt) VALUES (7, 'draft', 10)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true)

        val rows = db.rows()
        assertThat(rows.map { it.threadId }).containsExactly(7L, 7L, 8L, 8L, 9L).inOrder()
        assertThat(rows.map { it.key }).containsExactly("9876543210", "9876543210", "HDFCBK", "HDFCBK", "56767").inOrder()
        assertThat(rows.map { it.isRead }).containsExactly(true, false, true, true, true).inOrder()
        assertThat(db.pins()).containsExactly("9876543210", 1L, "HDFCBK", 2L)
        assertThat(db.drafts()).containsExactly(7L, "draft" to 10L)
    }

    @Test
    fun `the re-key is idempotent - a second run writes nothing`() {
        helper.createDatabase(TEST_DB, 21).apply {
            insertV21(threadId = 1, sender = "+48601234567", body = "hi", timestamp = 1_000)
            insertV21(threadId = 2, sender = "601234567", body = "reply", timestamp = 2_000)
            insertV21(threadId = 3, sender = "VM-HDFCBK", body = "x", timestamp = 3_000)
            execSQL("INSERT INTO thread_pins (normalizedSender, pinnedAt) VALUES ('601234567', 5)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true)
        val once = Triple(db.rows(), db.pins(), db.drafts())

        val second = ThreadRekeyer.rekey(db)

        assertThat(second.rekeyedSenders).isEqualTo(0)
        assertThat(second.mergedThreads).isEqualTo(0)
        assertThat(second.movedRows).isEqualTo(0)
        assertThat(Triple(db.rows(), db.pins(), db.drafts())).isEqualTo(once)
    }

    @Test
    fun `short codes and alphanumeric ids are never merged with each other or with phone numbers`() {
        SenderNormalizer.defaultRegion = "IN"
        helper.createDatabase(TEST_DB, 21).apply {
            insertV21(threadId = 1, sender = "56767", body = "short code", timestamp = 1_000)
            insertV21(threadId = 2, sender = "+919876556767", body = "a number ending in the code", timestamp = 2_000)
            insertV21(threadId = 3, sender = "VM-HDFCBK", body = "bank", timestamp = 3_000)
            insertV21(threadId = 4, sender = "HDFCBK", body = "bank again", timestamp = 4_000, normalizedSender = "HDFCBK")
            close()
        }
        // Even a provider that filed the short code with the number cannot
        // make the app do so: a short code never anchors.
        RekeyThreads.providerThreadSource =
            FakeProviderThreads(
                listOf(
                    ProviderThreadSource.ProviderThread(smsId = 1, threadId = 40, address = "56767"),
                    ProviderThreadSource.ProviderThread(smsId = 2, threadId = 40, address = "+919876556767"),
                ),
            )

        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true)

        val rows = db.rows()
        assertThat(rows.map { it.threadId }).containsExactly(1L, 2L, 3L, 3L).inOrder()
        assertThat(rows.map { it.key }).containsExactly("56767", "9876556767", "HDFCBK", "HDFCBK").inOrder()
    }

    @Test
    fun `provider thread ids merge variants the normalizer cannot fold - unknown region`() {
        // No SIM region: the normalizer keeps a French trunk zero, so the two
        // forms have different keys; the platform knows they are one thread.
        SenderNormalizer.defaultRegion = null
        helper.createDatabase(TEST_DB, 21).apply {
            insertV21(threadId = 1, sender = "+33612345678", body = "a", timestamp = 1_000, systemSmsId = 100)
            insertV21(threadId = 2, sender = "0612345678", body = "b", timestamp = 2_000, systemSmsId = 101)
            // Same shape, a DIFFERENT person, no anchor: must stay apart.
            insertV21(threadId = 3, sender = "0698765432", body = "c", timestamp = 3_000, systemSmsId = 102)
            close()
        }
        RekeyThreads.providerThreadSource =
            FakeProviderThreads(
                listOf(
                    ProviderThreadSource.ProviderThread(smsId = 100, threadId = 55, address = "+33612345678"),
                    ProviderThreadSource.ProviderThread(smsId = 101, threadId = 55, address = "0612345678"),
                ),
            )

        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true)

        val rows = db.rows()
        assertThat(rows.map { it.threadId }).containsExactly(1L, 1L, 3L).inOrder()
        assertThat(rows.map { it.providerThreadId }).containsExactly(55L, 55L, null).inOrder()
        // Keys stay honest (recomputable from the sender); only the thread merged.
        assertThat(rows.map { it.key }).containsExactly("612345678", "0612345678", "0698765432").inOrder()
    }

    @Test
    fun `a provider thread holding two different people - a group - merges nobody`() {
        helper.createDatabase(TEST_DB, 21).apply {
            insertV21(threadId = 1, sender = "+48601234567", body = "a", timestamp = 1_000, systemSmsId = 1)
            insertV21(threadId = 2, sender = "+48509876543", body = "b", timestamp = 2_000, systemSmsId = 2)
            close()
        }
        RekeyThreads.providerThreadSource =
            FakeProviderThreads(
                listOf(
                    ProviderThreadSource.ProviderThread(smsId = 1, threadId = 77, address = "+48601234567"),
                    ProviderThreadSource.ProviderThread(smsId = 2, threadId = 77, address = "+48509876543"),
                ),
            )

        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true)

        val rows = db.rows()
        assertThat(rows.map { it.threadId }).containsExactly(1L, 2L).inOrder()
        // The anchor is recorded (it is the provider's truth) but not obeyed.
        assertThat(rows.map { it.providerThreadId }).containsExactly(77L, 77L).inOrder()
    }

    @Test
    fun `a row whose provider row is gone stays unanchored and is still merged by its key`() {
        helper.createDatabase(TEST_DB, 21).apply {
            insertV21(threadId = 1, sender = "+48601234567", body = "a", timestamp = 1_000, systemSmsId = 100)
            insertV21(threadId = 2, sender = "601234567", body = "b", timestamp = 2_000, systemSmsId = 999)
            // A reused provider id: the provider's row 100 now belongs to
            // someone else - the app row must not inherit that thread.
            insertV21(threadId = 3, sender = "+48700000000", body = "c", timestamp = 3_000, systemSmsId = 300)
            close()
        }
        RekeyThreads.providerThreadSource =
            FakeProviderThreads(
                listOf(
                    ProviderThreadSource.ProviderThread(smsId = 100, threadId = 55, address = "+48601234567"),
                    ProviderThreadSource.ProviderThread(smsId = 300, threadId = 55, address = "+48601234567"),
                ),
            )

        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true)

        val rows = db.rows()
        assertThat(rows.map { it.threadId }).containsExactly(1L, 1L, 3L).inOrder()
        assertThat(rows.map { it.providerThreadId }).containsExactly(55L, null, null).inOrder()
    }

    @Test
    fun `a few thousand rows re-key in well under a second`() {
        helper.createDatabase(TEST_DB, 21).apply {
            beginTransaction()
            try {
                var thread = 0L
                // 300 people, each split across the +48 and local forms,
                // ~5000 rows in total; plus some alphanumeric traffic.
                for (person in 0 until 300) {
                    val local = "6%08d".format(person)
                    thread++
                    for (i in 0 until 8) insertV21(thread, "+48$local", "m$i", (person * 100 + i).toLong())
                    thread++
                    for (i in 0 until 8) insertV21(thread, local, "r$i", (person * 100 + 50 + i).toLong())
                }
                for (i in 0 until 200) insertV21(++thread, "VM-BANK$i", "promo", i.toLong())
                for (person in 0 until 50) execSQL("INSERT INTO thread_pins VALUES ('${legacyKey("+486%08d".format(person))}', $person)")
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
            close()
        }

        val started = System.nanoTime()
        val db = helper.runMigrationsAndValidate(TEST_DB, 22, true)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val rows = db.rows()
        assertThat(rows).hasSize(300 * 16 + 200)
        assertThat(rows.map { it.threadId }.distinct()).hasSize(300 + 200)
        assertThat(db.pins()).hasSize(50)
        assertThat(db.pins().keys.all { it.length == 9 }).isTrue()
        println("v21->v22 re-key of ${rows.size} rows took $elapsedMs ms")
        assertThat(elapsedMs).isLessThan(10_000)
    }

    private companion object {
        const val TEST_DB = "thread-rekey-migration.db"
    }
}
