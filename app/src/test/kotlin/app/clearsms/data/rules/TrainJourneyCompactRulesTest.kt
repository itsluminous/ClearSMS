package app.clearsms.data.rules

import app.clearsms.domain.model.Category
import app.clearsms.domain.model.SubCategory
import app.clearsms.domain.model.date
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The compact CRIS reservation SMS - `PNR-<10>` / `Trn:` / `Dt:` / `Frm X to
 * Y` on separate lines, no departure time - was categorised as travel but by
 * the catch-all IRCTC info rule, which extracts nothing. With no typed
 * `journey_date` there was no Alerts card, so a booked journey never appeared
 * in Alerts. All bodies here are synthetic reconstructions of the shape.
 */
class TrainJourneyCompactRulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Message-date anchor: two-digit journey years resolve against this. */
    private val anchor = LocalDate.of(2026, 8, 23)
    private val engine = RuleEngine()

    private val rules: List<RuleDefinition> by lazy {
        val file =
            listOf(
                File("src/main/assets/default_rules.json"),
                File("app/src/main/assets/default_rules.json"),
            ).firstOrNull { it.exists() }
        checkNotNull(file) { "default_rules.json not found" }
        json.decodeFromString(RuleDocument.serializer(), file.readText()).rules
    }

    private fun evaluate(
        sender: String,
        body: String,
        at: LocalDate = anchor,
    ) = engine.evaluate(rules, sender, body, at)

    private val reservation =
        """
        PNR-1234567890
        Trn:22345
        Dt:24-08-26
        Frm BXR to AY
        Cls:CC
        P1-C6,74
        P2-C6,9
        P3-C5,72
        P4-CNF
        Final status may change after charting
        For Enquiry/Complaint/Assistance,please dial 139 IR-CRIS
        """.trimIndent()

    @Test
    fun `the compact reservation extracts a journey date instead of falling to the info rule`() {
        val result = evaluate("VM-IRCTCI", reservation)

        assertThat(result?.matchedRuleId).isEqualTo("irctc-journey-02")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2026, 8, 24))
    }

    @Test
    fun `train number route and pnr are extracted for the Alerts label`() {
        val result = evaluate("VM-IRCTCI", reservation)

        assertThat(result?.extracted).containsEntry("train", "22345")
        assertThat(result?.extracted).containsEntry("route", "BXR to AY")
        assertThat(result?.extracted).containsEntry("pnr", "1234567890")
        // No departure time in this shape: the label must not invent one.
        assertThat(result?.extracted).doesNotContainKey("departure_time")
    }

    @Test
    fun `a journey later in the year keeps its own date`() {
        val result =
            evaluate(
                "VM-IRCTCI",
                "PNR-2233445566\nTrn:12309\nDt:05-12-26\nFrm NDLS to PNBE\nCls:3A\nP1-B2,15",
            )

        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2026, 12, 5))
    }

    @Test
    fun `a four digit year is accepted too`() {
        val result =
            evaluate(
                "VM-IRCTCI",
                "PNR-3344556677\nTrn:12345\nDt:02-01-2027\nFrm HWH to BBS\nCls:SL\nP1-S4,22",
            )

        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2027, 1, 2))
    }

    @Test
    fun `the older comma separated IRCTC shape still matches its own rule`() {
        // Regression: the new rule must not swallow the DOJ/DP shape, whose
        // extras (departure time) make a richer card.
        val result =
            evaluate(
                "VM-IRCTCI",
                "PNR:9988776655, TRN:12801, DOJ:26-08-26, HWH-NDLS, DP:14:05",
            )

        assertThat(result?.matchedRuleId).isEqualTo("irctc-journey-01")
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2026, 8, 26))
    }

    @Test
    fun `an undated irctc notice still carries no journey date`() {
        // Chart-prepared / enquiry notices must stay out of Alerts.
        val result =
            evaluate(
                "VM-IRCTCI",
                "Chart prepared for PNR 1234567890. Happy journey - IRCTC",
            )

        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
        assertThat(result?.typed?.date("journey_date")).isNull()
    }
}
