package app.clearsms.domain.parser

import app.clearsms.domain.model.MerchantCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * The bundled parser tables: every `app/src/main/assets/tables/` copy must be
 * byte-identical to its `rules/tables/` community master, each table must
 * load from the classpath and yield the same mappings the old Kotlin
 * constants did, and a malformed or missing table must degrade to an empty
 * table without crashing.
 */
class ParserTablesTest {
    // region rules/ <-> assets identity

    @Test
    fun `merchant category master and bundled copy are identical`() {
        assertIdentical("merchant_categories.json")
    }

    @Test
    fun `courier master and bundled copy are identical`() {
        assertIdentical("couriers.json")
    }

    @Test
    fun `biller master and bundled copy are identical`() {
        assertIdentical("billers.json")
    }

    private fun assertIdentical(name: String) {
        val master = repoFile("rules/tables/$name")
        val bundled = repoFile("app/src/main/assets/tables/$name")
        assertThat(bundled.readText()).isEqualTo(master.readText())
    }

    // endregion

    // region merchant categories

    @Test
    fun `merchant category table loads and yields the same mapping the constant did`() {
        fun categoryFor(text: String): MerchantCategory? =
            ParserTables.merchantCategories.firstOrNull { (regex, _) -> regex.containsMatchIn(text) }?.second

        assertThat(categoryFor("swiggy order")).isEqualTo(MerchantCategory.FOOD)
        assertThat(categoryFor("zomato")).isEqualTo(MerchantCategory.FOOD)
        assertThat(categoryFor("amazon pay")).isEqualTo(MerchantCategory.SHOPPING)
        assertThat(categoryFor("uber ride")).isEqualTo(MerchantCategory.TRANSPORTATION)
        assertThat(categoryFor("makemytrip booking")).isEqualTo(MerchantCategory.TRAVEL_HOTEL)
        assertThat(categoryFor("netflix renewal")).isEqualTo(MerchantCategory.ENTERTAINMENT)
        assertThat(categoryFor("school fees paid")).isEqualTo(MerchantCategory.EDUCATION)
        assertThat(categoryFor("apollo pharmacy")).isEqualTo(MerchantCategory.HOSPITAL)
        assertThat(categoryFor("electricity board")).isEqualTo(MerchantCategory.UTILITY_BILL)
        assertThat(categoryFor("rd installment")).isEqualTo(MerchantCategory.INVESTMENT)
        assertThat(categoryFor("nps contribution towards pran")).isEqualTo(MerchantCategory.INVESTMENT)
        assertThat(categoryFor("recharged successfully")).isEqualTo(MerchantCategory.RECHARGE)
        // Word boundaries survive the trip through JSON: "uber" must not
        // fire inside "tuberculosis".
        assertThat(categoryFor("tuberculosis fund")).isNull()
    }

    @Test
    fun `merchant category rows keep their first-match order`() {
        // "swiggy" is both FOOD (row 1) and could lexically match nothing
        // else; more importantly a haystack matching two rows must resolve
        // to the EARLIER row, as the old listOf(...) did.
        val haystack = "swiggy amazon" // FOOD row precedes SHOPPING row
        val hit = ParserTables.merchantCategories.first { (regex, _) -> regex.containsMatchIn(haystack) }
        assertThat(hit.second).isEqualTo(MerchantCategory.FOOD)
    }

    @Test
    fun `malformed merchant category json degrades to an empty table`() {
        assertThat(ParserTables.parseMerchantCategories("{ not json")).isEmpty()
        assertThat(ParserTables.parseMerchantCategories(null)).isEmpty()
    }

    @Test
    fun `invalid category or pattern rows are skipped not fatal`() {
        val json =
            """{"version":"1.0","categories":[
              {"pattern":"(?i)swiggy","category":"FOOD"},
              {"pattern":"(?i)broken(","category":"FOOD"},
              {"pattern":"(?i)fine","category":"NO_SUCH_CATEGORY"}
            ]}"""
        val rows = ParserTables.parseMerchantCategories(json)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().second).isEqualTo(MerchantCategory.FOOD)
    }

    // endregion

    // region couriers

    @Test
    fun `courier table loads and yields the same merchant mapping the constant did`() {
        val merchants = ParserTables.couriers.merchants.toMap()
        assertThat(merchants["AMAZON"]).isEqualTo("Amazon")
        assertThat(merchants["AMZN"]).isEqualTo("Amazon")
        assertThat(merchants["BLUDRT"]).isEqualTo("Blue Dart")
        assertThat(merchants["INDIAPOST"]).isEqualTo("India Post")
        assertThat(merchants["DOMINO"]).isEqualTo("Domino's")
        assertThat(merchants["CROMA"]).isEqualTo("Croma")
        assertThat(ParserTables.couriers.merchants).hasSize(33)

        val domains = ParserTables.couriers.brandDomains.toMap()
        assertThat(domains["croma.com"]).isEqualTo("Croma")
        assertThat(domains["indiapost.gov.in"]).isEqualTo("India Post")
        assertThat(ParserTables.couriers.brandDomains).hasSize(9)
    }

    @Test
    fun `malformed courier json degrades to an empty table`() {
        val empty = ParserTables.parseCouriers("""{"merchants": "wrong shape"}""")
        assertThat(empty.merchants).isEmpty()
        assertThat(empty.brandDomains).isEmpty()
        assertThat(ParserTables.parseCouriers(null).merchants).isEmpty()
    }

    // endregion

    // region billers

    @Test
    fun `assembled biller sender regex matches what the literal matched`() {
        val regex = ParserTables.billers.knownBillerSenderRegex
        // Escaped-literal alternation, case-insensitive, substring semantics.
        assertThat(regex.containsMatchIn("AX-AIRBIL")).isTrue()
        assertThat(regex.containsMatchIn("BESCOM")).isTrue()
        assertThat(regex.containsMatchIn("jd-bses-s")).isTrue()
        assertThat(regex.containsMatchIn("TORRNT")).isTrue()
        assertThat(regex.containsMatchIn("HDFCBK")).isFalse()
    }

    @Test
    fun `assembled insurer regex matches what the literal matched`() {
        val regex = ParserTables.billers.insurerNameRegex
        assertThat(regex.containsMatchIn("your LIC policy")).isTrue()
        // \s* fragment: "ICICIPru" (no space) must still match.
        assertThat(regex.containsMatchIn("ICICIPru policy")).isTrue()
        assertThat(regex.containsMatchIn("HDFC Life premium")).isTrue()
        assertThat(regex.containsMatchIn("Bajaj Allianz renewal")).isTrue()
        // Word boundary survives: "LIC" must not fire inside "SLICE" or "policy".
        assertThat(regex.containsMatchIn("SLICE card policy detail")).isFalse()
    }

    @Test
    fun `assembled bill domain regex matches what the literal matched`() {
        val regex = ParserTables.billers.billDomainRegex
        assertThat(regex.containsMatchIn("your postpaid bill")).isTrue()
        assertThat(regex.containsMatchIn("electricity dues")).isTrue()
        assertThat(regex.containsMatchIn("DTH recharge")).isTrue()
        assertThat(regex.containsMatchIn("maintenance fee")).isTrue()
        // "gas" is word-bounded: "Vegas" must not match.
        assertThat(regex.containsMatchIn("Vegas trip")).isFalse()
    }

    @Test
    fun `malformed biller json degrades to never-matching regexes`() {
        val empty = ParserTables.parseBillers("[1,2,3]")
        assertThat(empty.knownBillerSenderRegex.containsMatchIn("AIRBIL")).isFalse()
        assertThat(empty.insurerNameRegex.containsMatchIn("LIC")).isFalse()
        assertThat(ParserTables.parseBillers(null).billDomainRegex.containsMatchIn("postpaid")).isFalse()
    }

    @Test
    fun `empty alternation never matches and bad fragments are dropped`() {
        assertThat(ParserTables.assembleAlternation(emptyList()).containsMatchIn("anything")).isFalse()
        assertThat(ParserTables.assembleAlternation(emptyList()).containsMatchIn("")).isFalse()
        // One broken fragment must not take down its siblings.
        val partial = ParserTables.assembleAlternation(listOf("broken(", "\\bLIC\\b"))
        assertThat(partial.containsMatchIn("LIC policy")).isTrue()
        assertThat(partial.containsMatchIn("broken(")).isFalse()
    }

    @Test
    fun `missing classpath resource yields null without crashing`() {
        assertThat(ParserTables.readResource("no_such_table.json")).isNull()
    }

    // endregion

    private fun repoFile(repoRelativePath: String): File =
        sequenceOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File(repoRelativePath.removePrefix("app/")),
        ).first(File::exists)
}
