package app.clearsms

import android.app.Application

/**
 * The Application Robolectric boots for every unit test, replacing
 * [ClearSmsApplication] via Robolectric's `Test`-prefix naming convention
 * (AndroidTestEnvironment.getTestApplicationName: for a manifest application
 * `a.b.App` it first tries `a.b.TestApp`).
 *
 * WHY THIS MUST EXIST: without it, every Robolectric test instantiates the
 * real @HiltAndroidApp [ClearSmsApplication]. Its `onCreate` builds the FULL
 * production Hilt graph and calls `UndoManager.onAppStart()`, which launches
 * a job into the never-cancelled @ApplicationScope (Dispatchers.IO), opens
 * the real Room database (never closed - one CloseGuard "close not called"
 * leak per test; 1286 per suite run as of v0.10.3), and binds the
 * process-wide "settings"/"ui_settings" DataStore delegates to the FIRST
 * test's since-deleted temp dir. Because the whole suite runs in ONE
 * Robolectric sandbox classloader, those leaked jobs, executors and
 * finalizer-driven re-opens accumulate across all ~1300 Robolectric tests
 * and eventually land work / an uncaught exception on the shared
 * "SDK 35 Main Thread" - which on slow CI machines starved a later test's
 * runTest into `UncompletedCoroutinesError` (RulesViewModelDetailTest hang,
 * v0.10.3 release build).
 *
 * Tests construct their own collaborators (fakes, temp-file DataStores,
 * in-memory Room), so nothing here should ever build the production graph.
 * [RobolectricApplicationConventionTest] enforces that this class stays
 * effective.
 */
class TestClearSmsApplication : Application()
