package app.clearsms.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagBufferTest {
    private fun line(
        ts: Long,
        text: String = "I T e n=$ts",
    ) = DiagLine(ts, text)

    @Test
    fun `entry bound evicts the oldest first`() {
        val buffer = DiagBuffer(maxEntries = 3, maxBytes = 1_000_000)
        (1L..5L).forEach { buffer.append(line(it)) }
        assertThat(buffer.snapshot().map { it.timestampMs }).containsExactly(3L, 4L, 5L).inOrder()
        assertThat(buffer.size).isEqualTo(3)
    }

    @Test
    fun `byte bound evicts the oldest first and tracks the running total`() {
        // Each line is 10 chars + newline = 11 bytes; budget fits two.
        val buffer = DiagBuffer(maxEntries = 100, maxBytes = 25)
        buffer.append(line(1, "aaaaaaaaaa"))
        buffer.append(line(2, "bbbbbbbbbb"))
        buffer.append(line(3, "cccccccccc"))
        assertThat(buffer.snapshot().map { it.text }).containsExactly("bbbbbbbbbb", "cccccccccc").inOrder()
        assertThat(buffer.byteCount).isEqualTo(22)
    }

    @Test
    fun `a single oversized line is kept truncated rather than emptying the buffer`() {
        val buffer = DiagBuffer(maxEntries = 10, maxBytes = 16)
        buffer.append(line(1, "x".repeat(100)))
        assertThat(buffer.size).isEqualTo(1)
        assertThat(buffer.byteCount).isAtMost(16)
    }

    @Test
    fun `snapshot filters by time for every report window`() {
        val now = 10_000_000L
        val buffer = DiagBuffer()
        val ages = listOf(1, 4, 10, 30, 90, 600) // minutes ago
        ages.forEach { buffer.append(line(now - it * 60_000L, "age=$it")) }

        fun inWindow(window: ReportWindow) =
            DiagnosticReport
                .build(header(), buffer.snapshot(), window, maskSenders = false, nowMs = now)
                .lines()
                .filter { it.startsWith("age=") }
                .map { it.removePrefix("age=").toInt() }
        assertThat(inWindow(ReportWindow.LAST_5_MINUTES)).containsExactly(1, 4).inOrder()
        assertThat(inWindow(ReportWindow.LAST_15_MINUTES)).containsExactly(1, 4, 10).inOrder()
        assertThat(inWindow(ReportWindow.LAST_HOUR)).containsExactly(1, 4, 10, 30).inOrder()
        assertThat(inWindow(ReportWindow.EVERYTHING)).containsExactly(1, 4, 10, 30, 90, 600).inOrder()
        assertThat(buffer.snapshot(sinceMs = now - 5 * 60_000L)).hasSize(2)
    }

    @Test
    fun `default window is fifteen minutes`() {
        assertThat(ReportWindow.DEFAULT).isEqualTo(ReportWindow.LAST_15_MINUTES)
        assertThat(ReportWindow.entries.map { it.minutes }).containsExactly(5, 15, 60, null).inOrder()
    }

    @Test
    fun `concurrent appends never lose bounds or corrupt lines`() {
        val buffer = DiagBuffer(maxEntries = 500, maxBytes = 20_000)
        val threads = 8
        val perThread = 2_000
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                repeat(perThread) { i -> buffer.append(line(i.toLong(), "t$t-i$i-" + "x".repeat(20))) }
                done.countDown()
            }
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        val snapshot = buffer.snapshot()
        assertThat(snapshot.size).isAtMost(500)
        assertThat(snapshot.sumOf { it.bytes }).isAtMost(20_000)
        assertThat(buffer.byteCount).isEqualTo(snapshot.sumOf { it.bytes })
        snapshot.forEach { assertThat(it.text).matches("t\\d-i\\d+-x{20}") }
    }

    @Test
    fun `default bounds are two thousand entries and 256 KiB`() {
        val buffer = DiagBuffer()
        assertThat(buffer.maxEntries).isEqualTo(2_000)
        assertThat(buffer.maxBytes).isEqualTo(256 * 1024)
    }

    private fun header() =
        SystemState(
            androidRelease = "15",
            sdkInt = 35,
            manufacturer = "Acme",
            model = "Phone",
            appVersion = "0.20.0",
            versionCode = 69,
            debugBuild = false,
            defaultSmsApp = true,
            permissions = mapOf("READ_SMS" to true),
            networkConnected = true,
            networkTransport = "WIFI",
            simSlots = 2,
            inboxSection = true,
            financeSection = true,
            alertsSection = false,
            defaultInboxFilter = "ALL",
            messageCount = 10,
            ruleCount = 5,
            transactionCount = 2,
            bundledRulesVersion = "1.3",
            timeZone = "Asia/Kolkata",
        )
}
