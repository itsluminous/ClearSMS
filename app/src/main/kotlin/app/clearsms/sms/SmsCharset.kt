package app.clearsms.sms

import java.text.Normalizer

/**
 * The GSM 03.38 / 3GPP TS 23.038 default 7-bit alphabet. One accented
 * character OUTSIDE this table silently flips a whole SMS from GSM-7
 * (160 chars, 153 per multipart segment) to UCS-2 (70 chars, 67 per
 * segment), so a medium text becomes 3-5 billable messages (GitHub #17).
 *
 * Deliberately a real table, not "is it ASCII?": GSM-7 contains non-ASCII
 * letters (Ä Ö Ñ Ü é è ù ì ò à Å å Æ æ ß É Ç Ø ø, capital Greek), and
 * ASCII contains characters GSM-7 charges double for (the extended table).
 */
object GsmAlphabet {
    // The basic character set: 1 septet each. The escape slot (0x1B) is
    // omitted - it is a mechanism, not a text character.
    private const val BASIC: String =
        "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    // The extended table: each costs 2 septets (escape + character).
    private const val EXTENDED: String = "\u000C^{}\\[~]|€"

    private val basicSet: Set<Char> = BASIC.toSet()
    private val extendedSet: Set<Char> = EXTENDED.toSet()

    /** Whether [ch] is sendable in GSM-7 at all (basic or extended table). */
    fun isGsm(ch: Char): Boolean = ch in basicSet || ch in extendedSet

    /** Whether [ch] is in the basic table (1 septet). */
    fun isBasic(ch: Char): Boolean = ch in basicSet

    /**
     * Septet count of [text] under GSM-7, or null when any character falls
     * outside the alphabet (the message would be sent as UCS-2 instead).
     */
    fun septets(text: String): Int? {
        var count = 0
        for (ch in text) {
            count +=
                when {
                    ch in basicSet -> 1
                    ch in extendedSet -> 2
                    else -> return null
                }
        }
        return count
    }
}

/**
 * Billable segment math for an SMS body - the number the carrier charges
 * for. GSM-7: 160 septets in a single message, 153 per segment once
 * multipart (the UDH concatenation header eats 7). UCS-2: 70 UTF-16 code
 * units single, 67 per segment multipart - so an emoji (a surrogate pair)
 * counts as 2.
 */
object SmsSegments {
    private const val GSM_SINGLE = 160
    private const val GSM_MULTI = 153
    private const val UCS2_SINGLE = 70
    private const val UCS2_MULTI = 67

    /** Billable segments for [text]; 0 for an empty body. */
    fun count(text: String): Int {
        if (text.isEmpty()) return 0
        val septets = GsmAlphabet.septets(text)
        return if (septets != null) {
            if (septets <= GSM_SINGLE) 1 else ceilDiv(septets, GSM_MULTI)
        } else {
            // UCS-2 counts UTF-16 code units, not code points.
            if (text.length <= UCS2_SINGLE) 1 else ceilDiv(text.length, UCS2_MULTI)
        }
    }

    /** True when [text] sends as GSM-7; false means UCS-2. */
    fun isGsmText(text: String): Boolean = GsmAlphabet.septets(text) != null

    private fun ceilDiv(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b
}

/**
 * Faithful-only accent folding: maps a character to its GSM-7 equivalent
 * ONLY when one exists (č->c, ą->a, ê->e), and leaves everything else
 * untouched - CJK, emoji, Cyrillic and Greek never fold (their NFD base is
 * not a GSM letter), and characters already IN GSM-7 (é à ö ñ ü ß ...) are
 * never rewritten because folding them gains nothing.
 *
 * Prior art (GitHub #17): Textra ("remove diacritics if it sends fewer
 * SMS"), Pulse SMS's Strip Unicode setting, and android-smsmms's
 * StripAccents (which only strips multi-segment messages and keeps
 * GSM-native Ü/ü/Ö/ö as fold TARGETS - the Hungarian double-acute
 * mappings below are borrowed from its table). QKSMS's silent toggle drew
 * the complaint that "the user can't tell when it activated" (qksms#1333),
 * which is why ClearSMS surfaces folding as a visible compose-bar
 * affordance gated on [plan] proving an actual segment saving.
 */
object AccentFold {
    /**
     * Folds that Unicode decomposition cannot derive but native speakers
     * accept as the plain-SMS spelling. Hungarian double acutes fold to the
     * GSM-native umlauts (per android-smsmms); stroked letters do not
     * decompose at all; the oe ligature expands to two letters.
     */
    private val overrides: Map<Char, String> =
        mapOf(
            'Ő' to "Ö",
            'ő' to "ö",
            'Ű' to "Ü",
            'ű' to "ü",
            'Ł' to "L",
            'ł' to "l",
            'Đ' to "D",
            'đ' to "d",
            'Œ' to "OE",
            'œ' to "oe",
        )

    /**
     * A folding that would actually cut the bill: [folded] sends as
     * [segmentsAfter] (< [segmentsBefore]) messages. Null-plan means the
     * affordance must not appear - either nothing folds, or folding would
     * change the text for no gain (e.g. an emoji keeps the message UCS-2
     * anyway, or it already fits one segment).
     */
    data class Plan(
        val original: String,
        val folded: String,
        val segmentsBefore: Int,
        val segmentsAfter: Int,
    )

    /**
     * Folds every faithfully-foldable character of [text]; unfoldable
     * characters survive verbatim. Idempotent: folding a folded string is
     * a no-op.
     */
    fun fold(text: String): String {
        // Compose first so a decomposed "e + combining acute" is judged as
        // the single character é (which is GSM-7 and stays).
        val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
        return buildString(composed.length) {
            for (ch in composed) {
                when {
                    GsmAlphabet.isGsm(ch) -> append(ch)
                    else -> append(overrides[ch] ?: foldChar(ch) ?: ch.toString())
                }
            }
        }
    }

    /**
     * The decision the compose-bar affordance and the auto-strip setting
     * both run on: non-null exactly when folding REDUCES the billable
     * segment count.
     */
    fun plan(text: String): Plan? {
        if (text.isBlank()) return null
        val folded = fold(text)
        if (folded == text) return null
        val before = SmsSegments.count(text)
        val after = SmsSegments.count(folded)
        return if (after < before) Plan(text, folded, before, after) else null
    }

    /** [fold] gated on [plan]: the text unchanged unless folding saves segments. */
    fun foldIfItSaves(text: String): String = plan(text)?.folded ?: text

    /**
     * NFD-decomposes [ch] and strips its combining marks; the base is used
     * only when every remaining character is a basic GSM-7 letter (č->c;
     * Cyrillic й decomposes to и which is not GSM, so it stays). Exposed
     * for tests: this is where "é has base e" lives even though é itself,
     * being GSM-7, is never folded by [fold].
     */
    internal fun foldChar(ch: Char): String? {
        val base =
            Normalizer
                .normalize(ch.toString(), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        if (base.isEmpty() || base == ch.toString()) return null
        return if (base.all(GsmAlphabet::isBasic)) base else null
    }
}
