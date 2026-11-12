package app.clearsms.domain.model

/**
 * How clock times are written wherever the app shows one - inbox rows,
 * bubble corners, the revealed metadata line, message details, search,
 * Finance and the schedule picker's face (GitHub #158).
 *
 * The default follows the phone's own 12/24-hour setting, which is what the
 * details dialog already did; the two fixed choices exist for a user whose
 * phone setting is not what they want to read in this app. Resolved in one
 * place, `app.clearsms.ui.common.ClockFormat`, so every site agrees.
 */
enum class TimeFormat {
    /** Whatever Android's "Use 24-hour format" setting says. The default. */
    SYSTEM,

    /** Always "2:05 pm". */
    HOUR_12,

    /** Always "14:05". */
    HOUR_24,
}
