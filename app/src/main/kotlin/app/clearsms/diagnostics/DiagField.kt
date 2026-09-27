package app.clearsms.diagnostics

/**
 * One structured value on a diagnostic log entry.
 *
 * This is the privacy boundary of the in-app logger: a [DiagField] can only
 * be built through the factories below, and there is deliberately NO factory
 * that accepts an arbitrary [String]. Counts, booleans, database row ids,
 * platform result codes and enum names cannot carry a message body, a phone
 * number, a contact name, an OTP, an account tail, a VPA or an amount. The
 * three string-shaped inputs - [sender], [ruleId] and [mime] - are
 * validated against the narrow shape they are meant to carry and replaced
 * by a placeholder when the value does not fit.
 *
 * The source-level twin of this guard is `DiagnosticLogConventionTest`,
 * which fails the build when a `Diag.*` call site names a sensitive value
 * or interpolates into the event string.
 */
class DiagField private constructor(
    val name: String,
    val value: String,
) {
    override fun toString(): String = "$name=$value"

    companion object {
        /** Placeholder for a sender that is a phone number - it identifies a person. */
        const val PHONE = "[phone]"

        /** Placeholder for any value that does not fit the shape its factory carries. */
        const val DROPPED = "[dropped]"

        /** How many of something happened (rows imported, parts, attempts). */
        fun count(
            name: String,
            value: Int,
        ): DiagField = DiagField(name, value.toString())

        /** [count] for `Long` totals. */
        fun count(
            name: String,
            value: Long,
        ): DiagField = DiagField(name, value.toString())

        /** A yes/no fact ("duplicate", "initial run", "provider row present"). */
        fun flag(
            name: String,
            value: Boolean,
        ): DiagField = DiagField(name, value.toString())

        /**
         * A database row id (message, thread, transaction). Row ids are
         * process-local sequence numbers, meaningless outside this device.
         */
        fun id(
            name: String,
            value: Long?,
        ): DiagField = DiagField(name, value?.toString() ?: "null")

        /** A platform result / error code (e.g. `SmsManager.RESULT_*`). */
        fun code(
            name: String,
            value: Int,
        ): DiagField = DiagField(name, value.toString())

        /** An enum constant by name (category, delivery status, work result). */
        fun label(
            name: String,
            value: Enum<*>?,
        ): DiagField = DiagField(name, value?.name ?: "null")

        /**
         * A categorisation rule id. Rule ids are public identifiers
         * (`generic-scam-01`, `user_1a2b3c4d`); anything outside that
         * alphabet - or shaped like a phone number - is dropped.
         */
        fun ruleId(value: String?): DiagField =
            DiagField(
                "rule",
                when {
                    value == null -> "null"
                    RULE_ID.matches(value) && value.any { it.isLetter() } -> value
                    else -> DROPPED
                },
            )

        /**
         * A message sender. Alphanumeric sender ids (`VM-HDFCBK`, `HDFCBK`,
         * `AX-AMAZON-S`) and short codes are the operator's decision to keep -
         * they are what makes a rule-misclassification report actionable. A
         * phone-number-shaped sender identifies a PERSON and is replaced by
         * [PHONE] here, at the logger, never at share time; anything that fits
         * neither shape (an email-style MMS sender, a body passed by mistake)
         * becomes [DROPPED].
         */
        fun sender(value: String?): DiagField = DiagField("sender", LoggableSender.of(value))

        /**
         * An attachment's MIME type (`image/jpeg`, `video/mp4`). What a
         * carrier accepts depends on it, so an MMS bug report needs the
         * exact type - and it is not personal: the value must be a bare
         * `type/subtype` token from the IANA top-level types, so a body,
         * a name or a file name can never fit. Anything else is dropped.
         */
        fun mime(value: String?): DiagField =
            DiagField(
                "mime",
                when {
                    value == null -> "null"
                    else -> {
                        val trimmed = value.trim().lowercase()
                        if (MIME.matches(trimmed) && trimmed.substringBefore('/') in MIME_TOP_LEVEL) trimmed else DROPPED
                    }
                },
            )

        private val RULE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
        private val MIME = Regex("^[a-z]{1,11}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$")
        private val MIME_TOP_LEVEL =
            setOf("application", "audio", "font", "image", "message", "model", "multipart", "text", "video")
    }
}

/**
 * The single judgement of whether a sender address may appear in a log.
 * Mirrors the app's existing sender split (`SenderNormalizer` treats a
 * mostly-digit string of 7+ digits as a phone number; `DialableNumber`
 * recognises E.164 / Indian mobiles / toll-free lines) but errs on the side
 * of dropping: ANY value carrying seven or more digits is treated as a
 * phone number, whatever else surrounds them.
 */
object LoggableSender {
    private val HEADER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,19}$")
    private const val MAX_SHORT_CODE_DIGITS = 6

    /** True when [sender] is an alphanumeric header or a short code, never a phone number. */
    fun isLoggable(sender: String): Boolean {
        val trimmed = sender.trim()
        if (!HEADER.matches(trimmed)) return false
        val digits = trimmed.count { it.isDigit() }
        if (digits > MAX_SHORT_CODE_DIGITS) return false
        // All-digit values up to six digits are service short codes ("121",
        // "56767"); longer digit runs are phone numbers.
        return true
    }

    /** True when [sender] looks like a person's number: mostly digits, seven or more of them. */
    fun isPhoneShaped(sender: String): Boolean {
        val trimmed = sender.trim()
        if (trimmed.isEmpty()) return false
        val digits = trimmed.count { it.isDigit() }
        return digits > MAX_SHORT_CODE_DIGITS && trimmed.all { it.isDigit() || it in "+-() ." }
    }

    /** The loggable form of [sender]: itself, [DiagField.PHONE], or [DiagField.DROPPED]. */
    fun of(sender: String?): String {
        if (sender == null) return "null"
        val trimmed = sender.trim()
        return when {
            isLoggable(trimmed) -> trimmed
            isPhoneShaped(trimmed) || trimmed.count { it.isDigit() } > MAX_SHORT_CODE_DIGITS -> DiagField.PHONE
            else -> DiagField.DROPPED
        }
    }
}
