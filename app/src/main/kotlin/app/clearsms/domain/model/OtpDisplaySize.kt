package app.clearsms.domain.model

/**
 * Display size of OTP digits, from smallest [OPTION_1] to largest
 * [OPTION_5]. There is no separate "default" entry - [DEFAULT] points at
 * [OPTION_2], which every fresh install and every migrated legacy value
 * resolves to when nothing better matches.
 */
enum class OtpDisplaySize {
    OPTION_1,
    OPTION_2,
    OPTION_3,
    OPTION_4,
    OPTION_5,
    ;

    companion object {
        /** The out-of-the-box size. */
        val DEFAULT = OPTION_2

        /**
         * Resolves a persisted preference value, including values written by
         * older builds. The legacy scale interleaved a "Default" entry with
         * lettered options (A < Default < B < C < D by rendered size), so
         * each maps to the slot that keeps the user's chosen size:
         * A→1, Default→2, B→3, C→4, D→5. Null, blank or unknown values fall
         * back to [DEFAULT] - never a crash, never an unset state.
         */
        fun fromStored(stored: String?): OtpDisplaySize =
            when (stored) {
                "OPTION_A" -> OPTION_1
                "DEFAULT" -> OPTION_2
                "OPTION_B" -> OPTION_3
                "OPTION_C" -> OPTION_4
                "OPTION_D" -> OPTION_5
                else -> entries.firstOrNull { it.name == stored } ?: DEFAULT
            }
    }
}
