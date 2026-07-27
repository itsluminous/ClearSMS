package app.clearsms.ui.components

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Curated brand identity table (see `rules/brands/brands.json`).
 *
 * Each entry carries only *facts* about a brand — its name, the sender IDs it
 * uses, and its widely-published primary color — never artwork. The app draws
 * an original mark (colored tile + monogram + category glyph) from these
 * facts; no third-party logo files are bundled.
 */
@Serializable
data class Brand(
    val key: String,
    val name: String,
    val category: BrandCategory = BrandCategory.OTHER,
    /** The brand's primary color as `#RRGGBB` — a factual attribute, not artwork. */
    val color: String,
    /** 1–3 character monogram drawn on the tile. */
    val monogram: String,
    /** Exact sender IDs (after TRAI prefix/suffix normalization). */
    val senders: List<String> = emptyList(),
    /** Whole-word aliases matched against resolved display names. */
    val aliases: List<String> = emptyList(),
)

/** Category of a curated brand; drives the small badge glyph on the tile. */
@Serializable
enum class BrandCategory {
    BANK,
    CARD,
    WALLET,
    TELECOM,
    ECOMMERCE,
    DELIVERY,
    GOVERNMENT,
    UTILITY,
    INVESTMENT,
    HEALTH,
    TRAVEL,
    OTHER,
}

@Serializable
data class BrandTable(
    val version: String = "1.0",
    val brands: List<Brand> = emptyList(),
)

/**
 * In-memory index over the bundled brand table: exact sender-ID lookup plus
 * whole-word alias matching against display names. Pure JVM so resolution is
 * unit-testable; [BrandCatalog] owns the singleton loaded from assets.
 */
class BrandIndex(
    val brands: List<Brand>,
) {
    private val bySender: Map<String, Brand> =
        buildMap { brands.forEach { b -> b.senders.forEach { put(it.uppercase(), b) } } }
    private val aliasPairs: List<Pair<Regex, Brand>> =
        brands.flatMap { b ->
            (b.aliases + b.name).map { alias ->
                Regex("(?<![A-Z0-9])${Regex.escape(alias.uppercase())}(?![A-Z0-9])") to b
            }
        }

    /**
     * Resolves [senderOrName] — a raw sender address like `VM-HDFCBK` or an
     * already-resolved display name like `HDFC Bank` — to a curated brand.
     */
    fun resolve(senderOrName: String): Brand? {
        val normalized = normalizeSenderId(senderOrName)
        bySender[normalized]?.let { return it }
        val upper = senderOrName.uppercase()
        aliasPairs
            .firstOrNull { (regex, _) -> regex.containsMatchIn(upper) || regex.containsMatchIn(normalized) }
            ?.let { return it.second }
        return null
    }

    companion object {
        val EMPTY = BrandIndex(emptyList())

        fun parse(json: String): BrandIndex {
            val table = FORMAT.decodeFromString<BrandTable>(json)
            val duplicates =
                table.brands
                    .groupBy { it.key }
                    .filterValues { it.size > 1 }
                    .keys
            require(duplicates.isEmpty()) { "Duplicate brand keys: $duplicates" }
            return BrandIndex(table.brands)
        }

        private val FORMAT = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Strips the TRAI route prefix (`VM-`, `AD-`, …) and trailing route suffix
 * (`-S`, `-T`, `-P`, `-G`) from an alphanumeric sender ID and uppercases it,
 * mirroring how the sender-ID directory normalizes lookups.
 */
fun normalizeSenderId(sender: String): String {
    var s = sender.trim().uppercase()
    if (s.length > 3 && s[2] == '-' && s.take(2).all { it.isLetterOrDigit() }) {
        s = s.substring(3)
    }
    if (s.length > 2 && s[s.length - 2] == '-' && s.last() in "STPG") {
        s = s.dropLast(2)
    }
    return s
}

/** Parses `#RRGGBB` (or `#AARRGGBB`) into a Compose [Color]; null if malformed. */
fun parseBrandColor(hex: String): Color? {
    val digits = hex.removePrefix("#")
    if (digits.length != 6 && digits.length != 8) return null
    val value = digits.toLongOrNull(16) ?: return null
    val argb = if (digits.length == 6) 0xFF000000L or value else value
    return Color(argb.toInt())
}

/** WCAG relative luminance of an sRGB color (0 = black, 1 = white). */
fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** WCAG contrast ratio between two colors (1..21). */
fun contrastRatio(
    a: Color,
    b: Color,
): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val (lighter, darker) = if (la >= lb) la to lb else lb to la
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Picks white or black monogram text for a tile [background], whichever has
 * the higher WCAG contrast ratio — keeps every brand tile AA-legible.
 */
fun monogramColorFor(background: Color): Color =
    if (contrastRatio(background, Color.White) >= contrastRatio(background, Color.Black)) {
        Color.White
    } else {
        Color.Black
    }

/**
 * Tile fill for a brand color: the color itself in light theme; in dark theme
 * a slightly deepened tone of it (a tonal container) so saturated brand hues
 * don't glare against dark surfaces. Text color is still chosen from the
 * *final* fill via [monogramColorFor], so contrast holds in both themes.
 */
fun brandTileColor(
    brandColor: Color,
    darkTheme: Boolean,
): Color =
    if (darkTheme) {
        Color(
            red = brandColor.red * 0.78f,
            green = brandColor.green * 0.78f,
            blue = brandColor.blue * 0.78f,
            alpha = 1f,
        )
    } else {
        brandColor
    }

/** Loads the bundled brand table once per process from `assets/brands.json`. */
object BrandCatalog {
    @Volatile
    private var cached: BrandIndex? = null

    fun get(context: Context): BrandIndex =
        cached ?: synchronized(this) {
            cached ?: load(context).also { cached = it }
        }

    private fun load(context: Context): BrandIndex =
        try {
            val json =
                context.assets
                    .open("brands.json")
                    .bufferedReader()
                    .use { it.readText() }
            BrandIndex.parse(json)
        } catch (_: Exception) {
            // A malformed table must never break avatars — fall back to letters.
            BrandIndex.EMPTY
        }
}
