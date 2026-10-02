package app.clearsms.data.repository

/**
 * Region-aware reduction of a phone number to the digits that identify the
 * subscriber - the **national significant number** (NSN): country code and
 * trunk prefix stripped, nothing else touched.
 *
 * This replaces the old `digits.takeLast(10)` thread key (issue #42), which
 * assumed every country has ten-digit subscriber numbers. Poland has nine:
 * `+48 601 234 567` and `601 234 567` became `8601234567` and `601234567` -
 * two keys, two inbox threads for one person. Here both reduce to
 * `601234567`.
 *
 * Deliberately hand-rolled and small - no phone-number library. Everything
 * needed is (a) E.164's country-code lengths, which are a fixed rule, and
 * (b) per-region trunk prefix and plausible NSN lengths for the regions the
 * app is likely to run in. A region missing from [REGIONS] degrades
 * CONSERVATIVELY: `+`-prefixed numbers still reduce to their NSN (the
 * country code is known from the number itself), but a locally formatted
 * number keeps all of its digits, because stripping a trunk prefix we
 * cannot vouch for would invent merges. The platform thread id closes the
 * gap for such rows (see `ThreadIdentity`).
 */
object PhoneNumberKey {
    /**
     * Per-region dialling facts. [trunk] is the prefix a nationally
     * formatted number carries and an international one drops (`0` in most
     * of the world, `1` in NANP, `8` in Russia/Kazakhstan, none in e.g.
     * Poland, Spain, Italy - where a leading 0 is part of the number).
     * [minNsn]..[maxNsn] bound the subscriber-number lengths the region
     * actually assigns, so a prefix is only ever stripped when what is left
     * is a plausible number.
     */
    data class Region(
        val countryCode: String,
        val trunk: String?,
        val minNsn: Int,
        val maxNsn: Int,
    )

    /** ISO 3166-1 alpha-2 (upper case) → dialling facts. */
    val REGIONS: Map<String, Region> =
        buildMap {
            // NANP: country code 1, trunk 1, ten-digit NSN.
            for (r in listOf("US", "CA", "PR", "DO", "JM", "TT", "BS", "BB")) put(r, Region("1", "1", 10, 10))
            put("IN", Region("91", "0", 10, 10))
            put("GB", Region("44", "0", 9, 10))
            put("IE", Region("353", "0", 7, 9))
            put("PL", Region("48", null, 9, 9))
            put("DE", Region("49", "0", 6, 11))
            put("FR", Region("33", "0", 9, 9))
            put("IT", Region("39", null, 6, 11))
            put("ES", Region("34", null, 9, 9))
            put("PT", Region("351", null, 9, 9))
            put("NL", Region("31", "0", 9, 9))
            put("BE", Region("32", "0", 8, 9))
            put("CH", Region("41", "0", 9, 9))
            put("AT", Region("43", "0", 4, 13))
            put("SE", Region("46", "0", 7, 9))
            put("NO", Region("47", null, 8, 8))
            put("DK", Region("45", null, 8, 8))
            put("FI", Region("358", "0", 5, 12))
            put("CZ", Region("420", null, 9, 9))
            put("RO", Region("40", "0", 9, 9))
            put("GR", Region("30", null, 10, 10))
            put("UA", Region("380", "0", 9, 9))
            put("RU", Region("7", "8", 10, 10))
            put("KZ", Region("7", "8", 10, 10))
            put("TR", Region("90", "0", 10, 10))
            put("IL", Region("972", "0", 8, 9))
            put("AE", Region("971", "0", 8, 9))
            put("SA", Region("966", "0", 8, 9))
            put("EG", Region("20", "0", 8, 10))
            put("ZA", Region("27", "0", 9, 9))
            put("NG", Region("234", "0", 10, 10))
            put("KE", Region("254", "0", 9, 9))
            put("PK", Region("92", "0", 10, 10))
            put("BD", Region("880", "0", 10, 10))
            put("LK", Region("94", "0", 9, 9))
            put("NP", Region("977", "0", 8, 10))
            put("SG", Region("65", null, 8, 8))
            put("MY", Region("60", "0", 9, 10))
            put("ID", Region("62", "0", 8, 12))
            put("PH", Region("63", "0", 10, 10))
            put("TH", Region("66", "0", 8, 9))
            put("VN", Region("84", "0", 9, 10))
            put("JP", Region("81", "0", 9, 10))
            put("KR", Region("82", "0", 8, 10))
            put("CN", Region("86", "0", 10, 11))
            put("HK", Region("852", null, 8, 8))
            put("TW", Region("886", "0", 8, 9))
            put("AU", Region("61", "0", 9, 9))
            put("NZ", Region("64", "0", 8, 10))
            put("BR", Region("55", "0", 10, 11))
            put("MX", Region("52", null, 10, 10))
            put("AR", Region("54", "0", 10, 10))
        }

    /**
     * E.164 country codes are prefix-free and 1-3 digits: `1` and `7` are
     * the only one-digit codes, these are the two-digit ones, every other
     * assigned code has three digits. That rule is all a `+` number needs.
     */
    private val TWO_DIGIT_CODES: Set<String> =
        (
            "20 27 30 31 32 33 34 36 39 40 41 43 44 45 46 47 48 49 " +
                "51 52 53 54 55 56 57 58 60 61 62 63 64 65 66 81 82 84 86 90 91 92 93 94 95 98"
        ).split(' ')
            .toSet()

    /** The shortest subscriber number treated as a phone number at all. */
    const val MIN_SUBSCRIBER_DIGITS = 7

    /** Country code carried by an international (`+`/`00`) digit string, or null. */
    fun countryCodeOf(internationalDigits: String): String? {
        if (internationalDigits.isEmpty()) return null
        val one = internationalDigits.take(1)
        if (one == "1" || one == "7") return one
        val two = internationalDigits.take(2)
        if (two in TWO_DIGIT_CODES) return two
        return internationalDigits.take(3).takeIf { it.length == 3 }
    }

    /**
     * The thread key for a phone-shaped sender: its NSN digits.
     *
     * @param raw the sender as received (may carry `+`, spaces, dashes).
     * @param region ISO 3166-1 alpha-2 of the device's SIM, or null when
     *   unknown - then only the `+`/`00` international forms are reduced.
     */
    fun nationalKey(
        raw: String,
        region: String?,
    ): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter(Char::isDigit)
        if (digits.isEmpty()) return digits
        val international =
            when {
                trimmed.startsWith("+") -> digits

                // 00 is the international access code nearly everywhere and
                // no national number begins with it.
                digits.startsWith("00") && digits.length >= MIN_SUBSCRIBER_DIGITS + 3 -> digits.drop(2)

                else -> null
            }
        if (international != null) {
            val cc = countryCodeOf(international) ?: return international
            val nsn = international.drop(cc.length)
            return if (nsn.length >= MIN_SUBSCRIBER_DIGITS) nsn else international
        }
        val facts = region?.uppercase()?.let(REGIONS::get) ?: return digits
        // "919876543210" - the country code without a plus. Only when the
        // string is too long to be a national number, so a valid number
        // that happens to start with the code's digits is never cut.
        if (digits.length > facts.maxNsn && digits.startsWith(facts.countryCode)) {
            val rest = digits.drop(facts.countryCode.length)
            if (rest.length in facts.minNsn..facts.maxNsn) return rest
        }
        val trunk = facts.trunk
        if (trunk != null && digits.startsWith(trunk)) {
            val rest = digits.drop(trunk.length)
            if (rest.length in facts.minNsn..facts.maxNsn) return rest
        }
        return digits
    }

    /**
     * The pre-#42 key (`digits.takeLast(10)`), kept ONLY so entries stored
     * under it - blocked/muted numbers, remembered SIM choices, pins in old
     * backups - keep matching after the change. Never used for new keys.
     */
    fun legacyKey(raw: String): String = raw.filter(Char::isDigit).takeLast(LEGACY_KEY_DIGITS)

    /**
     * Platform-style loose equality of two phone keys: equal, or one is the
     * tail of the other with at least [MIN_SUBSCRIBER_DIGITS] digits shared
     * (`PhoneNumberUtils.compare`'s rule). Used to sanity-check a provider
     * thread before trusting it: two variants of one number pass, two
     * different people in a group thread do not.
     */
    fun looselySame(
        keyA: String,
        keyB: String,
    ): Boolean {
        if (keyA.isEmpty() || keyB.isEmpty()) return false
        if (!keyA.all(Char::isDigit) || !keyB.all(Char::isDigit)) return false
        val (short, long) = if (keyA.length <= keyB.length) keyA to keyB else keyB to keyA
        return short.length >= MIN_SUBSCRIBER_DIGITS && long.endsWith(short)
    }

    private const val LEGACY_KEY_DIGITS = 10
}
