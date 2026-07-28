package app.clearsms.data.senderid

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.clearsms.data.rules.RuleDocument
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * India Post identification and the sender-ID corrections layer: the rule
 * must match every observed sender spelling, and the curated corrections
 * asset must beat a wrong bundled directory entry (`INDPST` is mapped to an
 * unrelated business upstream).
 */
@RunWith(RobolectricTestRunner::class)
class SenderIdCorrectionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val rules by lazy {
        json
            .decodeFromString(RuleDocument.serializer(), repoFile("app/src/main/assets/default_rules.json").readText())
            .rules
    }

    private val articleBody = "Article No:UC123456789IN Out for delivery today. Track on indiapost.gov.in - INDPOST"

    @Test
    fun `india post article rule matches the INDPST sender spelling`() {
        val result = RuleEngine().evaluate(rules, "XX-INDPST", articleBody)
        assertThat(result?.matchedRuleId).isEqualTo("indiapost-article-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.DELIVERY)
    }

    @Test
    fun `india post article rule still matches the INDPOST sender spelling`() {
        val result = RuleEngine().evaluate(rules, "VD-INDPOST", articleBody)
        assertThat(result?.matchedRuleId).isEqualTo("indiapost-article-01")
    }

    @Test
    fun `corrections override beats the bundled directory value`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The bundled community directory carries a WRONG entry for INDPST.
        val bundledName =
            SQLiteDatabase
                .openDatabase(
                    repoFile("app/src/main/assets/sender_ids.db").absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { db ->
                    db.rawQuery("SELECT name FROM sender_ids WHERE sender_id = ?", arrayOf("INDPST")).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }
        assertThat(bundledName).isNotEqualTo("India Post")

        // The store must serve the correction instead, TRAI-normalized.
        val info = SenderIdStore(context).lookup("VD-INDPST")
        assertThat(info?.name).isEqualTo("India Post")
        assertThat(info?.category).isEqualTo(Category.IMPORTANT)
        assertThat(info?.sub).isEqualTo("delivery")
    }

    @Test
    fun `corrections asset is identical to the rules master copy`() {
        val master = repoFile("rules/sender_ids/corrections.json").readText()
        val asset = repoFile("app/src/main/assets/sender_id_corrections.json").readText()
        assertThat(asset).isEqualTo(master)
    }

    /** Tests run from the app module dir or the repo root — try both. */
    private fun repoFile(repoRelativePath: String): File =
        sequenceOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File(repoRelativePath.replaceFirst("app/", "")),
        ).first(File::exists)
}
