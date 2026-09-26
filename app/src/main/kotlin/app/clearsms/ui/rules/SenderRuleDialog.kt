package app.clearsms.ui.rules

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.data.rules.RuleEngine
import app.clearsms.domain.rules.SenderRule
import app.clearsms.ui.components.displayName

/**
 * The one-step rule: "messages from this sender are always <category>".
 * Pick a category, tap Save, done - no pattern, no body matching, nothing
 * that can be rejected. A "Detailed rule" link hands off to the full wizard
 * for the rare case that needs keyword or body conditions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SenderRuleDialog(
    sender: String,
    /** Contact or brand name to show; falls back to the sender core. */
    displayName: String? = null,
    onDismiss: () -> Unit,
    onSaved: (SenderRuleSaved) -> Unit,
    onDetailedRule: (() -> Unit)? = null,
    viewModel: SenderRuleViewModel = hiltViewModel(),
) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val shownName = displayName?.takeIf { it.isNotBlank() } ?: SenderRule.senderCore(sender)

    LaunchedEffect(saved) {
        saved?.let {
            viewModel.consumeSaved()
            onSaved(it)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sender_rule_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.sender_rule_body, shownName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SenderRule.CATEGORIES.forEach { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = { category = option },
                            label = { Text(RuleEngine.categoryOf(option).displayName()) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.sender_rule_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = category != null && !saving,
                onClick = {
                    saving = true
                    viewModel.save(sender, category ?: return@TextButton)
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (onDetailedRule != null) {
                    TextButton(onClick = onDetailedRule) {
                        Text(stringResource(R.string.sender_rule_detailed))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Confirmation line for the snackbar after a sender rule saved. */
fun senderRuleSavedMessage(
    resources: Resources,
    saved: SenderRuleSaved,
): String {
    val category = RuleEngine.categoryOf(saved.category).displayName()
    return if (saved.messages > 0) {
        resources.getString(R.string.sender_rule_saved, saved.messages, saved.senderCore, category)
    } else {
        resources.getString(R.string.sender_rule_saved_none, saved.senderCore, category)
    }
}
