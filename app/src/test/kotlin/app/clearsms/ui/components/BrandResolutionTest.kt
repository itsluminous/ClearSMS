package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Resolution tests run against the real bundled table so coverage claims in
 * the brand table stay honest.
 */
class BrandResolutionTest {
    private val index = BrandIndex.parse(bundledBrandsJson())

    @Test
    fun `resolves TRAI prefixed sender ids`() {
        assertThat(index.resolve("VM-HDFCBK")?.key).isEqualTo("hdfc")
        assertThat(index.resolve("AD-AMAZON")?.key).isEqualTo("amazon")
        assertThat(index.resolve("AX-ICICIB")?.key).isEqualTo("icici")
    }

    @Test
    fun `resolves plain sender ids and route suffixes`() {
        assertThat(index.resolve("HDFCBK")?.key).isEqualTo("hdfc")
        assertThat(index.resolve("JD-SBIINB-S")?.key).isEqualTo("sbi")
        assertThat(index.resolve("vm-paytmb")?.key).isEqualTo("paytm")
    }

    @Test
    fun `resolves display names through aliases`() {
        assertThat(index.resolve("HDFC Bank")?.key).isEqualTo("hdfc")
        assertThat(index.resolve("Axis Bank")?.key).isEqualTo("axis")
        assertThat(index.resolve("State Bank of India")?.key).isEqualTo("sbi")
        assertThat(index.resolve("Blue Dart")?.key).isEqualTo("bluedart")
    }

    @Test
    fun `alias matching is whole word - no false positives`() {
        // "UNION" must not fire inside "REUNION EVENTS".
        assertThat(index.resolve("REUNIONS")).isNull()
        assertThat(index.resolve("Union Bank of India")?.key).isEqualTo("union")
    }

    @Test
    fun `unknown alphanumeric sender resolves to nothing`() {
        assertThat(index.resolve("AIRSVD")).isNull()
        assertThat(index.resolve("ZX-QQWWEE")).isNull()
    }

    @Test
    fun `the two NPS CRAs keep their own avatar brands`() {
        // The resolver unifies both CRAs on the ONE "NPS" institution, but
        // each keeps its own avatar identity: a KFNCRA message shows the
        // KFintech NPS tile, a PTNNPS one the Protean NPS tile.
        assertThat(index.resolve("VM-KFNCRA-S")?.key).isEqualTo("kfintech")
        assertThat(index.resolve("VM-KFNCRA-S")?.name).isEqualTo("KFintech NPS")
        assertThat(index.resolve("VA-PTNNPS-S")?.key).isEqualTo("protean")
    }

    @Test
    fun `normalizeSenderId strips TRAI prefix and suffix`() {
        assertThat(normalizeSenderId("VM-HDFCBK")).isEqualTo("HDFCBK")
        assertThat(normalizeSenderId("JD-SBIINB-S")).isEqualTo("SBIINB")
        assertThat(normalizeSenderId("hdfcbk")).isEqualTo("HDFCBK")
        // Phone numbers are left intact apart from uppercasing.
        assertThat(normalizeSenderId("+919812345678")).isEqualTo("+919812345678")
    }

    @Test
    fun `table parses with no duplicate keys and valid entries`() {
        val brands = index.brands
        assertThat(brands.size).isAtLeast(40)
        assertThat(brands.map { it.key }.toSet()).hasSize(brands.size)
        brands.forEach { brand ->
            assertThat(parseBrandColor(brand.color)).isNotNull()
            assertThat(brand.monogram.length).isIn(1..3)
            assertThat(brand.senders).isNotEmpty()
        }
    }

    @Test
    fun `parse rejects duplicate keys`() {
        val json =
            """{"version":"1.0","brands":[
              {"key":"x","name":"X","color":"#000000","monogram":"X","senders":["XX"]},
              {"key":"x","name":"X2","color":"#111111","monogram":"Y","senders":["YY"]}
            ]}"""
        val error = runCatching { BrandIndex.parse(json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `community master copy and bundled asset are identical`() {
        val master = repoFile("rules/brands/brands.json")
        val asset = repoFile("app/src/main/assets/brands.json")
        assertThat(asset.readText()).isEqualTo(master.readText())
    }

    companion object {
        /** Locates a repo file whether the test runs from the module or repo root. */
        fun repoFile(repoRelativePath: String): File {
            val candidates =
                listOf(
                    File(repoRelativePath),
                    File("..", repoRelativePath),
                    File(repoRelativePath.removePrefix("app/")),
                )
            return candidates.firstOrNull { it.isFile }
                ?: error("Cannot locate $repoRelativePath from ${File(".").absolutePath}")
        }

        fun bundledBrandsJson(): String = repoFile("app/src/main/assets/brands.json").readText()
    }
}
