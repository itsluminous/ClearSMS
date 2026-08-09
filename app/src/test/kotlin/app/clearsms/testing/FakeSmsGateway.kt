package app.clearsms.testing

import android.app.PendingIntent
import app.clearsms.sms.SmsGateway

/**
 * Recording fake for [SmsGateway], replacing the old
 * `DividingShadowSmsManager` Robolectric shadow: that shadow faked
 * `SmsManager.divideMessage` (real framework division code cannot run on
 * the JVM) with single-part division, but registering it via
 * `@Config(shadows = ...)` split the Robolectric sandbox per test class and
 * re-triggered the native-runtime extraction race (see
 * RobolectricSandboxConventionTest).
 *
 * Like the shadow, division is single-part; sends are recorded for
 * assertion. An empty destination throws [IllegalArgumentException],
 * mirroring the framework's (and ShadowSmsManager's) synchronous rejection.
 */
class FakeSmsGateway : SmsGateway {
    data class MultipartSend(
        val subscriptionId: Int?,
        val destination: String,
        val parts: List<String>,
        val sentIntents: List<PendingIntent>,
        val deliveryIntents: List<PendingIntent?>,
    )

    val sends = mutableListOf<MultipartSend>()

    val lastSend: MultipartSend? get() = sends.lastOrNull()

    override fun divideMessage(
        subscriptionId: Int?,
        body: String,
    ): ArrayList<String> = arrayListOf(body)

    override fun sendMultipartTextMessage(
        subscriptionId: Int?,
        destination: String,
        parts: ArrayList<String>,
        sentIntents: ArrayList<PendingIntent>,
        deliveryIntents: ArrayList<PendingIntent?>,
    ) {
        require(destination.isNotEmpty()) { "Invalid destinationAddress" }
        sends += MultipartSend(subscriptionId, destination, parts.toList(), sentIntents.toList(), deliveryIntents.toList())
    }
}
