package app.clearsms.data.senderid

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Guards against unit tests opening the real bundled sender directory, a
 * suite-wide flake.
 *
 * WHY: `sender_ids.db` is a 44 MB asset. Robolectric does not stream a
 * compressed asset - `_CompressedAsset.getBuffer` inflates ALL of it into
 * one heap `byte[]` (`FileMap.getDataPtr`), a G1 humongous allocation
 * inside the forked test JVM's default 512 MB heap. Every Robolectric test
 * gets a fresh Application (fresh `filesDir`), so each test that reaches
 * `SenderIdStore.lookup()` pays that 44 MB again; with fifty-odd such
 * tests the suite peaked at 91% of the heap even locally, and on the slow
 * 2-vCPU CI runner one of them failed: `OutOfMemoryError: Java heap space`
 * thrown from `ConversationViewModel`'s `stateIn` coroutine (CI run
 * 36289688938). Its `uiState` never loaded (`awaitUntil` timed out ->
 * `IllegalStateException` in ConversationViewModelScheduleTest), and the
 * error escaped as an UNCAUGHT coroutine exception, so kotlinx-coroutines
 * -test failed the next unrelated `runTest` with
 * `UncaughtExceptionsBeforeTest` (RulesViewModelDetailTest was the innocent
 * victim) - the same victim pattern as the abandoned WorkManager worker in
 * WorkManagerTeardownConventionTest.
 *
 * THE RULE: production code depends on the `SenderIdLookup` interface, and
 * tests outside this package pass a fake (`SenderIdLookup { null }`). Only
 * the store's own tests, here in `data/senderid`, may construct
 * `SenderIdStore` and touch the asset.
 */
class SenderIdStoreIsolationConventionTest {
    @Test
    fun `only the sender directory's own tests construct SenderIdStore`() {
        val sourceRoot = File("src/test/kotlin")
        assertWithMessage("test must run from the app module").that(sourceRoot.isDirectory).isTrue()
        val ownPackage = File(sourceRoot, "app/clearsms/data/senderid")
        val violations =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.parentFile != ownPackage }
                .filter { CONSTRUCTS_STORE.containsMatchIn(it.readText()) }
                .map { it.toRelativeString(sourceRoot) }
                .toList()
        assertWithMessage(
            "These test files construct the asset-backed SenderIdStore. Robolectric " +
                "inflates the whole 44 MB sender_ids.db into one heap byte array per " +
                "test, which exhausted the 512 MB test heap on CI; the OutOfMemoryError " +
                "escaped a ViewModel coroutine and failed an unrelated later runTest " +
                "(UncaughtExceptionsBeforeTest). Pass a fake instead:\n" +
                "  SenderIdLookup { null }",
        ).that(violations).isEmpty()
    }

    private companion object {
        /** `SenderIdStore(` as a constructor call, in short or qualified form. */
        val CONSTRUCTS_STORE = Regex("""\bSenderIdStore\s*\(""")
    }
}
