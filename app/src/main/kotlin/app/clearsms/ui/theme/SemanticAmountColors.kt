package app.clearsms.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Fixed semantic colors for money amounts: debit is always red, credit is
 * always green and a balance-only figure is always blue.
 *
 * These deliberately bypass [androidx.compose.material3.MaterialTheme]'s
 * `colorScheme`: Material You derives every scheme role from the user's
 * wallpaper, so `error`/`tertiary`/`primary` can land on hues that no longer
 * read as red/green/blue. Financial direction must not shift meaning with
 * the wallpaper, so these values are constants - only a light/dark variant
 * exists, chosen for WCAG AA (≥ 4.5:1) contrast against the app's light and
 * dark surfaces and the system notification shade.
 *
 * The same values are duplicated as `amount_debit` / `amount_credit` /
 * `amount_balance` in `res/values/colors.xml` (+ `values-night`) for the
 * custom notification layout; a unit test keeps both copies identical.
 */
@Immutable
data class SemanticAmountColors(
    val debit: Color,
    val credit: Color,
    val balance: Color,
) {
    companion object {
        /** Variant for light surfaces (white-ish shade and app background). */
        val Light =
            SemanticAmountColors(
                debit = Color(0xFFB3261E),
                credit = Color(0xFF1B5E20),
                balance = Color(0xFF0D47A1),
            )

        /** Variant for dark surfaces (dark shade and app background). */
        val Dark =
            SemanticAmountColors(
                debit = Color(0xFFFFB4AB),
                credit = Color(0xFFA5D6A7),
                balance = Color(0xFF90CAF9),
            )
    }
}

/**
 * The resolved [SemanticAmountColors] for the current theme; provided by
 * [ClearSmsTheme] (dark variant whenever the theme itself is dark).
 */
val LocalSemanticAmountColors = staticCompositionLocalOf { SemanticAmountColors.Light }
