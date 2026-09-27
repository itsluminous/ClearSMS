package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.domain.categorizer.SenderIdLookup
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The LIVE receive path stores the sender's network timestamp beside the
 * received time (GitHub #45): a real SMSC value lands as `dateSent`, a
 * 0/absent one is stored as unknown (null) - never as the epoch and never
 * substituted with the received time - and the received `timestamp` is
 * untouched either way. Synthetic fixtures only.
 */
@RunWith(RobolectricTestRunner::class)
class IngestSentTimeTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
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
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopDataStore),
                json = json,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a reported SMSC timestamp is stored as dateSent, apart from the received time`() =
        runBlocking {
            val stored =
                repository
                    .ingestIncoming(
                        "5550100",
                        "arrived after a signal gap",
                        timestampMs = 1_700_000_060_000,
                        systemSmsId = 1L,
                        dateSentMs = 1_700_000_000_000,
                    ).entity

            val row = db.messageDao().getAll().single { it.id == stored.id }
            assertThat(row.timestamp).isEqualTo(1_700_000_060_000)
            assertThat(row.dateSent).isEqualTo(1_700_000_000_000)
        }

    @Test
    fun `an unreported (0) or absent SMSC timestamp is stored as unknown, not invented`() =
        runBlocking {
            val zero =
                repository.ingestIncoming("5550100", "zero from the pdu", 1_700_000_001_000, 1L, dateSentMs = 0L).entity
            val absent =
                repository.ingestIncoming("5550100", "nothing at all", 1_700_000_002_000, 2L).entity

            val rows = db.messageDao().getAll().associateBy { it.id }
            assertThat(rows.getValue(zero.id).dateSent).isNull()
            assertThat(rows.getValue(absent.id).dateSent).isNull()
            // The received time is never touched by the missing sent time.
            assertThat(rows.getValue(zero.id).timestamp).isEqualTo(1_700_000_001_000)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
