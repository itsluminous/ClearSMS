package app.clearsms.ui.components

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.clearsms.R

/**
 * Two-step Material3 schedule picker: a date, then a time. Confirming with
 * a moment that is not in the future keeps the picker open and toasts why -
 * a schedule in the past is always a mistake, never a send. Shared by every
 * screen with a compose bar (conversation, new conversation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleTimePicker(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val context = LocalContext.current
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    val timePickerState = rememberTimePickerState(is24Hour = DateFormat.is24HourFormat(context))
    val pastTimeMessage = stringResource(R.string.conversation_schedule_past_time)

    if (!pickingTime) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = { pickingTime = true },
                ) { Text(stringResource(R.string.conversation_schedule_pick_time)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.conversation_schedule_send)) },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val atMs =
                            combineDateAndTime(
                                utcDateMillis = datePickerState.selectedDateMillis ?: return@TextButton,
                                hour = timePickerState.hour,
                                minute = timePickerState.minute,
                            )
                        if (atMs <= System.currentTimeMillis()) {
                            Toast.makeText(context, pastTimeMessage, Toast.LENGTH_SHORT).show()
                        } else {
                            onConfirm(atMs)
                        }
                    },
                ) { Text(stringResource(R.string.conversation_schedule_send)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * A DatePicker selection (UTC midnight of the chosen day) plus a local
 * hour/minute, combined into the epoch instant of that local wall time.
 */
internal fun combineDateAndTime(
    utcDateMillis: Long,
    hour: Int,
    minute: Int,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): Long =
    java.time.Instant
        .ofEpochMilli(utcDateMillis)
        .atZone(java.time.ZoneOffset.UTC)
        .toLocalDate()
        .atTime(hour, minute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
