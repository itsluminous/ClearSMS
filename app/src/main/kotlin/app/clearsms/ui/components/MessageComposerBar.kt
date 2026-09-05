package app.clearsms.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clearsms.R
import app.clearsms.mms.StagedAttachment
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
            // Compact SIM indicator, dual-SIM devices only: a plain SIM-card
            // outline whose ONLY content is the slot number - the stock icon's
            // contact dots made the digit illegible (GitHub #7). Tapping
            // cycles SIMs and toasts the slot-first label.
            if (sim.visible) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(
                                onClick = {
                                    onCycleSim()
                                    Toast.makeText(context, sim.tapLabel, Toast.LENGTH_SHORT).show()
                                },
                                onClickLabel = stringResource(R.string.conversation_sim_switch),
                            ).padding(6.dp),
                ) {
                    Icon(
                        SimOutlineGlyph,
                        contentDescription = sim.contentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The digit IS the indicator: as large as the outline's
                    // interior (~11dp wide at the 24dp icon size) allows
                    // without clipping. Same tint as the outline;
                    // onSurfaceVariant stays legible on the bar surface in
                    // both light and dark themes.
                    Text(
                        text = sim.slot.toString(),
                        fontSize = 12.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
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
