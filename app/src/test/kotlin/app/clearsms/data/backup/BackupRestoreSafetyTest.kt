package app.clearsms.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.RuleEntity
import app.clearsms.data.rules.RuleSources
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class BackupRestoreSafetyTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var manager: BackupManager
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        manager = BackupManager(db, json)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun document(body: String): InputStream = ByteArrayInputStream(body.toByteArray())

    private fun message(
        id: Long,
        category: Category = Category.PERSONAL,
        timestamp: Long = id,
    ) = MessageEntity(
        id = id,
        threadId = 1L,
        sender = "AX-TEST",
        normalizedSender = "TEST",
        body = "body $id",
        timestamp = timestamp,
        category = category,
    )

    private fun builtinRule(id: String = "generic-otp") =
        RuleEntity(
            id = id,
            name = "Builtin",
            priority = 100,
            matchJson = "{}",
            actionJson = "{}",
            isUserDefined = false,
            source = RuleSources.BUILTIN,
            createdAt = 0L,
        )

    @Test
    fun `unknown enum values are defaulted, not thrown`() =
        runBlocking {
            val backup =
                """
                {"formatVersion":1,"createdAt":1,
                 "messages":[{"id":1,"threadId":1,"sender":"a","normalizedSender":"A","body":"b",
                              "timestamp":1,"isRead":false,"isArchived":false,
                              "category":"IMPRTANT","subCategory":"NOT_A_SUBCATEGORY"}]}
                """.trimIndent()

            val result = manager.importFrom(document(backup))

            val restored = db.messageDao().getAll().single()
            assertThat(restored.category).isEqualTo(Category.UNKNOWN)
            assertThat(restored.subCategory).isNull()
            assertThat(result.messages).isEqualTo(1)
            assertThat(result.defaultedValues).isEqualTo(2)
        }

    @Test
    fun `transaction rows with unknown type are skipped and counted`() =
        runBlocking {
            val backup =
                """
                {"formatVersion":1,"createdAt":1,
                 "transactions":[
                   {"id":1,"amount":10.0,"type":"DEBIT","accountNumber":"1234","bankName":"B",
                    "timestamp":1,"category":"OTHER","rawSmsId":1},
                   {"id":2,"amount":20.0,"type":"SIDEWAYS","accountNumber":"1234","bankName":"B",
                    "timestamp":2,"category":"OTHER","rawSmsId":2}]}
                """.trimIndent()

            val result = manager.importFrom(document(backup))

            assertThat(db.transactionDao().getAll()).hasSize(1)
            assertThat(result.transactions).isEqualTo(1)
            assertThat(result.skippedRows).isEqualTo(1)
        }

    @Test
    fun `database is intact after a mid-restore stream failure`() =
        runBlocking {
            db.messageDao().insertAll(listOf(message(1), message(2), message(3)))
            db.ruleDao().insert(builtinRule())

            val failing =
                object : InputStream() {
                    private val prefix = """{"formatVersion":1,"createdAt":1,"messages":[""".toByteArray()
                    private var position = 0

                    override fun read(): Int {
                        if (position < prefix.size) return prefix[position++].toInt()
                        throw IOException("stream died mid-restore")
                    }
                }

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { manager.importFrom(failing) }
            }

            assertThat(db.messageDao().getAll()).hasSize(3)
            assertThat(db.ruleDao().getAll()).hasSize(1)
        }

    @Test
    fun `newer formatVersion is rejected with a clear reason and leaves data untouched`() =
        runBlocking {
            db.messageDao().insertAll(listOf(message(1)))

            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { manager.importFrom(document("""{"formatVersion":99,"createdAt":1}""")) }
                }

            assertThat(error).hasMessageThat().contains("newer")
            assertThat(db.messageDao().getAll()).hasSize(1)
        }

    @Test
    fun `crafted rule source is sanitized and cannot overwrite a builtin`() =
        runBlocking {
            db.ruleDao().insert(builtinRule("generic-otp"))

            val backup =
                """
                {"formatVersion":1,"createdAt":1,
                 "rules":[
                   {"id":"generic-otp","name":"Evil","priority":9999,"matchJson":"{}","actionJson":"{}",
                    "isUserDefined":false,"source":"user","createdAt":0},
                   {"id":"sneaky","name":"Sneaky","priority":1,"matchJson":"{}","actionJson":"{}",
                    "isUserDefined":false,"source":"builtin","createdAt":0}]}
                """.trimIndent()

            val result = manager.importFrom(document(backup))

            val rules = db.ruleDao().getAll()
            // The builtin row survives untouched.
            val builtin = rules.single { it.id == "generic-otp" }
            assertThat(builtin.name).isEqualTo("Builtin")
            assertThat(builtin.source).isEqualTo(RuleSources.BUILTIN)
            // The user-claimed row is restored under the namespaced id, forced to user source.
            val restored = rules.single { it.id == "user:generic-otp" }
            assertThat(restored.source).isEqualTo(RuleSources.USER)
            assertThat(restored.isUserDefined).isTrue()
            // Rows claiming builtin/community source are never restored from a file.
            assertThat(rules.map { it.id }).doesNotContain("sneaky")
            assertThat(result.rules).isEqualTo(1)
        }

    @Test
    fun `restore reports per-table counts`() =
        runBlocking {
            db.messageDao().insertAll(listOf(message(1), message(2)))
            db.accountDao().insertAll(
                listOf(
                    app.clearsms.data.db.AccountEntity(
                        id = 1,
                        accountNumber = "1234",
                        bankName = "B",
                        type = app.clearsms.domain.model.AccountType.SAVINGS,
                        lastUpdated = 1,
                    ),
                ),
            )
            val exported = ByteArrayOutputStream()
            manager.exportTo(exported)
            db.messageDao().deleteAll()
            db.accountDao().deleteAll()

            val result = manager.importFrom(ByteArrayInputStream(exported.toByteArray()))

            assertThat(result.messages).isEqualTo(2)
            assertThat(result.accounts).isEqualTo(1)
            assertThat(result.defaultedValues).isEqualTo(0)
            assertThat(result.skippedRows).isEqualTo(0)
            assertThat(db.messageDao().getAll()).hasSize(2)
        }

    @Test
    fun `export contains only user rules`() =
        runBlocking {
            db.ruleDao().insert(builtinRule())
            db.ruleDao().insert(
                builtinRule("mine").copy(source = RuleSources.USER, isUserDefined = true, name = "Mine"),
            )

            val out = ByteArrayOutputStream()
            manager.exportTo(out)
            val text = out.toString()

            assertThat(text).contains("Mine")
            assertThat(text).doesNotContain("generic-otp")
        }
}
