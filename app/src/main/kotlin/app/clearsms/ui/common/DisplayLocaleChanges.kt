package app.clearsms.ui.common

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits every time the display locale (the app language) changes while the
 * process is alive - the Android 13+ per-app language picker, or the system
 * language - so a view model that PRE-FORMATS text from resources can redo
 * it.
 *
 * Why this exists: the inbox, bin, archive and conversation view models
 * build each row's time label ("Yesterday", "Tue") once, on the IO
 * dispatcher, when the database emits - deliberately, so a fling never
 * formats dates during composition (this app benchmarks list scrolling).
 * The price is that the label is a snapshot of the language at mapping
 * time: after a language switch the words in composition flip at once
 * (they are `stringResource` reads) while rows keep the old language until
 * the next database write. Rather than move formatting into the row - a
 * `remember` per row on every page, paid on every scroll, for an event
 * that happens a handful of times in an install's life - the view models
 * collect this flow and re-run their existing mapping once per change:
 * one formatting path, no per-recomposition work, and the refresh costs
 * exactly one page reload.
 *
 * Registered on the application (a [ComponentCallbacks]), so it fires for
 * a background process too, after the application's resources have taken
 * the new configuration - a row mapped from inside the collector is already
 * in the new language. Unregisters when the collector is cancelled.
 */
fun Context.displayLocaleChanges(): Flow<Unit> =
    callbackFlow {
        val application = applicationContext
        var locales = ConfigurationCompat.getLocales(application.resources.configuration)
        val callbacks =
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    val now: LocaleListCompat = ConfigurationCompat.getLocales(newConfig)
                    if (now == locales) return
                    locales = now
                    trySend(Unit)
                }

                override fun onLowMemory() = Unit
            }
        application.registerComponentCallbacks(callbacks)
        awaitClose { application.unregisterComponentCallbacks(callbacks) }
    }
