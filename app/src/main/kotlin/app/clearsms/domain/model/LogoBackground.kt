package app.clearsms.domain.model

/**
 * Backing plate drawn behind a bundled sender logo.
 *
 * Most logo artwork is transparent PNG, so what sits behind it is a choice:
 * a white plate reproduces how brands intend their marks to be seen (and keeps
 * dark marks legible in dark mode), while a dynamic tint blends with the
 * Material You palette, and none lets the row background show through.
 *
 * Note: a handful of bundled logos ship with an opaque background baked into
 * the image (no alpha channel). Those look the same whichever option is
 * chosen - the setting can only control the plate WE draw.
 */
enum class LogoBackground {
    /** White plate - brand-accurate, always legible. The default. */
    WHITE,

    /** Dark plate - blends into dark themes; keeps light marks readable. */
    DARK,

    /** Material You tinted plate that follows the app's color scheme. */
    DYNAMIC,

    /** No plate: transparent artwork sits directly on the row. */
    NONE,
}
