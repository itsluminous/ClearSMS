package app.clearsms.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class DiagnosticReportTest {
    private val now = 1_800_000_000_000L

    private val header =
        SystemState(
            androidRelease = "15",
            sdkInt = 35,
            manufacturer = "Acme",
            model = "Phone 2",
            appVersion = "0.20.0",
            versionCode = 69,
            debugBuild = false,
            defaultSmsApp = true,
            permissions = mapOf("READ_SMS" to true, "READ_CONTACTS" to false),
            networkConnected = true,
            networkTransport = "CELLULAR",
            simSlots = 2,
            inboxSection = true,
            financeSection = true,
            alertsSection = false,
            defaultInboxFilter = "IMPORTANT",
            messageCount = 1234,
            ruleCount = 470,
            transactionCount = 88,
            bundledRulesVersion = "1.3",
            timeZone = "Asia/Kolkata",
        )

    private fun line(
        agoMs: Long,
        text: String,
    ) = DiagLine(now - agoMs, text)

    // region header

    @Test
    fun `header carries exactly the allowed keys, in order`() {
        val keys =
            header
                .render()
                .lines()
                .filter { it.isNotBlank() }
                .map { it.substringBefore(':') }
        assertThat(keys).isEqualTo(SystemState.KEYS)
        assertThat(SystemState.KEYS)
            .containsExactly(
                "android",
                "device",
                "app",
                "defaultSmsApp",
                "permissions",
                "network",
                "simSlots",
                "sections",
                "defaultInboxFilter",
                "counts",
                "bundledRulesVersion",
                "timeZone",
            ).inOrder()
    }

    @Test
    fun `header has no slot for an identifier and renders none`() {
        // The data class itself: no property could hold these.
        val properties = SystemState::class.members.map { it.name.lowercase() }
        listOf("imei", "serial", "carrier", "operator", "ssid", "subscriber", "phone", "number", "account", "email", "contact")
            .forEach { forbidden -> assertThat(properties.filter { it.contains(forbidden) }).isEmpty() }

        val text = header.render().lowercase()
        listOf("imei", "serial", "carrier", "operator", "ssid", "subscriber", "@", "+91").forEach {
            assertThat(text).doesNotContain(it)
        }
        // Nothing phone-number-shaped: the only digits are versions and counts.
        assertThat(text).doesNotContainMatch("\\d{7,}")
        // The allowed facts are all there.
        assertThat(text).contains("android: 15 (sdk 35)")
        assertThat(text).contains("device: acme phone 2")
        assertThat(text).contains("app: 0.20.0 (69)")
        assertThat(text).contains("permissions: read_sms=granted read_contacts=denied")
        assertThat(text).contains("network: connected=true transport=cellular")
        assertThat(text).contains("simslots: 2")
        assertThat(text).contains("sections: inbox=true finance=true alerts=false")
        assertThat(text).contains("counts: messages=1234 rules=470 transactions=88")
        assertThat(text).contains("bundledrulesversion: 1.3")
    }

    // endregion

    // region masking

    @Test
    fun `masking is deterministic within a report and keeps the sequence readable`() {
        val lines =
            listOf(
                "I Categorizer classified sender=VM-HDFCBK rule=hdfc-debit category=IMPORTANT",
                "I SmsReceiver ingested sender=AX-AMAZON-S message=1",
                "I Categorizer classified sender=VM-HDFCBK rule=hdfc-credit category=IMPORTANT",
                "I SmsReceiver ingested sender=[phone] message=2",
                "I SmsReceiver ingested sender=HDFCBK message=3",
            )
        val masked = SenderMasker().mask(lines)
        assertThat(masked)
            .containsExactly(
                "I Categorizer classified sender=sender#1 rule=hdfc-debit category=IMPORTANT",
                "I SmsReceiver ingested sender=sender#2 message=1",
                "I Categorizer classified sender=sender#1 rule=hdfc-credit category=IMPORTANT",
                "I SmsReceiver ingested sender=[phone] message=2",
                // A different spelling is a different placeholder: nothing
                // here normalises, so nothing can be reversed.
                "I SmsReceiver ingested sender=sender#3 message=3",
            ).inOrder()
        masked.forEach {
            assertThat(it).doesNotContain("HDFCBK")
            assertThat(it).doesNotContain("AMAZON")
        }
    }

    @Test
    fun `masking is not reversible - the same sender gets a different number in another report`() {
        val a = SenderMasker().mask(listOf("x sender=BBB", "x sender=AAA"))
        val b = SenderMasker().mask(listOf("x sender=AAA", "x sender=BBB"))
        assertThat(a).containsExactly("x sender=sender#1", "x sender=sender#2").inOrder()
        assertThat(b).containsExactly("x sender=sender#1", "x sender=sender#2").inOrder()
    }

    @Test
    fun `report toggles masking and records the choice in its head`() {
        val lines = listOf(line(1_000, "I T e sender=VM-HDFCBK"))
        val masked = DiagnosticReport.build(header, lines, ReportWindow.EVERYTHING, maskSenders = true, nowMs = now)
        val plain = DiagnosticReport.build(header, lines, ReportWindow.EVERYTHING, maskSenders = false, nowMs = now)
        assertThat(masked).contains("senderIds: masked")
        assertThat(masked).contains("sender=sender#1")
        assertThat(masked).doesNotContain("HDFCBK")
        assertThat(plain).contains("senderIds: as recorded")
        assertThat(plain).contains("sender=VM-HDFCBK")
    }

    // endregion

    // region window, cap, zip

    @Test
    fun `report keeps only lines inside the window and says which window`() {
        val lines =
            listOf(
                line(2 * 60_000L, "recent"),
                line(20 * 60_000L, "older"),
                line(3 * 60 * 60_000L, "ancient"),
            )
        val fifteen = DiagnosticReport.build(header, lines, ReportWindow.LAST_15_MINUTES, false, now)
        assertThat(fifteen).contains("window: last 15 minutes")
        assertThat(fifteen).contains("entries: 1")
        assertThat(fifteen).contains("\nrecent\n")
        assertThat(fifteen).doesNotContain("older")
        val all = DiagnosticReport.build(header, lines, ReportWindow.EVERYTHING, false, now)
        assertThat(all).contains("entries: 3")
        assertThat(all).contains("ancient")
    }

    @Test
    fun `size cap keeps the header and the newest lines, and says how many were dropped`() {
        val head = "HEAD\n"
        val body = (1..100).map { "line-%03d-".format(it) + "x".repeat(40) } // 50 bytes each incl. newline
        val capped = DiagnosticReport.cap(head, body, maxBytes = 1_000)
        assertThat(capped.toByteArray().size).isAtMost(1_000)
        assertThat(capped).startsWith("HEAD\n[")
        assertThat(capped).contains("older entries dropped to fit")
        assertThat(capped).endsWith("line-100-" + "x".repeat(40) + "\n")
        assertThat(capped).doesNotContain("line-001-")
        // Untouched when it fits.
        assertThat(DiagnosticReport.cap(head, body.take(3), maxBytes = 1_000)).isEqualTo(head + body.take(3).joinToString("\n") + "\n")
    }

    @Test
    fun `a full report never exceeds the cap`() {
        val lines = (1..5_000).map { line(it.toLong(), "I T e n=$it " + "y".repeat(200)) }
        val text = DiagnosticReport.build(header, lines, ReportWindow.EVERYTHING, false, now)
        assertThat(text.toByteArray().size).isAtMost(DiagnosticReport.MAX_BYTES)
        assertThat(text).contains("n=5000")
    }

    @Test
    fun `zip round-trips the exact previewed text under the timestamped entry name`() {
        val text = DiagnosticReport.build(header, listOf(line(1, "I T e sender=VM-HDFCBK")), ReportWindow.EVERYTHING, true, now)
        val bytes = DiagnosticReport.zipBytes(text, now)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = zip.nextEntry
            assertThat(entry.name).isEqualTo(DiagnosticReport.textName(now))
            assertThat(entry.name).isEqualTo("clearsms-diagnostics-2027-01-15-080000Z.txt")
            assertThat(zip.readBytes().toString(Charsets.UTF_8)).isEqualTo(text)
            assertThat(zip.nextEntry).isNull()
        }
    }

    // endregion

    // region file name

    @Test
    fun `file names are timestamped in UTC, stable for a fixed clock, and share one stem`() {
        // 2026-09-28T02:43:51.259Z - the operator's report.
        val at = 1_790_563_431_259L
        assertThat(DiagnosticReport.fileStem(at)).isEqualTo("clearsms-diagnostics-2026-09-28-024351Z")
        assertThat(DiagnosticReport.textName(at)).isEqualTo("clearsms-diagnostics-2026-09-28-024351Z.txt")
        assertThat(DiagnosticReport.zipName(at)).isEqualTo("clearsms-diagnostics-2026-09-28-024351Z.zip")
        assertThat(DiagnosticReport.fileStem(at)).isEqualTo(DiagnosticReport.fileStem(at))
        assertThat(DiagnosticReport.zipName(at).removeSuffix(".zip")).isEqualTo(DiagnosticReport.textName(at).removeSuffix(".txt"))
    }

    @Test
    fun `file names match the documented pattern and contain nothing unsafe`() {
        val pattern = Regex("clearsms-diagnostics-\\d{4}-\\d{2}-\\d{2}-\\d{6}Z\\.(txt|zip)")
        for (at in listOf(0L, now, 1_790_563_431_259L, 4_102_444_799_999L)) {
            for (name in listOf(DiagnosticReport.textName(at), DiagnosticReport.zipName(at))) {
                assertThat(name).matches(pattern.pattern)
                // Filesystem- and mail-safe: no separators, colons, spaces,
                // offsets or anything outside the pattern's alphabet.
                assertThat(name).doesNotContainMatch("[^a-z0-9Z.-]")
                assertThat(name).doesNotContain(":")
                assertThat(name).doesNotContain(" ")
                assertThat(name).doesNotContain("+")
                assertThat(name).doesNotContain("/")
            }
        }
    }

    @Test
    fun `two reports a minute apart get different names`() {
        val first = DiagnosticReport.zipName(now)
        val second = DiagnosticReport.zipName(now + 60_000L)
        assertThat(first).isNotEqualTo(second)
        assertThat(DiagnosticReport.textName(now)).isNotEqualTo(DiagnosticReport.textName(now + 60_000L))
        // And even one second apart.
        assertThat(DiagnosticReport.zipName(now)).isNotEqualTo(DiagnosticReport.zipName(now + 1_000L))
    }

    // endregion
}
