package app.clearsms.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.RuleEntity
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleExporter
import app.clearsms.data.rules.RuleImporter
import app.clearsms.data.rules.RuleSources
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

@RunWith(RobolectricTestRunner::class)
class RuleIdentityTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var repository: RuleRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, ClearSmsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            RuleRepositoryImpl(
                ruleDao = db.ruleDao(),
                bundledRuleLoader = BundledRuleLoader(context, db.ruleDao(), json, NoopDataStore),
                ruleImporter = RuleImporter(json),
                ruleExporter = RuleExporter(json),
                json = json,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun builtinRule(id: String) =
        RuleEntity(
            id = id,
            name = "Builtin $id",
            priority = 100,
            matchJson = """{"body_must_contain":["otp"]}""",
            actionJson = """{"category":"otp"}""",
            isUserDefined = false,
            source = RuleSources.BUILTIN,
            createdAt = 0L,
        )

    @Test
    fun `imported rule with a builtin id does not overwrite the builtin`() =
        runBlocking {
            db.ruleDao().insert(builtinRule("generic-otp"))

            repository.importRules(
                """
                {"version":"1.0","rules":[
                  {"id":"generic-otp","priority":9999,
                   "match":{"body_must_contain":["free"]},
                   "action":{"category":"promotional"}}]}
                """.trimIndent(),
            )

            val rules = db.ruleDao().getAll()
            val builtin = rules.single { it.id == "generic-otp" }
            assertThat(builtin.name).isEqualTo("Builtin generic-otp")
            assertThat(builtin.source).isEqualTo(RuleSources.BUILTIN)
            val imported = rules.single { it.id == "user:generic-otp" }
            assertThat(imported.source).isEqualTo(RuleSources.USER)
            assertThat(imported.isUserDefined).isTrue()
        }

    @Test
    fun `already namespaced ids are not double-prefixed on import`() =
        runBlocking {
            repository.importRules(
                """
                {"version":"1.0","rules":[
                  {"id":"user:mine","priority":1,
                   "match":{"body_must_contain":["x"]},
                   "action":{"category":"personal"}}]}
                """.trimIndent(),
            )

            assertThat(
                db
                    .ruleDao()
                    .getAll()
                    .single()
                    .id,
            ).isEqualTo("user:mine")
        }

    @Test
    fun `enable-disable round trip preserves the rule row and its source`() =
        runBlocking {
            db.ruleDao().insert(builtinRule("generic-otp"))

            repository.setRuleEnabled("generic-otp", false)
            var rule = db.ruleDao().getAll().single()
            assertThat(rule.enabled).isFalse()
            assertThat(rule.source).isEqualTo(RuleSources.BUILTIN)
            assertThat(rule.isUserDefined).isFalse()

            repository.setRuleEnabled("generic-otp", true)
            rule = db.ruleDao().getAll().single()
            assertThat(rule.enabled).isTrue()
            assertThat(rule.source).isEqualTo(RuleSources.BUILTIN)
        }

    @Test
    fun `disabled rules are excluded from what the engine evaluates`() =
        runBlocking {
            db.ruleDao().insert(builtinRule("a"))
            db.ruleDao().insert(builtinRule("b"))

            repository.setRuleEnabled("a", false)

            val evaluated = db.ruleDao().getEnabledBySource(RuleSources.BUILTIN)
            assertThat(evaluated.map { it.id }).containsExactly("b")
            // getBySource still returns everything (exports, management UI).
            assertThat(db.ruleDao().getBySource(RuleSources.BUILTIN)).hasSize(2)
        }

    private object NoopDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = emptyPreferences()
    }
}
