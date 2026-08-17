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
 * The bundled FLIGHT rules extract a typed `journey_date` (plus route, PNR,
 * flight number and departure time where the shape carries them), so flight
 * bookings surface in Alerts through the same TRAVEL path the train rules
 * already use. All bodies are synthetic reconstructions of real airline SMS
 * shapes.
 */
class FlightJourneyRulesTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Fixed message-date anchor so the yearless "12Aug" itinerary date resolves deterministically. */
    private val anchor = LocalDate.of(2026, 8, 10)
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
    ) = engine.evaluate(rules, sender, body, anchor)

    @Test
    fun `booking confirmation extracts journey date route flight and departure time`() {
        val result =
            evaluate(
                "INDIGO",
                "Dear Mr Kumar, we are happy to confirm your booking under PNR - ZXCHRQ, " +
                    "10 Jun 26, from PAT to BLR(T1), 6E2345 at 10:30 hrs.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-booking-pnr-01")
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
        assertThat(result?.extracted).containsEntry("pnr", "ZXCHRQ")
        assertThat(result?.extracted).containsEntry("route", "PAT to BLR(T1)")
        assertThat(result?.extracted).containsEntry("flight", "6E2345")
        assertThat(result?.extracted).containsEntry("departure_time", "10:30")
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2026, 6, 10))
    }

    @Test
    fun `trailing-PNR booking confirmation extracts the same journey details`() {
        val result =
            evaluate(
                "INDIGO",
                "Hello Mr Kumar, we're happy to confirm your booking. PNR - TMQDXY, " +
                    "12 Aug 26, from BLR(T1) to PAT, 6E 234 at 08:15 hrs.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-booking-pnr-02")
        assertThat(result?.extracted).containsEntry("route", "BLR(T1) to PAT")
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2026, 8, 12))
    }

    @Test
    fun `booking with airport-pair route and month-name date extracts a journey`() {
        val result =
            evaluate(
                "AIRIND",
                "Air India Your booking is confirmed. PNR AB1CD2, DEL-BOM, 15 Aug 26, " +
                    "06.40 dep. Fly AI 2015. Web check-in at airindia.com",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-booking-route-doj-01")
        assertThat(result?.category).isEqualTo(Category.IMPORTANT)
        assertThat(result?.subCategory).isEqualTo(SubCategory.TRAVEL)
        assertThat(result?.extracted).containsEntry("pnr", "AB1CD2")
        assertThat(result?.extracted).containsEntry("route", "DEL-BOM")
        assertThat(result?.extracted).containsEntry("departure_time", "06.40")
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2026, 8, 15))
    }

    @Test
    fun `booking with numeric journey date parses the DD-MM-YYYY variant`() {
        val result =
            evaluate(
                "VSTARA",
                "Vistara Your ticket stands confirmed. PNR ZZ9YX8, BOM-DEL, 15-08-2026, 18.25 departure.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-booking-route-doj-01")
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2026, 8, 15))
    }

    @Test
    fun `web check-in itinerary extracts the yearless journey day on or after the message date`() {
        val result =
            evaluate(
                "INDIGO",
                "IndiGo: Dear flyer, your IndiGo PNR is HQBRXE - 6E 1234, 12Aug,PAT-BLR(T1) " +
                    "0915-1200 HRS. For a hassle-free airport experience, please web check-in.",
            )
        assertThat(result?.matchedRuleId).isEqualTo("flight-itinerary-pnr-01")
        assertThat(result?.extracted).containsEntry("flight", "6E 1234")
        assertThat(result?.extracted).containsEntry("route", "PAT-BLR(T1)")
        assertThat(result?.extracted).containsEntry("journey_date", "12Aug")
        // Anchor (message date) is 2026-08-10, so "12Aug" resolves to
        // 2026-08-12 - the first 12-Aug on or after the message.
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2026, 8, 12))
    }

    @Test
    fun `itinerary received in Dec-2024 dates its yearless journey day 2024 - never a phantom future year`() {
        // Real defect: an IndiGo SMS RECEIVED 11-Dec-2024 saying "11Dec,
        // PAT-BLR" produced a travel card dated 11-Dec-2026 because the
        // yearless inference anchored on the clock at re-sort time. Anchored
        // on the message date it stays 2024 - already expired, so the card
        // lands in Older instead of surfacing as an upcoming 2026 alert.
        val result =
            engine.evaluate(
                rules,
                "INDIGO",
                "IndiGo: Dear flyer, your IndiGo PNR is HQBRXE - 6E 1234, 11Dec,PAT-BLR(T1) " +
                    "0915-1200 HRS. For a hassle-free airport experience, please web check-in.",
                LocalDate.of(2024, 12, 11),
            )
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2024, 12, 11))
    }

    @Test
    fun `late-December itinerary referring to early January resolves to the NEXT year`() {
        val result =
            engine.evaluate(
                rules,
                "INDIGO",
                "IndiGo: Dear flyer, your IndiGo PNR is HQBRXE - 6E 1234, 2Jan,PAT-BLR(T1) " +
                    "0915-1200 HRS. For a hassle-free airport experience, please web check-in.",
                LocalDate.of(2024, 12, 28),
            )
        assertThat(result?.typed?.date("journey_date")).isEqualTo(LocalDate.of(2025, 1, 2))
    }

    @Test
    fun `itinerary without journey details still matches on the PNR alone`() {
        val result = evaluate("INDIGO", "Dear flyer, your IndiGo PNR is ABCDE1 - see the app for details.")
        assertThat(result?.matchedRuleId).isEqualTo("flight-itinerary-pnr-01")
        assertThat(result?.extracted).containsEntry("pnr", "ABCDE1")
        assertThat(result?.extracted).doesNotContainKey("journey_date")
    }

    @Test
    fun `gate and terminal notices stay info-only with no journey date`() {
        val gate =
            evaluate(
                "TRPSRC",
                "TripSource: The gate has changed for your flight to Hyderabad (6E 1234). " +
                    "It is now departing from Terminal 1, Gate 22.",
            )
        assertThat(gate?.matchedRuleId).isEqualTo("flight-gate-change-01")
        assertThat(gate?.extracted.orEmpty()).doesNotContainKey("journey_date")
        val boarding =
            evaluate("INDIGO", "Your flight 6E 431 boards at gate 14. Boarding closes 25 minutes before departure.")
        assertThat(boarding?.subCategory).isEqualTo(SubCategory.TRAVEL)
        assertThat(boarding?.extracted.orEmpty()).doesNotContainKey("journey_date")
    }
}
