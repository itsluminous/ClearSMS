package app.clearsms.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.FinanceTab
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.domain.model.StartDestination
import app.clearsms.domain.model.SwipeAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryImplTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dataStore: DataStore<Preferences>

    private fun repository(): SettingsRepositoryImpl {
        dataStore =
            PreferenceDataStoreFactory.create(scope = scope) {
                tmp.newFile("settings.preferences_pb")
            }
        return SettingsRepositoryImpl(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults when nothing is stored`() =
        runBlocking {
            val repo = repository()
            assertThat(repo.showRichAvatars.first()).isTrue()
            assertThat(repo.notificationActions.first())
                .isEqualTo(setOf(NotificationAction.MARK_READ, NotificationAction.REPLY))
            assertThat(repo.swipeActionStart.first()).isEqualTo(SwipeAction.ARCHIVE)
            assertThat(repo.swipeActionEnd.first()).isEqualTo(SwipeAction.DELETE)
            assertThat(repo.defaultDestination.first()).isEqualTo(StartDestination.INBOX)
            assertThat(repo.defaultInboxFilter.first()).isEqualTo(Category.IMPORTANT)
            assertThat(repo.financeTab.first()).isEqualTo(FinanceTab.ACCOUNTS)
            assertThat(repo.transactionNotifications.first()).isTrue()
        }

    @Test
    fun `showRichAvatars round trips`() =
        runBlocking {
            val repo = repository()
            repo.setShowRichAvatars(false)
            assertThat(repo.showRichAvatars.first()).isFalse()
            repo.setShowRichAvatars(true)
            assertThat(repo.showRichAvatars.first()).isTrue()
        }

    @Test
    fun `legacy stored otp display sizes migrate without an unset state`() =
        runBlocking {
            val repo = repository()
            val key = stringPreferencesKey("otp_display_size")
            // Fresh install: nothing stored → Option 2.
            assertThat(repo.otpDisplaySize.first()).isEqualTo(OtpDisplaySize.OPTION_2)
            // A user who had the old "Default" entry lands on Option 2.
            dataStore.edit { it[key] = "DEFAULT" }
            assertThat(repo.otpDisplaySize.first()).isEqualTo(OtpDisplaySize.OPTION_2)
            // Old lettered values land on the equivalent numbered option.
            dataStore.edit { it[key] = "OPTION_A" }
            assertThat(repo.otpDisplaySize.first()).isEqualTo(OtpDisplaySize.OPTION_1)
            dataStore.edit { it[key] = "OPTION_D" }
            assertThat(repo.otpDisplaySize.first()).isEqualTo(OtpDisplaySize.OPTION_5)
            // New values round-trip through the setter.
            repo.setOtpDisplaySize(OtpDisplaySize.OPTION_4)
            assertThat(repo.otpDisplaySize.first()).isEqualTo(OtpDisplaySize.OPTION_4)
        }

    @Test
    fun `notificationActions round trips including empty set`() =
        runBlocking {
            val repo = repository()
            val chosen = setOf(NotificationAction.COPY_OTP, NotificationAction.SHARE_OTP, NotificationAction.DELETE)
            repo.setNotificationActions(chosen)
            assertThat(repo.notificationActions.first()).isEqualTo(chosen)
            repo.setNotificationActions(emptySet())
            assertThat(repo.notificationActions.first()).isEmpty()
        }

    @Test
    fun `swipe actions round trip`() =
        runBlocking {
            val repo = repository()
            repo.setSwipeActionStart(SwipeAction.TOGGLE_READ)
            repo.setSwipeActionEnd(SwipeAction.NONE)
            assertThat(repo.swipeActionStart.first()).isEqualTo(SwipeAction.TOGGLE_READ)
            assertThat(repo.swipeActionEnd.first()).isEqualTo(SwipeAction.NONE)
        }

    @Test
    fun `defaultDestination round trips`() =
        runBlocking {
            val repo = repository()
            repo.setDefaultDestination(StartDestination.FINANCE)
            assertThat(repo.defaultDestination.first()).isEqualTo(StartDestination.FINANCE)
        }

    @Test
    fun `defaultInboxFilter round trips including All`() =
        runBlocking {
            val repo = repository()
            repo.setDefaultInboxFilter(Category.PERSONAL)
            assertThat(repo.defaultInboxFilter.first()).isEqualTo(Category.PERSONAL)
            repo.setDefaultInboxFilter(null)
            assertThat(repo.defaultInboxFilter.first()).isNull()
        }

    @Test
    fun `financeTab round trips`() =
        runBlocking {
            val repo = repository()
            repo.setFinanceTab(FinanceTab.TRANSACTIONS)
            assertThat(repo.financeTab.first()).isEqualTo(FinanceTab.TRANSACTIONS)
        }

    @Test
    fun `transactionNotifications round trips`() =
        runBlocking {
            val repo = repository()
            repo.setTransactionNotifications(false)
            assertThat(repo.transactionNotifications.first()).isFalse()
        }

    @Test
    fun `corrupt enum values fall back to defaults without throwing`() =
        runBlocking {
            val repo = repository()
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("swipe_action_start")] = "FLING_INTO_ORBIT"
                prefs[stringPreferencesKey("swipe_action_end")] = ""
                prefs[stringPreferencesKey("default_destination")] = "inbox"
                prefs[stringPreferencesKey("default_inbox_filter")] = "NOT_A_CATEGORY"
                prefs[stringPreferencesKey("finance_tab")] = "42"
            }
            assertThat(repo.swipeActionStart.first()).isEqualTo(SwipeAction.ARCHIVE)
            assertThat(repo.swipeActionEnd.first()).isEqualTo(SwipeAction.DELETE)
            assertThat(repo.defaultDestination.first()).isEqualTo(StartDestination.INBOX)
            assertThat(repo.defaultInboxFilter.first()).isEqualTo(Category.IMPORTANT)
            assertThat(repo.financeTab.first()).isEqualTo(FinanceTab.ACCOUNTS)
        }

    @Test
    fun `unknown notification action names are dropped, valid ones kept`() =
        runBlocking {
            val repo = repository()
            dataStore.edit { prefs ->
                prefs[stringSetPreferencesKey("notification_actions")] =
                    setOf("REPLY", "SELF_DESTRUCT", "copy_otp")
            }
            assertThat(repo.notificationActions.first()).isEqualTo(setOf(NotificationAction.REPLY))
        }
}
