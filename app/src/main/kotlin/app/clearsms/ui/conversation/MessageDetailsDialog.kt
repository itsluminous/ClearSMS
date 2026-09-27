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
import androidx.compose.ui.unit.dp
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.mms.SendFailureReason

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
 *   the sender's network time and the received time as two labelled rows.
 * - No time is ever invented: a missing network sent time reads as
 *   "not reported". The Delivered row shows a time only when the app
 *   recorded when it processed the carrier's delivery report (GitHub #44),
 *   and says that is what the time is - the report's arrival on this
 *   phone, a close proxy, not the carrier's own timestamp. A report whose
 *   arrival was never recorded reads as confirmed without a time, no report
 *   at all reads as unknown, and MMS (no delivery reports supported) says so.
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
                MessageDetails.Row.SentTimeUnknown -> R.string.message_details_sent_by_network
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
            MessageDetails.Row.SentTimeUnknown -> stringResource(R.string.message_details_sent_unknown)
            is MessageDetails.Row.Delivered ->
                when {
                    // A recorded acknowledgement: the time (with seconds, like
                    // the other time rows) plus what that time IS - when this
                    // phone received the report, not the carrier's stamp.
                    row.acknowledgedAtMs != null ->
                        MessageMetadata.preciseTimestampLabel(row.acknowledgedAtMs, is24Hour) + "\n" +
                            stringResource(R.string.message_details_delivered_at_note)
                    else ->
                        stringResource(
                            when (row.knowledge) {
                                MessageDetails.DeliveryKnowledge.CONFIRMED ->
                                    R.string.message_details_delivered_confirmed
                                MessageDetails.DeliveryKnowledge.UNKNOWN_NO_REPORT ->
                                    R.string.message_details_delivered_unknown
                                MessageDetails.DeliveryKnowledge.UNSUPPORTED_MMS ->
                                    R.string.message_details_delivered_mms
                            },
                        )
                }
            is MessageDetails.Row.Error ->
                stringResource(
                    when (row.reason) {
                        SendFailureReason.NO_MMS_NETWORK -> R.string.send_failure_no_mms_network
                        SendFailureReason.APN_CONFIGURATION -> R.string.send_failure_apn
                        SendFailureReason.HTTP_FAILURE -> R.string.send_failure_http
                        SendFailureReason.TRANSIENT -> R.string.send_failure_transient
                        SendFailureReason.UNKNOWN, null -> R.string.message_details_error_unknown
                    },
                )
            is MessageDetails.Row.Sim -> row.label
            MessageDetails.Row.InRecycleBin -> stringResource(R.string.message_details_bin)
        }
    Column {
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
