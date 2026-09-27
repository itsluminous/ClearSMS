package app.clearsms.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.clearsms.BuildConfig
import app.clearsms.sms.DefaultSmsAppHelper
import java.util.TimeZone

/**
 * Reads the device half of [SystemState] from PUBLIC platform APIs only:
 * `Build` constants, `PackageManager` permission checks, the connectivity
 * transport (never the SSID or operator) and the SIM SLOT count from
 * `TelephonyManager` (a hardware fact needing no permission - subscription
 * details, which would name carriers, are deliberately not read).
 */
object SystemStateCollector {
    /** The runtime permissions the manifest declares, by bare name. */
    val RUNTIME_PERMISSIONS =
        listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS,
        )

    /** The device facts; [appFacts] supplies the preference and count half. */
    fun collect(
        context: Context,
        appFacts: AppFacts,
    ): SystemState {
        val network = network(context)
        return SystemState(
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "?",
            model = Build.MODEL ?: "?",
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            debugBuild = BuildConfig.DEBUG,
            defaultSmsApp = runCatching { DefaultSmsAppHelper.isDefaultSmsApp(context) }.getOrDefault(false),
            permissions =
                RUNTIME_PERMISSIONS.associate { permission ->
                    permission.substringAfterLast('.') to
                        (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
                },
            networkConnected = network?.first,
            networkTransport = network?.second ?: "UNKNOWN",
            simSlots = simSlots(context),
            inboxSection = appFacts.inboxSection,
            financeSection = appFacts.financeSection,
            alertsSection = appFacts.alertsSection,
            defaultInboxFilter = appFacts.defaultInboxFilter,
            messageCount = appFacts.messageCount,
            ruleCount = appFacts.ruleCount,
            transactionCount = appFacts.transactionCount,
            bundledRulesVersion = appFacts.bundledRulesVersion,
            timeZone = TimeZone.getDefault().id,
        )
    }

    /** The app-side facts a repository layer supplies. */
    data class AppFacts(
        val inboxSection: Boolean,
        val financeSection: Boolean,
        val alertsSection: Boolean,
        val defaultInboxFilter: String,
        val messageCount: Int,
        val ruleCount: Int,
        val transactionCount: Int,
        val bundledRulesVersion: String,
    )

    private fun network(context: Context): Pair<Boolean, String>? =
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false to "NONE"
            val transport =
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "OTHER"
                }
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) to transport
        } catch (_: Exception) {
            null
        }

    @Suppress("DEPRECATION")
    private fun simSlots(context: Context): Int? =
        try {
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tm.activeModemCount else tm.phoneCount
        } catch (_: Exception) {
            null
        }
}
