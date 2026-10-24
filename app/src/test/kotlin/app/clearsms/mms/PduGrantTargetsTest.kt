package app.clearsms.mms

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Process
import android.service.carrier.CarrierMessagingService
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The shared grant-target resolver against Robolectric's package manager:
 * it names every carrier messaging service and every telephony-uid package
 * the platform reports, carrier services first, and answers an empty list
 * (never a throw) when the platform knows of none. Issue #135.
 */
@RunWith(RobolectricTestRunner::class)
class PduGrantTargetsTest {
    private lateinit var context: Context
    private lateinit var targets: PduGrantTargets

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        targets = PduGrantTargets(context)
    }

    private fun carrierService(packageName: String): ResolveInfo =
        ResolveInfo().apply {
            serviceInfo =
                ServiceInfo().apply {
                    this.packageName = packageName
                    name = "$packageName.MessagingService"
                }
        }

    @Test
    fun `a bare platform resolves nobody - the fallbacks then carry the grant`() {
        assertThat(targets.resolve()).isEmpty()
    }

    @Test
    fun `carrier messaging services come first, then every package on the telephony uid`() {
        val pm = shadowOf(context.packageManager)
        pm.addResolveInfoForIntent(
            Intent(CarrierMessagingService.SERVICE_INTERFACE),
            listOf(carrierService("com.verizon.messaging.service"), carrierService("com.oem.carrier")),
        )
        pm.setPackagesForUid(Process.PHONE_UID, "com.android.phone", "com.oem.telephony")

        assertThat(targets.resolve())
            .containsExactly("com.verizon.messaging.service", "com.oem.carrier", "com.android.phone", "com.oem.telephony")
            .inOrder()
    }

    @Test
    fun `the same resolver feeds the grant decision - resolved targets are granted before the AOSP fallbacks`() {
        val pm = shadowOf(context.packageManager)
        pm.addResolveInfoForIntent(Intent(CarrierMessagingService.SERVICE_INTERFACE), carrierService("com.verizon.messaging.service"))

        val attempted = mutableListOf<String>()
        val outcomes = PduGrant(targets::resolve) { attempted += it }.grantAll()

        assertThat(attempted).containsExactly("com.verizon.messaging.service", "com.android.phone", "com.android.mms.service").inOrder()
        assertThat(outcomes.single { it.packageName == "com.verizon.messaging.service" }.resolved).isTrue()
    }
}
