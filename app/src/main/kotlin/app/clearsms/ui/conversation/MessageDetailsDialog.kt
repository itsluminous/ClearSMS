package app.clearsms.ui.conversation

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.clearsms.R
import app.clearsms.data.db.MessageEntity

/**
 * The "More details" dialog for ONE selected message. Rows come from the
 * pure [MessageDetails.rowsFor] mapping; this composable only formats and
 * lays them out. Design choices:
 *
 * - Wrapped in a [SelectionContainer] so every value is selectable and
 *   copyable - a details view you cannot copy from is half useful.
 * - The column scrolls, so large font scales never clip rows; colors are
 *   all theme roles, so both themes stay readable.
 * - Times are shown WITH seconds (GitHub #45): an incoming message shows
 *   the sender's network time and the received time as two labelled rows -
 *   and when the network attached no sent time, the Sent row is simply
 *   absent rather than carrying an explanation.
 * - No time is ever invented, and the wording stays short: the Delivered
 *   row shows the time when the app recorded when it processed the carrier's
 *   delivery report (GitHub #44), a plain "Yes" for a report whose arrival
 *   was never recorded, "Unknown" when no report came back, and for MMS (no
 *   delivery reports supported) it never claims delivery.
 * - Each row's label and value are merged into ONE accessibility node, so a
 *   screen reader hears "Delivered, Yes" - never a bare "Yes".
 */
@Composable
internal fun MessageDetailsDialog(
    message: MessageEntity,
    resolvedName: String?,
    simLabel: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val is24Hour = remember { DateFormat.is24HourFormat(context) }
    val rows = remember(message) { MessageDetails.rowsFor(message, resolvedName, simLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_details_title)) },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rows.forEach { row -> DetailRow(row, is24Hour) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_action_close)) }
        },
    )
}

/** One labeled detail: small label above, the (copyable) value beneath. */
@Composable
private fun DetailRow(
    row: MessageDetails.Row,
    is24Hour: Boolean,
) {
    val label =
        stringResource(
            when (row) {
                is MessageDetails.Row.Type -> R.string.message_details_type
                is MessageDetails.Row.Counterparty ->
                    if (row.outgoing) R.string.message_details_to else R.string.message_details_from
                is MessageDetails.Row.Timestamp ->
                    when (row.kind) {
                        MessageDetails.TimeKind.RECEIVED -> R.string.message_details_received
                        MessageDetails.TimeKind.SENT_BY_NETWORK -> R.string.message_details_sent_by_network
                        MessageDetails.TimeKind.SENT -> R.string.message_details_sent
                        MessageDetails.TimeKind.SCHEDULED -> R.string.message_details_scheduled
                    }
                is MessageDetails.Row.Delivered -> R.string.message_details_delivered
                is MessageDetails.Row.Error -> R.string.message_details_error
                is MessageDetails.Row.Sim -> R.string.message_details_sim
                MessageDetails.Row.InRecycleBin -> R.string.message_details_status
            },
        )
    val value =
        when (row) {
            is MessageDetails.Row.Type ->
                stringResource(
                    when (row.transport) {
                        MessageDetails.Transport.SMS -> R.string.message_details_type_sms
                        MessageDetails.Transport.MMS -> R.string.message_details_type_mms
                    },
                )
            // Contact / sender-directory name first, raw address beneath it.
            is MessageDetails.Row.Counterparty ->
                row.resolvedName?.let { "$it\n${row.address}" } ?: row.address
            // With seconds: sent vs received of one message can differ by
            // seconds, and that difference is what the row is for.
            is MessageDetails.Row.Timestamp ->
                MessageMetadata.preciseTimestampLabel(row.timestampMs, is24Hour)
            is MessageDetails.Row.Delivered ->
                when {
                    // A recorded acknowledgement: just the time (with seconds,
                    // like the other time rows) - no story about the carrier.
                    row.acknowledgedAtMs != null ->
                        MessageMetadata.preciseTimestampLabel(row.acknowledgedAtMs, is24Hour)
                    else ->
                        stringResource(
                            when (row.knowledge) {
                                // Confirmed by a real report, time unknown: "Yes".
                                MessageDetails.DeliveryKnowledge.CONFIRMED ->
                                    R.string.message_details_delivered_confirmed
                                // No report at all: honestly "Unknown".
                                MessageDetails.DeliveryKnowledge.UNKNOWN_NO_REPORT ->
                                    R.string.message_details_delivered_unknown
                                // MMS: never "Yes" - no delivery reports exist here.
                                MessageDetails.DeliveryKnowledge.UNSUPPORTED_MMS ->
                                    R.string.message_details_delivered_mms
                            },
                        )
                }
            is MessageDetails.Row.Error -> stringResource(SendFailureText.explanationRes(row.reason))
            is MessageDetails.Row.Sim -> row.label
            MessageDetails.Row.InRecycleBin -> stringResource(R.string.message_details_bin)
        }
    // One accessibility node per row: label then value, so TalkBack reads
    // "Delivered, Yes" rather than a context-free "Yes".
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
