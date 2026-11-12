package app.clearsms.domain.model

/**
 * In-app size of the message text inside conversation bubbles (GitHub #157).
 *
 * Every text size in the app is in sp, so Android's own font-size setting
 * already enlarges bubbles; this is a multiplier ON TOP of that, applied to
 * the bubble body style only (`ConversationScreen`'s `bodyStyle`), never to
 * the density. A scaled sp value is still sp, so the system scale keeps
 * applying at layout time: the two compose, neither replaces the other.
 */
enum class MessageTextSize(
    /** Factor applied to the body style's font size AND line height. */
    val scale: Float,
) {
    SMALL(0.85f),
    DEFAULT(1f),
    LARGE(1.15f),
    EXTRA_LARGE(1.3f),
}
