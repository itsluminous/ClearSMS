package app.clearsms.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clearsms.R
import app.clearsms.domain.model.CategorizationResult
import app.clearsms.domain.rules.RuleSuggester
import app.clearsms.domain.rules.SuggestedToken
import app.clearsms.domain.rules.TokenKind

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

/**
 * Guided rule-creation wizard: the source message is analyzed into tappable
 * tokens and the app composes the regex - no hand-written patterns required.
 * An "Advanced: edit pattern" escape hatch remains for power users.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                title = {
                    Text(
                        stringResource(
                            if (state.editingRuleId != null) R.string.rule_wizard_title_edit else R.string.rule_wizard_title,
                        ),
                    )
                },
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
            if (state.analyzed) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::save,
                    text = { Text(stringResource(R.string.action_save)) },
                    icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                )
            }
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
            if (!state.analyzed) {
                SampleMessageStep(state, viewModel)
            } else {
                // Editing an existing rule has no source message to show.
                if (state.sourceBody.isNotBlank()) SourceMessageStep(state, viewModel)
                CategoryStep(state, viewModel)
                ExtractionStep(state, viewModel)
                ConditionsStep(state, viewModel)
                TestStep(state, viewModel)
                SaveStep(state, viewModel)
            }
        }
    }
}

/** Step 0 (only when the wizard is opened without a message): paste a sample. */
@Composable
private fun SampleMessageStep(
    state: RuleWizardUiState,
    viewModel: RuleWizardViewModel,
) {
    StepCard(title = stringResource(R.string.rule_wizard_step_source)) {
        OutlinedTextField(
            value = state.sourceSender,
            onValueChange = viewModel::onSourceSenderChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rule_wizard_sample_sender)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.sourceBody,
            onValueChange = viewModel::onSourceBodyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rule_wizard_sample_body)) },
            minLines = 3,
        )
        TextButton(onClick = viewModel::analyze, enabled = state.sourceBody.isNotBlank()) {
            Text(stringResource(R.string.rule_wizard_analyze))
        }
    }
}

/** Step 1: source message with detected tokens as tappable inline highlights. */
@Composable
private fun SourceMessageStep(
    state: RuleWizardUiState,
    viewModel: RuleWizardViewModel,
) {
    StepCard(
        title = stringResource(R.string.rule_wizard_step_source),
        subtitle = stringResource(R.string.rule_wizard_step_source_hint),
    ) {
        if (state.sourceSender.isNotBlank()) {
            Text(
                text = state.sourceSender,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HighlightedMessage(state, viewModel)
    }
}

@Composable
private fun HighlightedMessage(
    state: RuleWizardUiState,
    viewModel: RuleWizardViewModel,
) {
    val capturedStyle =
        SpanStyle(
            background = MaterialTheme.colorScheme.primaryContainer,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    val keywordStyle =
        SpanStyle(
            background = MaterialTheme.colorScheme.tertiaryContainer,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    val idleStyle =
        SpanStyle(
            background = MaterialTheme.colorScheme.surfaceVariant,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    val body = state.sourceBody
    val annotated =
        buildAnnotatedString {
            var pos = 0
            state.tokens.withIndex().sortedBy { it.value.start }.forEach { (index, token) ->
                if (token.start < pos) return@forEach
                append(body.substring(pos, token.start))
                val style =
                    when {
                        token.kind == TokenKind.KEYWORD && token.literal in state.mustContain -> keywordStyle
                        token.kind == TokenKind.KEYWORD -> idleStyle
                        (state.tokenFields[index] ?: FIELD_IGNORE) != FIELD_IGNORE -> capturedStyle
                        else -> idleStyle
                    }
                val link =
                    LinkAnnotation.Clickable(
                        tag = "token:$index",
                        styles = TextLinkStyles(style = style),
                    ) { viewModel.toggleToken(index) }
                pushLink(link)
                append(body.substring(token.start, token.end))
                pop()
                pos = token.end
            }
            append(body.substring(pos))
        }
    Text(text = annotated, style = MaterialTheme.typography.bodyMedium)
}

/** Step 2: category + sub-category as chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryStep(
    state: RuleWizardUiState,
    viewModel: RuleWizardViewModel,
) {
    StepCard(title = stringResource(R.string.rule_wizard_step_category)) {
        Text(stringResource(R.string.rule_wizard_category), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CATEGORY_OPTIONS.forEach { option ->
                FilterChip(
                    selected = state.category == option,
                    onClick = { viewModel.onCategoryChange(option) },
                    label = { Text(option) },
                )
            }
        }
        Text(stringResource(R.string.rule_wizard_sub_category), style = MaterialTheme.typography.labelLarge)
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
    }
}

/** Step 3: detected values with a target-field selector per row. */
@Composable
private fun ExtractionStep(
    state: RuleWizardUiState,
    viewModel: RuleWizardViewModel,
) {
    val rows = state.tokens.withIndex().filter { it.value.kind != TokenKind.KEYWORD }
    StepCard(title = stringResource(R.string.rule_wizard_step_extract)) {
        if (rows.isEmpty()) {
            if (state.extract.isNotEmpty()) {
                // Edit mode: extracts loaded from the rule, editable only
                // through the advanced pattern editor.
                state.extract.forEach { (key, value) ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = key, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.rule_wizard_extract_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        rows.forEachIndexed { position, (index, token) ->
            if (position > 0) HorizontalDivider()
            TokenRow(
                token = token,
                field = state.tokenFields[index] ?: FIELD_IGNORE,
                onFieldChange = { viewModel.setTokenField(index, it) },
            )
        }
    }
}

@Composable
private fun TokenRow(
    token: SuggestedToken,
    field: String,
    onFieldChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = token.literal,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tokenKindLabel(token.kind),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FieldSelector(field = field, onFieldChange = onFieldChange)
    }
}

@Composable
private fun FieldSelector(
    field: String,
    onFieldChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { expanded = true },
        label = { Text(if (field == FIELD_IGNORE) stringResource(R.string.rule_wizard_field_ignore) else field) },
        trailingIcon = { Icon(Icons.Outlined.ExpandMore, contentDescription = null) },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        (listOf(FIELD_IGNORE) + RuleSuggester.Fields.ALL).forEach { option ->
            DropdownMenuItem(
                text = {
                    Text(if (option == FIELD_IGNORE) stringResource(R.string.rule_wizard_field_ignore) else option)
                },
                onClick = {
                    expanded = false
                    onFieldChange(option)
                },
            )
        }
    }
}

/** Step 4: must-contain keyword chips, must-not-contain, sender binding. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionsStep(
    state: RuleWizardUiState,
    viewModel: RuleWizardViewModel,
) {
    StepCard(title = stringResource(R.string.rule_wizard_step_conditions)) {
        if (state.keywordOptions.isNotEmpty()) {
            Text(stringResource(R.string.rule_wizard_keywords_hint), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.keywordOptions.forEach { word ->
                    FilterChip(
                        selected = word in state.mustContain,
                        onClick = { viewModel.toggleKeyword(word) },
                        label = { Text(word) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.mustNotContain,
            onValueChange = viewModel::onMustNotContainChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rule_wizard_must_not)) },
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.rule_wizard_bind_sender),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (state.composedSenderPattern.isNotBlank()) {
                    Text(
                        text = state.composedSenderPattern,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = state.bindSender, onCheckedChange = viewModel::onBindSenderChange)
        }
    }
}

/** Step 5: generated pattern preview, source verdict, and a second test message. */
@Composable
private fun TestStep(
    state: RuleWizardUiState,
    viewModel: RuleWizardViewModel,
) {
    var patternVisible by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    StepCard(title = stringResource(R.string.rule_wizard_step_test)) {
        TextButton(onClick = { patternVisible = !patternVisible }) {
            Icon(
                if (patternVisible) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
            )
            Text(
                stringResource(
                    if (patternVisible) R.string.rule_wizard_hide_pattern else R.string.rule_wizard_show_pattern,
                ),
            )
        }
        if (patternVisible) {
            SelectionContainer {
                Text(
                    text = state.effectiveBodyPattern,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        if (state.sourceBody.isNotBlank()) {
            Verdict(
                matched = state.sourceResult != null,
                matchedText = stringResource(R.string.rule_wizard_source_match),
                unmatchedText = stringResource(R.string.rule_wizard_source_no_match),
            )
            ExtractedValues(state.sourceResult)
        }

        HorizontalDivider()
        Text(stringResource(R.string.rule_wizard_try_another), style = MaterialTheme.typography.labelLarge)
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
        if (state.testBody.isNotBlank()) {
            Verdict(
                matched = state.testResult != null,
                matchedText =
                    stringResource(
                        R.string.rule_wizard_test_match,
                        state.testResult
                            ?.category
                            ?.name
                            .orEmpty(),
                        "",
                    ).trim(),
                unmatchedText = stringResource(R.string.rule_wizard_test_no_match),
            )
            ExtractedValues(state.testResult)
        }

        // Secondary, clearly out of the happy path: raw pattern editing.
        TextButton(onClick = { advancedOpen = !advancedOpen }) {
            Text(stringResource(R.string.rule_wizard_advanced))
        }
        if (advancedOpen) {
            OutlinedTextField(
                value = state.effectiveBodyPattern,
                onValueChange = viewModel::onPatternOverrideChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.rule_wizard_body_pattern)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 2,
            )
            if (state.patternOverride != null) {
                TextButton(onClick = viewModel::resetPatternOverride) {
                    Text(stringResource(R.string.rule_wizard_advanced_reset))
                }
            }
        }
    }
}

/** Step 6: name + priority (defaults into the user band) + validation errors. */
@Composable
private fun SaveStep(
    state: RuleWizardUiState,
    viewModel: RuleWizardViewModel,
) {
    StepCard(title = stringResource(R.string.rule_wizard_step_save)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rule_wizard_name)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.priority,
            onValueChange = viewModel::onPriorityChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rule_wizard_priority)) },
            supportingText = { Text(stringResource(R.string.rule_wizard_priority_hint)) },
            singleLine = true,
        )
        state.validationError?.let { error ->
            Text(
                text = validationErrorText(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun Verdict(
    matched: Boolean,
    matchedText: String,
    unmatchedText: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (matched) Icons.Outlined.CheckCircle else Icons.Outlined.HighlightOff,
            contentDescription = null,
            tint = if (matched) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
        Text(
            text = if (matched) matchedText else unmatchedText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (matched) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
    }
}

/** Key/value table of what the rule extracted from a test message. */
@Composable
private fun ExtractedValues(result: CategorizationResult?) {
    val extracted = result?.extracted.orEmpty()
    if (extracted.isEmpty()) return
    Text(stringResource(R.string.rule_wizard_extracted_values), style = MaterialTheme.typography.labelLarge)
    extracted.forEach { (key, value) ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = key, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun validationErrorText(error: WizardValidationError): String =
    stringResource(
        when (error) {
            WizardValidationError.EMPTY_PATTERN -> R.string.rule_error_empty_pattern
            WizardValidationError.INVALID_PATTERN -> R.string.rule_error_invalid_pattern
            WizardValidationError.CATCH_ALL_WRAPPER -> R.string.rule_error_catch_all
            WizardValidationError.CAPTURE_MISMATCH -> R.string.rule_error_capture_mismatch
            WizardValidationError.DUPLICATE_FIELD -> R.string.rule_error_duplicate_field
            WizardValidationError.NO_SOURCE_MATCH -> R.string.rule_error_no_source_match
        },
    )

@Composable
private fun tokenKindLabel(kind: TokenKind): String =
    stringResource(
        when (kind) {
            TokenKind.AMOUNT -> R.string.rule_token_amount
            TokenKind.BALANCE -> R.string.rule_token_balance
            TokenKind.ACCOUNT_LAST4 -> R.string.rule_token_account
            TokenKind.OTP_CODE -> R.string.rule_token_otp
            TokenKind.DATE -> R.string.rule_token_date
            TokenKind.REFERENCE -> R.string.rule_token_reference
            TokenKind.PERCENT -> R.string.rule_token_percent
            TokenKind.GENERIC_NUMBER -> R.string.rule_token_number
            TokenKind.VENDOR -> R.string.rule_token_vendor
            TokenKind.KEYWORD -> R.string.rule_token_keyword
        },
    )
