package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards the bundled logo artwork in `app/src/main/assets/logos/`: every
 * committed file must map to a curated brand, be accounted for in the
 * provenance manifest, stay within the size budget, and load through a
 * decode-once cache that never crashes on a bad asset.
 */
class BundledLogosTest {
    private val logosDir = BrandResolutionTest.repoFile("app/src/main/assets/logos/MANIFEST.md").parentFile!!
    private val pngs = logosDir.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
    private val brandKeys =
        BrandIndex
            .parse(BrandResolutionTest.bundledBrandsJson())
            .brands
            .map { it.key }
            .toSet()

    @Test
    fun `every bundled asset maps to a curated brand entry`() {
        assertThat(pngs).isNotEmpty()
        val orphans = pngs.map { it.nameWithoutExtension }.filterNot { it in brandKeys }
        assertThat(orphans).isEmpty()
        // Informational: brands still rendered as generated tiles.
        val withAsset = pngs.map { it.nameWithoutExtension }.toSet()
        val uncovered = brandKeys - withAsset
        println("Bundled logo coverage: ${withAsset.size}/${brandKeys.size}; no asset for: ${uncovered.sorted()}")
    }

    @Test
    fun `provenance manifest lists exactly the committed assets with pinned sources`() {
        val manifest = logosDir.resolve("MANIFEST.md").readText()
        val listed =
            Regex("""\| `([a-z0-9]+)\.png` \|""")
                .findAll(manifest)
                .map { it.groupValues[1] }
                .toSet()
        assertThat(listed).isEqualTo(pngs.map { it.nameWithoutExtension }.toSet())
        assertThat(manifest).contains("ad33060ca976397a9fcb46dd40c2d77bce5ce7e1")
        assertThat(manifest).contains("39862391f964bbb263008b5a1d9802be6589864c")
    }

    @Test
    fun `bundled assets respect the size budget`() {
        pngs.forEach { png ->
            assertThat(png.length()).isLessThan(100L * 1024)
            // Committed files must actually be PNG data.
            assertThat(png.readBytes().take(8)).isEqualTo(
                listOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() },
            )
        }
        assertThat(pngs.sumOf { it.length() }).isLessThan(1024L * 1024)
    }

    @Test
    fun `key index takes png files only and ignores the manifest`() {
        val keys = bundledLogoKeys(listOf("hdfc.png", "SBI.PNG", "MANIFEST.md", "notes.txt", "icon.webp"))
        assertThat(keys).containsExactly("hdfc", "sbi")
    }

    @Test
    fun `cache decodes each key exactly once even under concurrency`() {
        val decodes = AtomicInteger()
        val cache =
            BundledLogoCache { key ->
                decodes.incrementAndGet()
                "bitmap:$key"
            }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(16)
        repeat(16) {
            pool.execute {
                start.await()
                assertThat(cache.get("hdfc")).isEqualTo("bitmap:hdfc")
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertThat(decodes.get()).isEqualTo(1)
        assertThat(cache.get("sbi")).isEqualTo("bitmap:sbi")
        assertThat(decodes.get()).isEqualTo(2)
    }

    @Test
    fun `cache memoizes a missing asset without retrying`() {
        val attempts = AtomicInteger()
        val cache =
            BundledLogoCache<String> {
                attempts.incrementAndGet()
                null
            }
        assertThat(cache.get("ghost")).isNull()
        assertThat(cache.get("ghost")).isNull()
        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    fun `a corrupt asset falls back to null instead of crashing`() {
        val cache = BundledLogoCache<String> { error("corrupt PNG") }
        assertThat(cache.get("bad")).isNull()
        assertThat(cache.get("bad")).isNull()
    }

    @Test
    fun `app declares no network permission - logos can never be fetched at runtime`() {
        val manifest = BrandResolutionTest.repoFile("app/src/main/AndroidManifest.xml").readText()
        assertThat(manifest).doesNotContain("android.permission.INTERNET")
        assertThat(manifest).doesNotContain("ACCESS_NETWORK_STATE")
    }
}
