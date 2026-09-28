package app.clearsms.sms

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSubscriptionManager

/**
 * How the platform's two system defaults reach the app: a real id passes
 * through, INVALID_SUBSCRIPTION_ID (no SIM chosen for that role) and the
 * pre-API-24 platform (no public query) both read as unknown. The SDK
 * level is injected rather than pinned with `@Config(sdk = ...)`, which
 * would split the Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceSubscriptionSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        // Static shadow state: leave it as the next test expects to find it.
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
    }

    private fun source(sdkInt: Int = Build.VERSION_CODES.N) = DeviceSubscriptionSource(context, sdkInt)

    @Test
    fun `the default data subscription passes through when the platform has one`() {
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(7)

        assertThat(source().defaultDataSubscriptionId()).isEqualTo(7)
    }

    @Test
    fun `INVALID_SUBSCRIPTION_ID - no data SIM chosen - is unknown, not a SIM`() {
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

        assertThat(source().defaultDataSubscriptionId()).isNull()
    }

    @Test
    fun `below API 24 there is no public query, so the data default is unknown even when the platform has one`() {
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(7)

        assertThat(source(sdkInt = Build.VERSION_CODES.M).defaultDataSubscriptionId()).isNull()
        // API 24 itself is the floor, not above it.
        assertThat(source(sdkInt = Build.VERSION_CODES.N).defaultDataSubscriptionId()).isEqualTo(7)
    }

    @Test
    fun `the SMS and data defaults are read independently - they are different settings`() {
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(3)
        ShadowSubscriptionManager.setDefaultDataSubscriptionId(7)

        val source = source()
        assertThat(source.defaultSmsSubscriptionId()).isEqualTo(3)
        assertThat(source.defaultDataSubscriptionId()).isEqualTo(7)
    }

    @Test
    fun `the SMS default keeps its pre-API-24 and invalid handling`() {
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(3)
        assertThat(source(sdkInt = Build.VERSION_CODES.M).defaultSmsSubscriptionId()).isNull()

        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        assertThat(source().defaultSmsSubscriptionId()).isNull()
    }

    @Test
    fun `validSubscription is the single INVALID filter`() {
        assertThat(DeviceSubscriptionSource.validSubscription(SubscriptionManager.INVALID_SUBSCRIPTION_ID)).isNull()
        assertThat(DeviceSubscriptionSource.validSubscription(0)).isEqualTo(0)
        assertThat(DeviceSubscriptionSource.validSubscription(12)).isEqualTo(12)
    }
}
