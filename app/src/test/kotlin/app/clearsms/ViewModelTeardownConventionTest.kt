package app.clearsms

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Guards against a view model's coroutine outliving its test's Room
 * database, a suite-wide flake.
 *
 * WHY: `viewModelScope` is cancelled only by the framework (owner
 * destroyed). A test that builds a ViewModel by hand against an in-memory
 * database and then `db.close()`s in `@After` leaves that scope alive, and
 * Room runs suspending DAO work on its own executor threads - so a query the
 * view model still has in flight (a send's delivery watcher, a paging
 * source, a `stateIn` collector) can execute AFTER the database is gone.
 * `IllegalStateException: ... connection pool has been closed` then escapes
 * the scope (it has no handler) as an uncaught coroutine exception, and
 * kotlinx-coroutines-test fails whichever unrelated `runTest` comes next
 * with `UncaughtExceptionsBeforeTest` - the same victim pattern as
 * WorkManagerTeardownConventionTest and SenderIdStoreIsolationConventionTest.
 * It is pure scheduling, so it was green locally and red on CI (PR #108:
 * `SentMessageWatcherTest` the innocent victim of
 * `ConversationViewModelDelayedSendTest`'s immediate send).
 *
 * THE RULE: every test file that constructs a ViewModel AND opens an
 * in-memory Room database goes through `ViewModelDatabaseFixture` - the
 * database is built with `fixture.onDatabase(...)`, every view model is
 * registered with `fixture.own(...)`, and teardown is `fixture.close(db)`
 * (clear the view models, drain Room's executor, THEN close), never a bare
 * `db.close()`. ConversationViewModelTeardownTest proves the order matters.
 */
class ViewModelTeardownConventionTest {
    @Test
    fun `every test building a ViewModel against an in-memory database tears down through the fixture`() {
        val sourceRoot = File("src/test/kotlin")
        assertWithMessage("test must run from the app module").that(sourceRoot.isDirectory).isTrue()
        val violations = mutableListOf<String>()
        sourceRoot
            .walkTopDown()
            // This file quotes the rule's own vocabulary; it builds nothing.
            .filter { it.isFile && it.extension == "kt" && it.name != "ViewModelTeardownConventionTest.kt" }
            .forEach { file ->
                val text = file.readText()
                if (!text.contains("inMemoryDatabaseBuilder") || !CONSTRUCTS_VIEW_MODEL.containsMatchIn(text)) return@forEach
                val problems =
                    listOfNotNull(
                        "does not build the database with fixture.onDatabase(...)".takeUnless {
                            text.contains(".onDatabase(")
                        },
                        "does not register its view model with fixture.own(...)".takeUnless { text.contains(".own(") },
                        "does not tear down with fixture.close(db)".takeUnless { text.contains(".close(db)") },
                        "closes the database directly with db.close()".takeIf {
                            // The proof test closes the database under a live view model ON PURPOSE.
                            file.name != "ConversationViewModelTeardownTest.kt" && CLOSES_DIRECTLY.containsMatchIn(text)
                        },
                    )
                if (problems.isNotEmpty()) {
                    violations += "${file.toRelativeString(sourceRoot)}: ${problems.joinToString("; ")}"
                }
            }
        assertWithMessage(
            "These test files build a ViewModel against an in-memory Room database " +
                "without ViewModelDatabaseFixture. A view model's coroutines are only " +
                "cancelled by the framework, so a DAO call still in flight on Room's " +
                "executor runs after db.close(), and the uncaught exception fails an " +
                "unrelated later runTest (UncaughtExceptionsBeforeTest). Use:\n" +
                "  private val fixture = ViewModelDatabaseFixture()\n" +
                "  db = fixture.onDatabase(Room.inMemoryDatabaseBuilder(...))....build()\n" +
                "  return fixture.own(viewModel)\n" +
                "  @After fun tearDown() { fixture.close(db) }",
        ).that(violations).isEmpty()
    }

    private companion object {
        /** A `SomethingViewModel(` constructor call, in short or qualified form. */
        val CONSTRUCTS_VIEW_MODEL = Regex("""\b[A-Z]\w*ViewModel\s*\(""")

        /** `db.close()` outside the fixture - the teardown that races the view model. */
        val CLOSES_DIRECTLY = Regex("""\bdb\s*\.\s*close\(\)""")
    }
}
