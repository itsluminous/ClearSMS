package app.clearsms.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R

private val CATEGORY_OPTIONS = listOf("important", "promotional", "personal", "otp", "unknown")
private val SUB_CATEGORY_OPTIONS =
    listOf(
        "transaction",
        "otp",
        "bill",
        "bank_alert",
        "government",
        "recharge",
        "investment",
        "delivery",
        "offer",
        "scam",
        "general",
    )

/** Rule creation wizard: patterns, category/extraction pickers and a live test panel. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleWizardScreen(
    onBack: () -> Unit,
    viewModel: RuleWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rule_wizard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::save,
                text = { Text(stringResource(R.string.action_save)) },
                icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.rule_wizard_name)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.senderPattern,
                onValueChange = viewModel::onSenderPatternChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.rule_wizard_sender_pattern)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedTextField(
                value = state.bodyPattern,
                onValueChange = viewModel::onBodyPatternChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.rule_wizard_body_pattern)) },
                supportingText = { Text(stringResource(R.string.rule_wizard_body_pattern_hint)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 2,
            )

            Text(stringResource(R.string.rule_wizard_category), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORY_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = state.category == option,
                        onClick = { viewModel.onCategoryChange(option) },
                        label = { Text(option) },
                    )
                }
            }

            Text(stringResource(R.string.rule_wizard_sub_category), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SUB_CATEGORY_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = state.subCategory == option,
                        onClick = {
                            viewModel.onSubCategoryChange(if (state.subCategory == option) null else option)
                        },
                        label = { Text(option) },
                    )
                }
            }

            Text(stringResource(R.string.rule_wizard_extract), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExtractionField.entries.forEach { field ->
                    FilterChip(
                        selected = state.extractions.containsKey(field.key),
                        onClick = { viewModel.toggleExtraction(field) },
                        label = { Text(field.key) },
                    )
                }
            }

            // Live test panel — evaluates the draft rule with the real engine.
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.rule_wizard_test_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    OutlinedTextField(
                        value = state.testSender,
                        onValueChange = viewModel::onTestSenderChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.rule_wizard_test_sender)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.testBody,
                        onValueChange = viewModel::onTestBodyChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.rule_wizard_test_body)) },
                        minLines = 2,
                    )
                    when {
                        state.testError != null ->
                            TestVerdict(
                                matched = false,
                                text = stringResource(R.string.rule_wizard_test_invalid, state.testError.orEmpty()),
                            )
                        state.testResult != null -> {
                            val result = state.testResult
                            TestVerdict(
                                matched = true,
                                text =
                                    stringResource(
                                        R.string.rule_wizard_test_match,
                                        result?.category?.name.orEmpty(),
                                        result
                                            ?.extracted
                                            ?.entries
                                            ?.joinToString { "${it.key}=${it.value}" }
                                            .orEmpty(),
                                    ),
                            )
                        }
                        else -> TestVerdict(matched = false, text = stringResource(R.string.rule_wizard_test_no_match))
                    }
                }
            }
        }
    }
}

@Composable
private fun TestVerdict(
    matched: Boolean,
    text: String,
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (matched) Icons.Outlined.CheckCircle else Icons.Outlined.HighlightOff,
            contentDescription = null,
            tint = if (matched) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (matched) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
    }
}
