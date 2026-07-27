package app.clearsms.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

/**
 * Helpers for checking and requesting the default-SMS-app role.
 *
 * On Android Q+ the [RoleManager] API is used; earlier releases fall back to
 * [Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT].
 */
object DefaultSmsAppHelper {
    /** True when Clear SMS is currently the user's default SMS app. */
    fun isDefaultSmsApp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            return roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        }
        return context.packageName == Telephony.Sms.getDefaultSmsPackage(context)
    }

    /**
     * Intent that opens the system dialog asking the user to make Clear SMS
     * the default SMS app. Launch with an activity result launcher.
     */
    fun createRequestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = requireNotNull(context.getSystemService(RoleManager::class.java))
            return roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
        }
        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
    }
}
