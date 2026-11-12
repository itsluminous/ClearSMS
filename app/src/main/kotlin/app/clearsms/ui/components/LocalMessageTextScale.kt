package app.clearsms.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

/**
 * The user's bubble-text multiplier (GitHub #157, `MessageTextSize.scale`),
 * provided once at the app root (see `ClearSmsApp`) and read where the
 * conversation bubble builds its body style.
 *
 * It is applied with [scaledBy] to ONE style, never by overriding
 * `LocalDensity`: a density override would also enlarge the title, the
 * time/tick/SIM row, the filter pills and the composer, and would feed back
 * into `ConversationTitleFit`, which walks sp steps with the density it is
 * handed.
 */
val LocalMessageTextScale = staticCompositionLocalOf { 1f }

/**
 * [this] with its font size and line height multiplied by [scale]. A
 * `TextUnit` in sp times a float is still sp, so the system font scale
 * (Android's own font-size setting) keeps applying at layout time: the two
 * multiply, this one never replaces the other. Proportions are kept (16/24
 * at 1.3 is 20.8/31.2). Unspecified sizes stay unspecified.
 */
fun TextStyle.scaledBy(scale: Float): TextStyle =
    if (scale == 1f) {
        this
    } else {
        copy(
            fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
            lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
        )
    }
