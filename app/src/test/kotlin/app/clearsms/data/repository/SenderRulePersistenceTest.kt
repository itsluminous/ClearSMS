package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleExporter
import app.clearsms.data.rules.RuleImporter
import app.clearsms.data.rules.RuleSources
import app.clearsms.data.rules.toDefinition
import app.clearsms.domain.rules.SenderRule
import app.clearsms.testing.InMemoryPreferencesDataStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A one-tap sender rule is a plain user rule in the real rules table, so it
 * must survive what user rules survive: the bundled-rules reseed after an app
 * update (which replaces BUILTIN rows wholesale), and a rules export followed
 * by an import on another install.
 */
@RunWith(RobolectricTestRunner::class)
class SenderRulePersistenceTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var loader: BundledRuleLoader
    private lateinit var repository: RuleRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }
    private val dataStore = InMemoryPreferencesDataStore()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        loader = BundledRuleLoader(context, db.ruleDao(), json, dataStore)
        repository = RuleRepositoryImpl(db.ruleDao(), loader, RuleImporter(json), RuleExporter(json), json)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a bundled-rules refresh replaces builtin rules but keeps the sender rule`() =
        runBlocking {
            val rule = SenderRule.definition("AX-AB.CD", "promotional", id = "user_keepme")
            repository.addUserRule(rule)
            // First launch: bundled rules seeded around the user rule.
            val version = loader.ensureLoaded()
            assertThat(version).isNotNull()
            val builtinCount = db.ruleDao().getBySource(RuleSources.BUILTIN).size
            assertThat(builtinCount).isGreaterThan(0)

            // An app update: the recorded version no longer matches the asset,
            // so the loader deletes and reinserts every BUILTIN row.
            dataStore.edit { it[stringPreferencesKey("bundled_rules_version")] = "0.0.0-stale" }
            loader.ensureLoaded()

            assertThat(db.ruleDao().getBySource(RuleSources.BUILTIN)).hasSize(builtinCount)
            val users = db.ruleDao().getBySource(RuleSources.USER)
            assertThat(users).hasSize(1)
            assertThat(users.single().toDefinition(json)?.copy(createdAt = null)).isEqualTo(rule)
            assertThat(repository.observeRules().first().map { it.id }).contains("user_keepme")
        }

    @Test
    fun `the sender rule round-trips through the repository's export and import`() =
        runBlocking {
            val rule = SenderRule.definition("+91 98765 43210", "personal", id = "user_phone")
            repository.addUserRule(rule)

            val exported = repository.exportUserRules()
            repository.deleteRule(rule.id)
            assertThat(db.ruleDao().getBySource(RuleSources.USER)).isEmpty()
            repository.importRules(exported)

            val imported =
                db
                    .ruleDao()
                    .getBySource(RuleSources.USER)
                    .single()
                    .toDefinition(json)!!
            // The importer namespaces ids into the user space; everything else is identical.
            assertThat(imported.copy(id = rule.id, createdAt = null)).isEqualTo(rule)
            assertThat(imported.match.senderPattern).isEqualTo("(?i)9876543210")
        }

    @Test
    fun `deleting the sender rule from the rule manager path removes it like any user rule`() =
        runBlocking {
            val rule = SenderRule.definition("VM-HDFCBK", "otp", id = "user_gone")
            repository.addUserRule(rule)
            repository.deleteRule(rule.id)
            assertThat(db.ruleDao().getBySource(RuleSources.USER)).isEmpty()
        }
}
