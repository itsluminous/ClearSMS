package app.clearsms.testing

import androidx.core.content.FileProvider

/**
 * androidx [FileProvider] caches its path strategy per authority in a
 * static map. Every Robolectric test gets a fresh data directory, so an
 * entry left by an earlier test points at a directory that no longer
 * exists and `getUriForFile` rejects every file in the new one. Tests that
 * serve a file through the app's provider call this first.
 */
object FileProviderTestSupport {
    @Suppress("UNCHECKED_CAST")
    fun resetPathStrategyCache() {
        runCatching {
            FileProvider::class.java
                .getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null)
                .let { (it as MutableMap<Any, Any>).clear() }
        }
    }
}
