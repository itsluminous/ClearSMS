package app.clearsms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clearsms.R
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.ui.theme.ClearSmsTheme

/**
 * Full-width banner at the top of the inbox for the most recent OTP:
 * large monospaced code (sized per the OTP display-size setting), sender
 * attribution and one-tap copy. Tapping the banner body opens the source
 * message; the X dismisses it. Both copying and dismissing mark the OTP
 * handled so it never reappears.
 */
@Composable
fun OtpBanner(
    code: String,
    senderName: String,
    onCopied: () -> Unit,
    onDismiss: () -> Unit,
    onOpenMessage: () -> Unit,
    modifier: Modifier = Modifier,
    displaySize: OtpDisplaySize = OtpDisplaySize.DEFAULT,
) {
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = stringResource(R.string.otp_banner_open),
                        onClick = onOpenMessage,
                    ).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.otp_banner_from, senderName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                OtpCode(code = code, ceilingSp = otpBannerFontSp(displaySize))
            }
            FilledTonalIconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    onCopied()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.otp_banner_copy),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.otp_banner_dismiss),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * The code on ONE line, never wrapped, never truncated: shrinks from the
 * user's size setting ([ceilingSp]) only as far as the width the buttons
 * leave it requires, dropping the inter-digit spacing before it would go
 * under the readability floor. The decision is [OtpCodeFit.plan] over real
 * text measurement; the drawing is the same `BasicText` +
 * `TextAutoSize.StepBased` the conversation title uses (Material3 1.3's
 * `Text` has no `autoSize`), with the style `Text` would have built - so a
 * code that fits at the ceiling is exactly the Text it was before.
 *
 * `softWrap = false`, `maxLines = 1` and `TextOverflow.Visible` together
 * are the no-truncation guarantee: the whole string is laid out as one
 * line and painted in full, whatever the width. The plan then makes sure
 * that line also FITS: StepBased resolves the largest size in
 * `[plan.minFontSp, ceiling]` that does not overflow, and the plan has
 * already measured that `plan.minFontSp` does not (except in the
 * unreachable last-resort case the contract documents, where the code
 * paints past its slot rather than lose a glyph).
 *
 * Accessibility: the announced text is pinned to the spaced form, so a
 * screen reader hears the code digit by digit whether or not the spacing
 * is drawn - the announcement never follows the visual layout.
 */
@Composable
private fun OtpCode(
    code: String,
    ceilingSp: Int,
) {
    // Exactly what Material3's Text(style = displaySmall, fontSize,
    // fontFamily) built: the given style with the two overrides merged in
    // (M3 Text does not fold LocalTextStyle into an explicit style).
    val baseStyle =
        MaterialTheme.typography.displaySmall.merge(
            TextStyle(fontSize = ceilingSp.sp, fontFamily = FontFamily.Monospace),
        )
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    val measurer = rememberTextMeasurer()
    val announced = OtpCodeFit.spaced(code)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val plan =
            remember(code, ceilingSp, maxWidthPx, baseStyle, measurer) {
                OtpCodeFit.plan(
                    code = code,
                    ceilingSp = ceilingSp,
                    availableWidth = maxWidthPx.toFloat(),
                ) { text, fontSp ->
                    measurer
                        .measure(
                            text = text,
                            style = baseStyle.copy(fontSize = fontSp.sp),
                            overflow = TextOverflow.Visible,
                            softWrap = false,
                            maxLines = OtpCodeFit.MaxLines,
                        ).size.width
                        .toFloat()
                }
            }
        BasicText(
            text = plan.text,
            style = baseStyle,
            color = { color },
            maxLines = OtpCodeFit.MaxLines,
            softWrap = false,
            overflow = TextOverflow.Visible,
            autoSize =
                TextAutoSize.StepBased(
                    minFontSize = plan.minFontSp.sp,
                    maxFontSize = plan.maxFontSp.sp,
                    stepSize = OtpCodeFit.StepSize,
                ),
            modifier =
                Modifier.clearAndSetSemantics {
                    text = AnnotatedString(announced)
                },
        )
    }
}

@Preview
@Composable
private fun OtpBannerPreview() {
    ClearSmsTheme {
        OtpBanner(
            code = "482910",
            senderName = "HDFC Bank",
            onCopied = {},
            onDismiss = {},
            onOpenMessage = {},
        )
    }
}
