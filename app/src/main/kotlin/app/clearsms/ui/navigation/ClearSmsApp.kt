package app.clearsms.ui.navigation

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import app.clearsms.domain.model.EnabledSections
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

private data class BottomDestination(
    val tab: StartDestination,
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
    /**
     * Intents delivered after this composition started - a notification tap
     * while the app is already running. The graph only resolves deep links
     * from the intent the NavController was built with, so these are handed to
     * it explicitly or the tap does nothing.
     */
    laterIntents: Flow<Intent> = emptyFlow(),
    /**
     * The (sanitized) intent this activity was CREATED with - the cold-start
     * notification tap. The graph declares no `navDeepLink`s: NavController's
     * built-in handling would plain-push the destination, which corrupts the
     * bottom bar's saved tab state (see [LaterIntentAction.Navigate.selectTab]),
     * so cold starts navigate through the same triage as a warm tap.
     */
    initialIntent: Intent? = null,
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
                        laterIntents = laterIntents,
                        initialIntent = initialIntent,
                        // The START destination must be an ENABLED section
                        // (cold start included): the stored preference wins
                        // while its section is on, else the first enabled tab.
                        startDestination = state.sections.resolveStart(state.defaultDestination),
                        sections = state.sections,
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
    laterIntents: Flow<Intent>,
    initialIntent: Intent?,
    /** Already resolved against [sections]: always an enabled tab. */
    startDestination: StartDestination,
    sections: EnabledSections,
    navController: NavHostController = rememberNavController(),
) {
    val destinations =
        listOf(
            BottomDestination(StartDestination.INBOX, Routes.INBOX, Icons.Outlined.ChatBubbleOutline, R.string.nav_inbox),
            BottomDestination(StartDestination.FINANCE, Routes.FINANCE, Icons.Outlined.AccountBalanceWallet, R.string.nav_finance),
            BottomDestination(StartDestination.ALERTS, Routes.ALERTS, Icons.Outlined.Notifications, R.string.nav_alerts),
        ).filter { sections.isEnabled(it.tab) }
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

    // Cold-start notification tap: the deep link in the creation intent,
    // navigated through the SAME triage as a warm tap so a tab-targeted
    // link selects its tab (a share/compose intent is handled above; the
    // graph declares no navDeepLinks - see ClearSmsApp's initialIntent doc).
    // Consumed once PER LINK, keyed on the deep-link uri: a plain boolean
    // guard survives process death in the saved state and then swallowed a
    // FRESH notification tap that recreated the killed activity (the tap
    // landed on the start tab instead of its conversation). Keying on the
    // uri keeps what the boolean was for - a rotation or recents relaunch
    // replays the SAME intent, so its uri matches and is skipped - while a
    // new tap carries a new uri and navigates.
    var consumedInitialUri by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val uri = initialIntent?.dataString
        if (uri != null && uri != consumedInitialUri) {
            consumedInitialUri = uri
            val action = initialIntent.let(LaterIntentTriage::classify)
            if (action is LaterIntentAction.Navigate) navController.navigateDeepLink(action, sections)
        }
    }

    // Intents delivered while the activity was already alive (notification
    // taps and shares into a running app - MainActivity.onNewIntent). The
    // graph never sees them by itself, so translate each into explicit
    // navigation here or the tap/share silently does nothing (issue #8).
    val context = LocalContext.current
    LaunchedEffect(navController) {
        laterIntents.collect { intent ->
            when (val action = LaterIntentTriage.classify(intent)) {
                is LaterIntentAction.Navigate -> navController.navigateDeepLink(action, sections)
                is LaterIntentAction.OpenCompose -> {
                    if (action.rejectedAttachment) {
                        // Same courtesy as the onCreate path: never fail a
                        // share silently.
                        Toast.makeText(context, R.string.share_only_images, Toast.LENGTH_LONG).show()
                    }
                    action.route?.let { navController.navigate(it) }
                }
                LaterIntentAction.None -> Unit
            }
        }
    }

    // v0.17.2 lesson: tab switches keep per-tab back-stack state alive via
    // popUpTo(start){saveState} + restoreState, so a section that gets
    // DISABLED must be scrubbed from both places or its stale state can
    // resurface later:
    //  - clearBackStack drops the SAVED (popped) state, so no restoreState
    //    navigation can ever resurrect the hidden tab's stack;
    //  - if the tab is still on the ACTIVE stack (it is the screen beneath
    //    the Settings screen the toggle was flipped on), the stack is
    //    rebuilt onto the resolved start tab without saving the disabled
    //    tab's state - and Settings is re-pushed so the user stays exactly
    //    where they toggled.
    // Runs on the first composition too, where it is a no-op (nothing
    // disabled is in the stack of a fresh NavController).
    LaunchedEffect(sections) {
        StartDestination.entries.filterNot(sections::isEnabled).forEach { tab ->
            val route = tab.toRoute()
            navController.clearBackStack(route)
            val onActiveStack = runCatching { navController.getBackStackEntry(route) }.isSuccess
            if (onActiveStack) {
                val settingsWasOnTop = navController.currentDestination?.route == Routes.SETTINGS
                navController.navigate(startDestination.toRoute()) {
                    popUpTo(route) {
                        inclusive = true
                        saveState = false
                    }
                    launchSingleTop = true
                }
                if (settingsWasOnTop) navController.navigate(Routes.settings())
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // Single-owner rule for system-bar insets: the BARS own them. Each
        // screen's TopAppBar pads for the status bar and this scaffold's
        // NavigationBar pads for the system navigation bar. The default
        // contentWindowInsets would pad the NavHost by the same system bars
        // AGAIN (an empty strip under the status bar on every screen, and -
        // with 3-button navigation - a second strip above the bottom bar),
        // so the shell contributes none of its own.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            // With fewer than two enabled sections there is nothing to switch
            // between: a single-item bar is dead chrome, so the whole bar
            // disappears (the remaining screen keeps Search and Settings in
            // its own top bar, which is also the way back to re-enabling).
            if (currentRoute in Routes.topLevel && sections.showBottomBar) {
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
            startDestination = startDestination.toRoute(),
            // padding is the bottom bar's height (which already includes the
            // navigation-bar inset). consumeWindowInsets is the half that
            // Modifier.padding lacks: without it every screen's own Scaffold
            // still sees the full navigationBars inset and pads its content
            // by it a second time - the device-dependent dead strip above
            // the bottom bar. On routes without the bottom bar padding is
            // zero, so nothing is consumed and those screens keep handling
            // their own insets end to end (the conversation composer's
            // ime/navigation-bar reads are untouched).
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
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
            composable(Routes.ALERTS) {
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

/** The nav route rendering a top-level tab. */
private fun StartDestination.toRoute(): String =
    when (this) {
        StartDestination.INBOX -> Routes.INBOX
        StartDestination.FINANCE -> Routes.FINANCE
        StartDestination.ALERTS -> Routes.ALERTS
    }

/**
 * Navigates a notification deep link. A route targeting a bottom-bar tab is
 * selected exactly like a bottom-bar tap - the same options the
 * NavigationBarItem onClick uses - so it can never be swept into another
 * tab's saved back stack (see [LaterIntentAction.Navigate.selectTab]). The
 * enabled-sections decision itself lives in [LaterIntentTriage.resolve]
 * (pure, unit-tested): a tab whose section is DISABLED redirects to the
 * resolved start tab; everything else (a conversation, with its optional
 * `?messageId=` highlight) keeps the plain push it always had.
 */
private fun NavHostController.navigateDeepLink(
    action: LaterIntentAction.Navigate,
    sections: EnabledSections,
) {
    val resolved = LaterIntentTriage.resolve(action, sections)
    if (resolved.selectTab) {
        navigate(resolved.route) {
            popUpTo(graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    } else {
        navigate(resolved.route)
    }
}
