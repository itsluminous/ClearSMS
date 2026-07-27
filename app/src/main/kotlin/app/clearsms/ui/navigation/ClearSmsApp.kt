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
import app.clearsms.R
import app.clearsms.ui.alerts.AlertsScreen
import app.clearsms.ui.composemsg.ComposeMessageScreen
import app.clearsms.ui.conversation.ConversationScreen
import app.clearsms.ui.finance.AccountDetailScreen
import app.clearsms.ui.finance.FinanceScreen
import app.clearsms.ui.inbox.InboxScreen
import app.clearsms.ui.onboarding.OnboardingScreen
import app.clearsms.ui.rules.RuleWizardScreen
import app.clearsms.ui.rules.RulesScreen
import app.clearsms.ui.search.SearchScreen
import app.clearsms.ui.settings.LicensesScreen
import app.clearsms.ui.settings.PermissionsInfoScreen
import app.clearsms.ui.settings.PrivacyPolicyScreen
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
    onOnboarded: () -> Unit,
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val state by appViewModel.uiState.collectAsStateWithLifecycle()
    ClearSmsTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColor) {
        when (state.onboardingComplete) {
            null -> Box(Modifier.fillMaxSize()) // settings still loading; avoid flashing a screen
            false -> OnboardingScreen()
            true -> {
                LaunchedEffect(Unit) { onOnboarded() }
                MainScaffold(initialRecipient, initialBody)
            }
        }
    }
}

@Composable
private fun MainScaffold(
    initialRecipient: String?,
    initialBody: String?,
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
    LaunchedEffect(initialRecipient, initialBody) {
        if (!initialRecipient.isNullOrBlank() || !initialBody.isNullOrBlank()) {
            navController.navigate(Routes.compose(initialRecipient, initialBody))
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
            startDestination = Routes.INBOX,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.INBOX) {
                InboxScreen(
                    onOpenThread = { threadId -> navController.navigate(Routes.conversation(threadId)) },
                    onCompose = { navController.navigate(Routes.compose()) },
                    onSearch = { navController.navigate(Routes.SEARCH) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onCreateRule = { sender, body -> navController.navigate(Routes.ruleWizard(sender, body)) },
                )
            }
            composable(Routes.FINANCE) {
                FinanceScreen(
                    onOpenAccount = { number, bank -> navController.navigate(Routes.accountDetail(number, bank)) },
                )
            }
            composable(Routes.ALERTS) { AlertsScreen() }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onOpenThread = { threadId -> navController.navigate(Routes.conversation(threadId)) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.CONVERSATION,
                arguments = listOf(navArgument("threadId") { type = NavType.LongType }),
            ) {
                ConversationScreen(
                    onBack = { navController.popBackStack() },
                    onCreateRule = { sender, body -> navController.navigate(Routes.ruleWizard(sender, body)) },
                )
            }
            composable(
                route = Routes.COMPOSE,
                arguments =
                    listOf(
                        navArgument("recipient") { defaultValue = "" },
                        navArgument("body") { defaultValue = "" },
                    ),
            ) {
                ComposeMessageScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.ACCOUNT_DETAIL,
                arguments =
                    listOf(
                        navArgument("accountNumber") { type = NavType.StringType },
                        navArgument("bank") { defaultValue = "" },
                    ),
            ) {
                AccountDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onManageRules = { navController.navigate(Routes.RULES) },
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
                )
            }
            composable(
                route = Routes.RULE_WIZARD,
                arguments =
                    listOf(
                        navArgument("sender") { defaultValue = "" },
                        navArgument("body") { defaultValue = "" },
                    ),
            ) {
                RuleWizardScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
