package app.clearsms.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import app.clearsms.R
import app.clearsms.domain.model.StartDestination
import app.clearsms.ui.alerts.AlertsScreen
import app.clearsms.ui.components.LocalLogoBackground
import app.clearsms.ui.composemsg.ComposeMessageScreen
import app.clearsms.ui.conversation.ConversationScreen
import app.clearsms.ui.finance.AccountDetailScreen
import app.clearsms.ui.finance.FinanceScreen
import app.clearsms.ui.inbox.ArchivedScreen
import app.clearsms.ui.inbox.BinScreen
import app.clearsms.ui.inbox.InboxScreen
import app.clearsms.ui.onboarding.OnboardingScreen
import app.clearsms.ui.rules.RuleWizardScreen
import app.clearsms.ui.rules.RulesScreen
import app.clearsms.ui.search.SearchScreen
import app.clearsms.ui.settings.LicensesScreen
import app.clearsms.ui.settings.PermissionsInfoScreen
import app.clearsms.ui.settings.PrivacyPolicyScreen
import app.clearsms.ui.settings.SettingsItem
import app.clearsms.ui.settings.SettingsScreen
import app.clearsms.ui.theme.ClearSmsTheme

private data class BottomDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

/** Root composable: theme, onboarding gate, bottom navigation and the nav graph. */
@Composable
fun ClearSmsApp(
    initialRecipient: String?,
    initialBody: String?,
    initialImageUri: String?,
    onOnboarded: () -> Unit,
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val state by appViewModel.uiState.collectAsStateWithLifecycle()
    ClearSmsTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColor) {
        CompositionLocalProvider(LocalLogoBackground provides state.logoBackground) {
            when (state.onboardingComplete) {
                null -> Box(Modifier.fillMaxSize()) // settings still loading; avoid flashing a screen
                false -> OnboardingScreen()
                true -> {
                    LaunchedEffect(Unit) { onOnboarded() }
                    MainScaffold(
                        initialRecipient = initialRecipient,
                        initialBody = initialBody,
                        initialImageUri = initialImageUri,
                        startDestination = state.defaultDestination,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(
    initialRecipient: String?,
    initialBody: String?,
    initialImageUri: String?,
    startDestination: StartDestination,
    navController: NavHostController = rememberNavController(),
) {
    val destinations =
        listOf(
            BottomDestination(Routes.INBOX, Icons.Outlined.ChatBubbleOutline, R.string.nav_inbox),
            BottomDestination(Routes.FINANCE, Icons.Outlined.AccountBalanceWallet, R.string.nav_finance),
            BottomDestination(Routes.ALERTS, Icons.Outlined.Notifications, R.string.nav_alerts),
        )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // A share/compose intent deep-links straight into the compose screen.
    // A shared image rides along as a nav argument; the compose ViewModel
    // stages it immediately (the share grant dies with the activity).
    LaunchedEffect(initialRecipient, initialBody, initialImageUri) {
        if (!initialRecipient.isNullOrBlank() || !initialBody.isNullOrBlank() || !initialImageUri.isNullOrBlank()) {
            navController.navigate(Routes.compose(initialRecipient, initialBody, initialImageUri))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            if (currentRoute in Routes.topLevel) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = stringResource(destination.labelRes),
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination =
                when (startDestination) {
                    StartDestination.INBOX -> Routes.INBOX
                    StartDestination.FINANCE -> Routes.FINANCE
                    StartDestination.ALERTS -> Routes.ALERTS
                },
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.INBOX) {
                InboxScreen(
                    onOpenThread = { threadId -> navController.navigate(Routes.conversation(threadId)) },
                    onOpenMessage = { threadId, messageId ->
                        navController.navigate(Routes.conversation(threadId, messageId))
                    },
                    onCompose = { navController.navigate(Routes.compose()) },
                    onSearch = { navController.navigate(Routes.SEARCH) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onCreateRule = { sender, body -> navController.navigate(Routes.ruleWizard(sender, body)) },
                )
            }
            composable(Routes.ARCHIVED) {
                ArchivedScreen(
                    onOpenThread = { threadId -> navController.navigate(Routes.conversation(threadId)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.RECYCLE_BIN) {
                BinScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.FINANCE) {
                FinanceScreen(
                    onOpenAccount = { number, bank -> navController.navigate(Routes.accountDetail(number, bank)) },
                    onOpenMessage = { threadId, messageId ->
                        navController.navigate(Routes.conversation(threadId, messageId))
                    },
                    onSearch = { navController.navigate(Routes.SEARCH) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(
                route = Routes.ALERTS,
                deepLinks = listOf(navDeepLink { uriPattern = "clearsms://alerts" }),
            ) {
                AlertsScreen(
                    onOpenMessage = { threadId, messageId ->
                        navController.navigate(Routes.conversation(threadId, messageId))
                    },
                    onSearch = { navController.navigate(Routes.SEARCH) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onOpenThread = { threadId, messageId ->
                        navController.navigate(Routes.conversation(threadId, messageId))
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.CONVERSATION,
                arguments =
                    listOf(
                        navArgument("threadId") { type = NavType.LongType },
                        navArgument("messageId") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                    ),
                deepLinks =
                    listOf(
                        navDeepLink { uriPattern = "clearsms://conversation/{threadId}" },
                        navDeepLink { uriPattern = "clearsms://conversation/{threadId}?messageId={messageId}" },
                    ),
            ) {
                ConversationScreen(
                    onBack = { navController.popBackStack() },
                    onCreateRule = { sender, body -> navController.navigate(Routes.ruleWizard(sender, body)) },
                    // Forward: the compose screen with the text prefilled,
                    // recipient empty (and focused - see ComposeMessageScreen).
                    onForward = { text -> navController.navigate(Routes.compose(body = text)) },
                )
            }
            composable(
                route = Routes.COMPOSE,
                arguments =
                    listOf(
                        navArgument("recipient") { defaultValue = "" },
                        navArgument("body") { defaultValue = "" },
                        navArgument("imageUri") { defaultValue = "" },
                    ),
            ) {
                ComposeMessageScreen(
                    onBack = { navController.popBackStack() },
                    // A dispatched send created the thread: REPLACE this
                    // screen with the conversation (back goes to the inbox,
                    // never to a stale compose form).
                    onOpenConversation = { threadId ->
                        navController.navigate(Routes.conversation(threadId)) {
                            popUpTo(Routes.COMPOSE) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.ACCOUNT_DETAIL,
                arguments =
                    listOf(
                        navArgument("accountNumber") { type = NavType.StringType },
                        navArgument("bank") { defaultValue = "" },
                    ),
            ) {
                AccountDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenMessage = { threadId, messageId ->
                        navController.navigate(Routes.conversation(threadId, messageId))
                    },
                )
            }
            composable(
                route = Routes.SETTINGS,
                arguments =
                    listOf(
                        navArgument("highlight") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
            ) { entry ->
                SettingsScreen(
                    highlight =
                        entry.arguments
                            ?.getString("highlight")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { name -> SettingsItem.entries.firstOrNull { it.name == name } },
                    onBack = { navController.popBackStack() },
                    onManageRules = { navController.navigate(Routes.RULES) },
                    onArchived = { navController.navigate(Routes.ARCHIVED) },
                    onRecycleBin = { navController.navigate(Routes.RECYCLE_BIN) },
                    onPrivacyPolicy = { navController.navigate(Routes.PRIVACY_POLICY) },
                    onLicenses = { navController.navigate(Routes.LICENSES) },
                    onPermissions = { navController.navigate(Routes.PERMISSIONS_INFO) },
                )
            }
            composable(Routes.PRIVACY_POLICY) { PrivacyPolicyScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.LICENSES) { LicensesScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.PERMISSIONS_INFO) { PermissionsInfoScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.RULES) {
                RulesScreen(
                    onBack = { navController.popBackStack() },
                    onCreateRule = { navController.navigate(Routes.ruleWizard()) },
                    onEditRule = { ruleId -> navController.navigate(Routes.ruleWizardEdit(ruleId)) },
                    onDuplicateRule = { ruleId -> navController.navigate(Routes.ruleWizardDuplicate(ruleId)) },
                )
            }
            composable(
                route = Routes.RULE_WIZARD,
                arguments =
                    listOf(
                        navArgument("sender") { defaultValue = "" },
                        navArgument("body") { defaultValue = "" },
                        navArgument("ruleId") { defaultValue = "" },
                        navArgument("duplicate") {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
            ) {
                RuleWizardScreen(
                    onBack = { navController.popBackStack() },
                    // A rule that needs the full re-sort sends the user straight
                    // to the setting that runs it, highlighted on arrival - the
                    // same gesture search uses to point at a message.
                    onOpenSortSetting = {
                        navController.popBackStack()
                        navController.navigate(Routes.settings(SettingsItem.SORT_AGAIN.name))
                    },
                )
            }
        }
    }
}
