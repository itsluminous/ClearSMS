package app.clearsms.sms

import android.content.Context
import android.provider.Telephony
import app.clearsms.diagnostics.Diag
import app.clearsms.diagnostics.DiagField.Companion.flag
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform's conversation id for an address ABOUT to be written to -
 * the same `thread_id` the provider stamps on the row once it is sent, so
 * a lookup and a later send agree on the thread (see
 * [app.clearsms.data.repository.ThreadIdentity]). Null when the platform
 * cannot say: the caller then falls back to the app's sender key.
 */
fun interface ProviderThreadLookup {
    fun threadIdFor(address: String): Long?
}

/**
 * [ProviderThreadLookup] over `Telephony.Threads` - the provider's own
 * canonical matching, which is what merges `+48 601 234 567` with
 * `601 234 567` (issue #42). The platform offers no read-only form: it
 * returns the thread the address already has, else allocates an empty
 * one that holds no message and that no app lists - the same call every
 * stock messaging app makes when a recipient is picked, and the thread
 * the eventual send would create anyway. Failures (permission, no
 * telephony) degrade to null.
 */
@Singleton
class SystemProviderThreadLookup
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ProviderThreadLookup {
        override fun threadIdFor(address: String): Long? =
            try {
                Telephony.Threads.getOrCreateThreadId(context, address).takeIf { it > 0L }
            } catch (e: Exception) {
                Diag.w(TAG, "provider thread lookup failed; falling back to sender key", e, flag("denied", e is SecurityException))
                null
            }

        private companion object {
            const val TAG = "ProviderThreadLookup"
        }
    }
