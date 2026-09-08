package app.clearsms.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clearsms.R
import app.clearsms.mms.StagedAttachment
import app.clearsms.sms.AccentFold
import app.clearsms.ui.common.AttachmentError

/**
 * The compose-bar SIM indicator. [visible] only on devices with 2+ active
 * subscriptions - single-SIM devices keep the pre-feature compose bar.
 */
data class SimUiState(
    val visible: Boolean = false,
    /** 1-based slot of the SIM the next send uses, drawn inside the icon; 0 = unknown. */
    val slot: Int = 0,
    /** Count of active SIMs, for the accessibility description. */
    val simCount: Int = 0,
    /** Operator / user-given subscription name, surfaced as a toast on tap. */
    val operatorName: String = "",
    /** System SIM colour (ARGB) for the chosen subscription; null = none. */
    val iconTint: Int? = null,
) {
    /**
     * Accessibility description of the icon indicator ("SIM 1 of 2 -
     * Airtel"). Built here, not as a resource, so the mapping stays
     * unit-testable and consistent with the tap toast.
     */
    val contentDescription: String get() = "SIM $slot of $simCount$nameSuffix"

    /**
     * Tap-toast label, slot FIRST ("SIM 1 - Airtel"): with the same carrier
     * on both SIMs the operator name alone is ambiguous, the slot never is.
     * The name (the platform's SubscriptionInfo display name, which is the
     * user's nickname when one is set) stays for users who rely on it.
     */
    val tapLabel: String get() = "SIM $slot$nameSuffix"

    /**
     * Long-press identity hint, "Sends with SIM 1 - Airtel" (GitHub #7,
     * round 2): tap already CYCLES and announces the new choice, so
     * long-press is the non-mutating "tell me what this is" - it names the
     * SIM the next send will use without changing anything. Slot first for
     * the same reason as [tapLabel]: with the same carrier on both SIMs the
     * name alone is ambiguous, the slot never is.
     */
    val hintLabel: String get() = "Sends with SIM $slot$nameSuffix"

    private val nameSuffix: String get() = if (operatorName.isBlank()) "" else " - $operatorName"
}

/**
 * The one compose bar: an attach affordance (a "+" opening the attachment
 * sheet), removable attachment chips with a running size indicator, text
 * field, dual-SIM indicator (tap cycles SIMs and toasts the operator name)
 * and a Send button whose long-press opens the schedule picker (text-only
 * messages; with attachments staged, scheduling is SMS-only and the
 * long-press explains that instead). Shared by the conversation screen and
 * the new-conversation screen so send affordances never diverge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    sim: SimUiState,
    onCycleSim: () -> Unit,
    onScheduleSend: () -> Unit,
    modifier: Modifier = Modifier,
    attachments: List<StagedAttachment> = emptyList(),
    onAttachClick: (() -> Unit)? = null,
    onRemoveAttachment: (StagedAttachment) -> Unit = {},
    attachmentError: AttachmentError? = null,
    onAccentsStripped: ((AccentFold.Plan) -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth().imePadding()) {
        if (attachments.isNotEmpty()) {
            AttachmentChipsRow(attachments = attachments, onRemove = onRemoveAttachment, error = attachmentError)
        } else {
            // An error can outlive its refused attachment (nothing staged).
            AttachmentErrorText(attachmentError)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onAttachClick != null) {
                TooltipIconButton(
                    label = stringResource(R.string.compose_attach),
                    onClick = onAttachClick,
                    icon = Icons.Outlined.Add,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.conversation_reply_hint)) },
                shape = RoundedCornerShape(28.dp),
                maxLines = 4,
            )
            // Accent-strip affordance (GitHub #17): appears ONLY when folding
            // accents would actually reduce the billable segment count - one
            // č silently flips the whole SMS from GSM-7 (160 chars) to UCS-2
            // (70/67), so a medium text bills as 3-5 messages. Tap folds the
            // draft (č->c); the screen confirms the saving with an undoable
            // snackbar. Long-press explains, like the bar's other affordances.
            // Hidden with attachments staged: those send as MMS, where SMS
            // encoding does not exist. Textra/android-smsmms fold silently
            // when it saves; QKSMS's silent toggle drew "the user can't tell
            // when it activated" (qksms#1333) - hence a visible button.
            val foldPlan = remember(draft, attachments.size) { accentFoldPlan(draft, attachments.size) }
            if (foldPlan != null && onAccentsStripped != null) {
                val stripLabel = stringResource(R.string.compose_strip_accents)
                val stripHint =
                    stringResource(
                        R.string.compose_strip_accents_hint,
                        foldPlan.segmentsBefore,
                        foldPlan.segmentsAfter,
                    )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = {
                                    onDraftChange(foldPlan.folded)
                                    onAccentsStripped(foldPlan)
                                },
                                onClickLabel = stripLabel,
                                onLongClick = { Toast.makeText(context, stripHint, Toast.LENGTH_LONG).show() },
                                onLongClickLabel = stripHint,
                            ).padding(6.dp)
                            .semantics { contentDescription = stripHint },
                ) {
                    // A single bold accented letter at EXACTLY the SIM
                    // indicator's footprint (shared ComposeBarIndicatorMetrics,
                    // so the two cannot drift) - the old wide arrow chip
                    // ("e-grave becomes e") was visually heavier than its
                    // neighbours. That explanation now lives in the
                    // long-press hint text.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(ComposeBarIndicatorMetrics.IconSize),
                    ) {
                        Text(
                            text = "è",
                            fontSize = ComposeBarIndicatorMetrics.GlyphFontSize,
                            lineHeight = ComposeBarIndicatorMetrics.GlyphFontSize,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            // Compact SIM indicator, dual-SIM devices only: a plain SIM-card
            // outline whose ONLY content is the slot number - the stock icon's
            // contact dots made the digit illegible (GitHub #7). Tapping
            // cycles SIMs and toasts the slot-first label; long-press is the
            // non-mutating identity hint ("Sends with SIM 1 - Airtel").
            // Outline and digit take the system's SIM colour when it is
            // legible on this theme's surface (see simIndicatorTint).
            if (sim.visible) {
                val simIdentity = stringResource(R.string.conversation_sim_identity)
                val simTint =
                    simIndicatorTint(
                        systemTint = sim.iconTint?.let { Color(it) },
                        surface = MaterialTheme.colorScheme.surface,
                        fallback = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = {
                                    onCycleSim()
                                    Toast.makeText(context, sim.tapLabel, Toast.LENGTH_SHORT).show()
                                },
                                onClickLabel = stringResource(R.string.conversation_sim_switch),
                                onLongClick = {
                                    Toast.makeText(context, sim.hintLabel, Toast.LENGTH_LONG).show()
                                },
                                onLongClickLabel = simIdentity,
                            ).padding(6.dp),
                ) {
                    Icon(
                        SimOutlineGlyph,
                        contentDescription = sim.contentDescription,
                        tint = simTint,
                    )
                    // The digit IS the indicator: as large as the outline's
                    // interior (~11dp wide at the 24dp icon size) allows
                    // without clipping. Same tint as the outline, so the
                    // system colour (or the onSurfaceVariant fallback) reads
                    // as ONE mark that stays legible on the bar surface in
                    // both light and dark themes.
                    Text(
                        text = sim.slot.toString(),
                        fontSize = ComposeBarIndicatorMetrics.GlyphFontSize,
                        lineHeight = ComposeBarIndicatorMetrics.GlyphFontSize,
                        fontWeight = FontWeight.Bold,
                        color = simTint,
                    )
                }
            }
            // Send: tap sends now, long-press opens the schedule picker. A
            // custom surface because FilledIconButton exposes no long-press.
            // While there is a text-only message to send, a small clock rides
            // the button's top-start corner hinting that Send has a long-press
            // behind it; with attachments staged, scheduling is SMS-only and
            // the long-press says so instead.
            val enabled = draft.isNotBlank() || attachments.isNotEmpty()
            val scheduleAvailable = scheduleHintVisible(draft, attachments.size)
            val scheduleSmsOnly = stringResource(R.string.compose_attachment_schedule_sms_only)
            Box {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    modifier =
                        Modifier.combinedClickable(
                            enabled = enabled,
                            onClick = onSend,
                            onClickLabel = stringResource(R.string.action_send),
                            onLongClick =
                                if (scheduleAvailable) {
                                    onScheduleSend
                                } else {
                                    { Toast.makeText(context, scheduleSmsOnly, Toast.LENGTH_SHORT).show() }
                                },
                            onLongClickLabel = stringResource(R.string.conversation_schedule_send),
                        ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = stringResource(R.string.action_send),
                        tint =
                            if (enabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        modifier = Modifier.padding(10.dp),
                    )
                }
                if (scheduleAvailable) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = stringResource(R.string.conversation_schedule_send),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .offset(x = (-4).dp, y = (-4).dp)
                                .size(14.dp)
                                .background(MaterialTheme.colorScheme.surface, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * Whether the schedule affordances (clock badge; reachable long-press) are
 * live: exactly when the compose field has something to send AND no
 * attachments are staged. Scheduling is SMS-only this wave: a scheduled
 * row persists only text, and building staged-part persistence plus an
 * alarm-path MMS dispatch would double the send surface for a niche
 * combination - the compose bar says so honestly instead of guessing.
 */
internal fun scheduleHintVisible(
    draft: String,
    attachmentCount: Int = 0,
): Boolean = draft.isNotBlank() && attachmentCount == 0

/**
 * Visibility + payload rule for the accent-strip button, pure so it is
 * unit-testable without a Compose harness (the repo's affordance pattern).
 * Non-null exactly when folding the draft would send FEWER billable
 * segments AND no attachments are staged - with attachments the message
 * goes as MMS, where GSM-7 vs UCS-2 does not exist, so offering to rewrite
 * the user's accents would be a pure loss.
 */
internal fun accentFoldPlan(
    draft: String,
    attachmentCount: Int,
): AccentFold.Plan? = if (attachmentCount > 0) null else AccentFold.plan(draft)

/**
 * The ONE set of size constants for the compose bar's small indicator
 * glyphs. The SIM slot indicator AND the accent-strip affordance both draw
 * from here - operator requirement: the accent affordance sits at exactly
 * the SIM indicator's footprint, and sharing the constants (instead of two
 * eyeballed dp values) means the two cannot drift apart.
 */
internal object ComposeBarIndicatorMetrics {
    /** Icon footprint - the SIM outline's intrinsic size. */
    val IconSize = 24.dp

    /** Glyph drawn inside the footprint (the SIM slot digit; the accent è). */
    val GlyphFontSize = 12.sp
}

/**
 * The colour the SIM indicator (outline + digit) is drawn in, pure so the
 * decision is unit-testable without a Compose harness. The system's SIM
 * colour ([android.telephony.SubscriptionInfo.getIconTint]) wins when
 * present AND legible - it is the colour the user already sees for this SIM
 * in system settings (GitHub #7). Legible means the WCAG 1.4.11 non-text
 * contrast minimum, 3:1 against the surface the bar sits on (the same
 * contrast-ratio arithmetic the brand tiles use, [contrastRatio], not a new
 * invention) - so a dark system tint on a dark theme, or a pale one on
 * light, falls back to [fallback] (onSurfaceVariant, the pre-tint colour)
 * instead of vanishing. Absent tint falls back the same way; duplicate
 * tints across SIMs are fine - the slot digit disambiguates, per round 1.
 */
internal fun simIndicatorTint(
    systemTint: Color?,
    surface: Color,
    fallback: Color,
): Color =
    if (systemTint != null && contrastRatio(systemTint.copy(alpha = 1f), surface) >= 3.0) {
        systemTint.copy(alpha = 1f)
    } else {
        fallback
    }

/**
 * A plain SIM-card outline - the familiar clipped-corner card shape and
 * nothing inside it. Replaces the stock Material outlined `SimCard` icon,
 * whose chip-contact dots competed with the slot digit and made it
 * unreadable (GitHub #7). Stroke-only, so [Icon]'s tint colors just the
 * outline and the digit sits directly on the bar surface. No dots, no chip
 * contacts - the overlaid slot digit is the glyph's only content.
 */
private val SimOutlineGlyph: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "SimOutlineGlyph",
            defaultWidth = ComposeBarIndicatorMetrics.IconSize,
            defaultHeight = ComposeBarIndicatorMetrics.IconSize,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                // Any opaque color works: Icon recolors the whole vector via tint.
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.5f, 8.5f)
                // The clipped corner that reads as "SIM card".
                lineTo(11.5f, 3.5f)
                lineTo(16f, 3.5f)
                quadTo(17.5f, 3.5f, 17.5f, 5f)
                lineTo(17.5f, 19f)
                quadTo(17.5f, 20.5f, 16f, 20.5f)
                lineTo(8f, 20.5f)
                quadTo(6.5f, 20.5f, 6.5f, 19f)
                close()
            }
        }.build()
}
