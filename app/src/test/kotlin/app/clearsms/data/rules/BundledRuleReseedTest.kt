package app.clearsms.data.rules

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.RuleEntity
import app.clearsms.testing.InMemoryPreferencesDataStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bundled reseed (app update shipping new rules) against a table that
 * already holds the user's own rules and their disabled choices. Issue #43's
 * two symptoms both trace to this code path: a rule collision would destroy
 * a user rule via REPLACE, and a rule added without a version bump (0.20.0's
 * `atm-withdrawal-01`) was never loaded for updating users.
 */
@RunWith(RobolectricTestRunner::class)
class BundledRuleReseedTest {
    private lateinit var db: ClearSmsDatabase
    private lateinit var loader: BundledRuleLoader
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
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun bundled(
        id: String,
        category: String = "otp",
    ) = RuleDefinition(id = id, name = "Bundled $id", priority = 100, action = RuleAction(category = category))

    private fun userRow(id: String) =
        RuleEntity(
            id = id,
            name = "My $id",
            priority = 900,
            matchJson = """{"body_pattern":"withdrawn"}""",
            actionJson = """{"category":"important","sub_category":"transaction"}""",
            isUserDefined = true,
            source = RuleSources.USER,
            createdAt = 0L,
        )

    private fun document(
        version: String,
        vararg rules: RuleDefinition,
    ): String = json.encodeToString(RuleDocument.serializer(), RuleDocument(version, rules.toList()))

    @Test
    fun `reseed never destroys a user rule whose id a bundled rule takes - user first`() =
        runBlocking {
            db.ruleDao().insert(userRow("shared-id"))

            loader.reseed(RuleDocument("1.0", listOf(bundled("shared-id"), bundled("other"))))

            val rows = db.ruleDao().getAll()
            val mine = rows.single { it.id == "shared-id" }
            assertThat(mine.source).isEqualTo(RuleSources.USER)
            assertThat(mine.name).isEqualTo("My shared-id")
            assertThat(mine.matchJson).contains("withdrawn")
            assertThat(rows.map { it.id }).containsExactly("shared-id", "other")
            assertThat(rows.single { it.id == "other" }.source).isEqualTo(RuleSources.BUILTIN)
        }

    @Test
    fun `reseed never destroys a user rule whose id a bundled rule takes - bundled first`() =
        runBlocking {
            loader.reseed(RuleDocument("1.0", listOf(bundled("shared-id"))))
            // The legacy re-enable path (and REPLACE insert) can turn that id
            // into a USER row; the next reseed must then leave it alone.
            db.ruleDao().insert(userRow("shared-id"))
            assertThat(
                db
                    .ruleDao()
                    .getAll()
                    .single()
                    .source,
            ).isEqualTo(RuleSources.USER)

            loader.reseed(RuleDocument("1.1", listOf(bundled("shared-id"))))

            val row = db.ruleDao().getAll().single()
            assertThat(row.source).isEqualTo(RuleSources.USER)
            assertThat(row.name).isEqualTo("My shared-id")
            // Not a half-replaced row: both JSON halves are still the user's.
            assertThat(row.actionJson).contains("transaction")
        }

    @Test
    fun `reseed keeps a builtin the user disabled disabled`() =
        runBlocking {
            loader.reseed(RuleDocument("1.0", listOf(bundled("noisy"), bundled("fine"))))
            db.ruleDao().setEnabled("noisy", false)

            loader.reseed(RuleDocument("1.1", listOf(bundled("noisy", category = "spam"), bundled("fine"))))

            val rows = db.ruleDao().getAll().associateBy { it.id }
            assertThat(rows.getValue("noisy").enabled).isFalse()
            // Content still refreshed to the shipped version.
            assertThat(rows.getValue("noisy").actionJson).contains("spam")
            assertThat(rows.getValue("fine").enabled).isTrue()
            assertThat(db.ruleDao().getEnabledBySource(RuleSources.BUILTIN).map { it.id }).containsExactly("fine")
            assertThat(rows.getValue("noisy").source).isEqualTo(RuleSources.BUILTIN)
        }

    @Test
    fun `reseed keeps a builtin a pre-0_21 version parked in the UI store disabled`() =
        runBlocking {
            // 0.20.0 disabled a rule by deleting its row and parking
            // "builtin|<definition>" in the UI preferences. The update that
            // introduces the flag reseeds first: that reseed must not switch
            // the parked rule back on (which the update would otherwise do
            // silently for every rule the user had turned off).
            val uiStore = InMemoryPreferencesDataStore()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val loader = BundledRuleLoader(context, db.ruleDao(), json, dataStore, uiStore)
            val noisy = bundled("noisy")
            uiStore.edit { prefs ->
                prefs[stringSetPreferencesKey("disabled_rules")] =
                    setOf(
                        "builtin|" + json.encodeToString(RuleDefinition.serializer(), noisy),
                        "user|" + json.encodeToString(RuleDefinition.serializer(), bundled("fine")),
                        "garbage-without-json",
                    )
            }

            loader.reseed(RuleDocument("1.1", listOf(noisy, bundled("fine"))))

            val rows = db.ruleDao().getAll().associateBy { it.id }
            assertThat(rows.getValue("noisy").enabled).isFalse()
            // Only "builtin|" entries count; a user-parked copy of a bundled id does not.
            assertThat(rows.getValue("fine").enabled).isTrue()
            assertThat(db.ruleDao().getEnabledBySource(RuleSources.BUILTIN).map { it.id }).containsExactly("fine")
            assertThat(rows.getValue("noisy").source).isEqualTo(RuleSources.BUILTIN)
        }

    @Test
    fun `reseed replaces stale builtins and drops ones no longer shipped`() =
        runBlocking {
            loader.reseed(RuleDocument("1.0", listOf(bundled("gone"), bundled("kept"))))

            loader.reseed(RuleDocument("1.1", listOf(bundled("kept"), bundled("new"))))

            assertThat(db.ruleDao().getBySource(RuleSources.BUILTIN).map { it.id }).containsExactly("kept", "new")
            assertThat(db.ruleDao().getAll()).hasSize(2)
        }

    @Test
    fun `a changed asset reseeds even when the version string was not bumped`() =
        runBlocking {
            // 0.19.1 -> 0.20.0 shipped atm-withdrawal-01 under the same "1.3",
            // so ensureLoaded() short-circuited and the rule never reached
            // updating users' tables (their transactions stayed missed).
            loader.ensureLoaded(document("1.3", bundled("generic-otp")))
            assertThat(db.ruleDao().getAll().map { it.id }).containsExactly("generic-otp")

            loader.ensureLoaded(document("1.3", bundled("generic-otp"), bundled("atm-withdrawal-01")))

            assertThat(db.ruleDao().getAll().map { it.id }).containsExactly("generic-otp", "atm-withdrawal-01")
            assertThat(loader.loadedVersion()).isEqualTo("1.3")
        }

    @Test
    fun `an unchanged asset does not reseed`() =
        runBlocking {
            val text = document("1.3", bundled("generic-otp"))
            loader.ensureLoaded(text)
            db.ruleDao().setEnabled("generic-otp", false)
            val before = db.ruleDao().getAll()

            assertThat(loader.ensureLoaded(text)).isEqualTo("1.3")

            assertThat(db.ruleDao().getAll()).isEqualTo(before)
        }

    @Test
    fun `a legacy install with only a version key reseeds once then settles`() =
        runBlocking {
            // Installs from before the fingerprint existed have the version
            // key alone; the first run on this build repairs the table once.
            dataStore.edit { it[stringPreferencesKey("bundled_rules_version")] = "1.3" }
            val text = document("1.3", bundled("generic-otp"))

            loader.ensureLoaded(text)
            assertThat(db.ruleDao().getAll()).hasSize(1)

            db.ruleDao().insert(userRow("mine"))
            db.ruleDao().setEnabled("generic-otp", false)
            loader.ensureLoaded(text)
            val rows = db.ruleDao().getAll().associateBy { it.id }
            assertThat(rows.keys).containsExactly("generic-otp", "mine")
            // No-op: the disabled flag was not reset by a spurious reseed.
            assertThat(rows.getValue("generic-otp").enabled).isFalse()
        }
}
