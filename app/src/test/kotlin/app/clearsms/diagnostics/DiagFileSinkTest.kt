package app.clearsms.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagFileSinkTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `lines written by one process are restored by the next, oldest first`() {
        val dir = temp.newFolder("diag")
        val first = DiagFileSink(dir)
        first.append(DiagLine(1_000L, "I App process start"))
        first.append(DiagLine(2_000L, "E Crash uncaught exception\n  ! java.lang.IllegalStateException\n      at a.B.c(B.kt:1)"))

        val restored = DiagFileSink(dir).restore()

        assertThat(restored)
            .containsExactly(
                DiagLine(1_000L, "I App process start"),
                DiagLine(2_000L, "E Crash uncaught exception\n  ! java.lang.IllegalStateException\n      at a.B.c(B.kt:1)"),
            ).inOrder()
    }

    @Test
    fun `rotation keeps at most two files and drops the oldest content`() {
        val dir = temp.newFolder("diag")
        val sink = DiagFileSink(dir, maxFileBytes = 200)
        // ~40 bytes per encoded line: 200-byte files hold ~5 lines each, so
        // 30 appends rotate several times and only the last two files survive.
        (1..30).forEach { sink.append(DiagLine(it.toLong(), "I T line-$it-" + "x".repeat(20))) }

        assertThat(dir.listFiles()!!.map { it.name }.sorted()).containsExactly("diag.0.log", "diag.1.log")
        val restored = sink.restore()
        assertThat(restored.map { it.timestampMs }).isInOrder()
        assertThat(restored.last().timestampMs).isEqualTo(30L)
        assertThat(restored.first().timestampMs).isGreaterThan(1L)
        assertThat(restored.size).isAtLeast(5)
        assertThat(restored.size).isAtMost(12)
    }

    @Test
    fun `garbage in the file is skipped, valid entries survive`() {
        val dir = temp.newFolder("diag")
        val sink = DiagFileSink(dir)
        sink.append(DiagLine(5L, "I T ok"))
        dir.resolve("diag.0.log").appendText("not a valid entry\n\n")
        sink.append(DiagLine(6L, "I T also ok"))
        assertThat(sink.restore().map { it.text }).containsExactly("I T ok", "I T also ok").inOrder()
    }

    @Test
    fun `install restores the file into the live buffer and mirrors new entries to disk`() {
        val dir = temp.newFolder("diag")
        DiagFileSink(dir).append(DiagLine(1L, "I App previous process"))

        try {
            Diag.install(DiagFileSink(dir))
            assertThat(Diag.buffer.snapshot().map { it.text }).contains("I App previous process")

            Diag.record(DiagLevel.INFO, "T", "after install", emptyList(), null, nowMs = 2L)
            Diag.flushSync()
            assertThat(DiagFileSink(dir).restore().map { it.timestampMs }).containsAtLeast(1L, 2L).inOrder()
        } finally {
            // Diag is process-global: detach so later tests do not write here.
            Diag.install(null)
        }
    }
}
