package app.clearsms.data.prefs

import android.content.Context
import app.clearsms.domain.model.CurrencyCatalog
import app.clearsms.sms.SenderRegion
import java.util.Currency
import java.util.Locale

/**
 * The device's HOME currency - the fallback amounts are read in when a
 * message names no currency (see
 * [app.clearsms.domain.parser.CurrencyDetector]). The SIM's country decides
 * first (the bank texting the user is in the SIM's market), then the device
 * locale; a device that can say neither keeps the app's historic rupee
 * default. Read once per process, like [SenderRegion]: a SIM swap is a
 * restart-level event and the Settings override exists for everything else.
 */
object DeviceCurrency {
    fun detect(context: Context): String = resolve(SenderRegion.detect(context), Locale.getDefault())

    /** Pure resolution so the precedence is unit-testable without a device. */
    fun resolve(
        simRegion: String?,
        locale: Locale,
    ): String =
        currencyOfRegion(simRegion)
            ?: currencyOfRegion(locale.country.takeIf { it.isNotBlank() })
            ?: CurrencyCatalog.INR_CODE

    /** ISO 4217 code of an ISO 3166 region, or null for an unknown / currency-less region. */
    fun currencyOfRegion(region: String?): String? {
        if (region.isNullOrBlank() || region.length != 2) return null
        return try {
            Currency.getInstance(Locale("", region.uppercase()))?.currencyCode
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
