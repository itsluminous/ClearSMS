package app.clearsms.ui.common

import app.clearsms.data.backup.BackupFileNames
import app.clearsms.diagnostics.DiagLevel
import app.clearsms.diagnostics.DiagLine
import app.clearsms.diagnostics.DiagnosticReport
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Source-level contract for GitHub #158: ONE resolver writes every clock.
 *
 * The bug it pins shut: `RelativeTime` hard-coded "HH:mm" while
 * `MessageMetadata` honoured the phone's 12/24-hour setting, so the inbox
 * and the details of the same message disagreed. From now on the only file
 * allowed to spell a clock pattern, or ask the phone whether it is 24-hour,
 * is `ui/common/ClockFormat.kt` - except the three MACHINE formats below,
 * which are pinned to their exact shape and must never learn about the
 * preference. (The repo has no Compose UI test harness; this is text, like
 * the other contract tests.)
 */
class ClockFormatContractTest {
    private val root = File("src/main/kotlin/app/clearsms")

    private fun source(path: String) = File(root, path).readText()

    private fun allSources(): Map<String, String> =
        root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path to it.readText() }

    /**
     * Any date/time pattern string carrying an hour-and-minute clock
     * (`HH:mm`, `h:mm a`, `hh:mm`, `kk:mm`, `HHmm`...), plus the other ways
     * of writing one: `SimpleDateFormat`, the platform `DateFormat`
     * helpers and `DateUtils.FORMAT_SHOW_TIME`.
     */
    private val clockPattern =
        Regex(
            """ofPattern\("[^"]*[HhKk]{1,2}:?mm|SimpleDateFormat|is24HourFormat|DateFormat\.getTimeFormat|FORMAT_SHOW_TIME|DateFormat\.format\(""",
        )

    private val resolver = "ui/common/ClockFormat.kt"

    private val machineFormats =
        mapOf(
            "diagnostics/DiagBuffer.kt" to """DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)""",
            "diagnostics/DiagnosticReport.kt" to """DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss'Z'").withZone(ZoneOffset.UTC)""",
            "data/backup/BackupFileNames.kt" to """SimpleDateFormat(PATTERN, Locale.US)""",
        )

    @Test
    fun `only the resolver spells a clock pattern - every other site goes through it`() {
        val offenders =
            allSources()
                .filterKeys { it != resolver && it !in machineFormats }
                .filterValues { clockPattern.containsMatchIn(it) }
                .keys
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `the resolver owns the four clock patterns and the one is24HourFormat call`() {
        val text = source(resolver)
        assertThat(text).contains("""DateTimeFormatter.ofPattern("HH:mm")""")
        assertThat(text).contains("""DateTimeFormatter.ofPattern("h:mm a")""")
        assertThat(text).contains("""DateTimeFormatter.ofPattern("HH:mm:ss")""")
        assertThat(text).contains("""DateTimeFormatter.ofPattern("h:mm:ss a")""")
        assertThat(Regex("""DateFormat\.is24HourFormat\(""").findAll(text).count()).isEqualTo(1)
        // Follow-system is what `is24HourFormat` resolves; the fixed choices never ask.
        assertThat(text).contains("TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)")
        assertThat(text).contains("TimeFormat.HOUR_12 -> false")
        assertThat(text).contains("TimeFormat.HOUR_24 -> true")
    }

    @Test
    fun `machine formats keep their exact shape and know nothing of the preference`() {
        for ((path, pinned) in machineFormats) {
            val text = source(path)
            assertThat(text).contains(pinned)
            // A backup file name or a log stamp that changed shape with a UI
            // preference would be a real bug: the pruner parses the former
            // and the report reader relies on the latter.
            for (forbidden in listOf("TimeFormat", "ClockFormat", "LocalTimeFormat", "is24Hour", "rememberIs24Hour")) {
                // Whole identifiers only: `DateTimeFormatter` is fine.
                assertThat(Regex("\\b$forbidden\\b").containsMatchIn(text)).isFalse()
            }
        }
        assertThat(source("data/backup/BackupFileNames.kt")).contains("""private const val PATTERN = "yyyyMMddHHmm"""")
    }

    @Test
    fun `machine formats render the same bytes whatever the clock preference could say`() {
        // 2026-07-26 16:59:07.123 UTC - an afternoon, so a 12-hour rendering
        // would be visibly different ("4:59").
        val ms = LocalDateTime.of(2026, 7, 26, 16, 59, 7, 123_000_000).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertThat(DiagLine.render(ms, DiagLevel.INFO, "Test", "event", emptyList(), null).text)
            .startsWith("2026-07-26 16:59:07.123Z I Test event")
        assertThat(DiagnosticReport.fileStem(ms)).isEqualTo("clearsms-diagnostics-2026-07-26-165907Z")
        val defaultZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertThat(BackupFileNames.timestamp(ms)).isEqualTo("202607261659")
            assertThat(BackupFileNames.matches(BackupFileNames.MANUAL_SETTINGS_PREFIX, BackupFileNames.manualSettings(ms))).isTrue()
        } finally {
            TimeZone.setDefault(defaultZone)
        }
    }

    @Test
    fun `every RelativeTime site resolves its Strings with the preference`() {
        val sites =
            listOf(
                "ui/inbox/InboxViewModel.kt",
                "ui/inbox/BinViewModel.kt",
                "ui/inbox/ArchivedViewModel.kt",
                "ui/conversation/ConversationViewModel.kt",
                "ui/search/SearchScreen.kt",
                "ui/finance/FinanceScreen.kt",
                "ui/finance/AccountDetailScreen.kt",
            )
        for (path in sites) {
            val text = source(path)
            assertThat(text).contains("RelativeTime.format(")
            // Either the view model resolved Strings against the preference,
            // or the composable reads them from LocalTimeFormat.
            val resolved =
                Regex("""RelativeTime\.Strings\.from\(\w+, \w+(\.value)?\)""").containsMatchIn(text) ||
                    text.contains("rememberRelativeTimeStrings()")
            assertThat(resolved).isTrue()
            // Never a Strings built without the preference.
            assertThat(Regex("""Strings\.from\(\w+\)""").containsMatchIn(text)).isFalse()
        }
        // The view models re-map loaded rows when the preference changes, so a
        // change repaints without a restart (merged into the language hook).
        for (vm in listOf("ui/inbox/InboxViewModel.kt", "ui/conversation/ConversationViewModel.kt")) {
            val text = source(vm)
            assertThat(text).contains("merge(")
            assertThat(text).contains("timeFormat.drop(1)")
            assertThat(text).contains(".collect { activePagingSource?.invalidate() }")
            assertThat(text).contains("settings.timeFormat.stateIn(viewModelScope, SharingStarted.Eagerly, TimeFormat.SYSTEM)")
        }
        for (vm in listOf("ui/inbox/BinViewModel.kt", "ui/inbox/ArchivedViewModel.kt")) {
            assertThat(source(vm)).contains("settings.timeFormat,")
        }
    }

    @Test
    fun `every MessageMetadata and picker site asks rememberIs24Hour`() {
        val conversation = source("ui/conversation/ConversationScreen.kt")
        // Scheduled-for line and the revealed metadata line.
        assertThat(Regex("""val is24Hour = rememberIs24Hour\(\)""").findAll(conversation).count()).isEqualTo(2)
        // The remembered label is keyed on the flag, so a change repaints it.
        assertThat(conversation).contains("remember(item.id, locale, is24Hour) { MessageMetadata.timestampLabel(")
        assertThat(source("ui/conversation/MessageDetailsDialog.kt")).contains("val is24Hour = rememberIs24Hour()")
        assertThat(source("ui/components/ScheduleTimePicker.kt")).contains("rememberTimePickerState(is24Hour = rememberIs24Hour())")
        // MessageMetadata keeps its pure is24Hour parameter and delegates the clock.
        val metadata = source("ui/conversation/MessageMetadata.kt")
        assertThat(metadata).contains("ClockFormat.clock(then, is24Hour, locale)")
        assertThat(metadata).contains("ClockFormat.clock(then, is24Hour, locale, withSeconds = true)")
    }

    @Test
    fun `the preference is provided once at the root and both composition readers key on it`() {
        assertThat(source("ui/navigation/ClearSmsApp.kt")).contains("LocalTimeFormat provides state.timeFormat")
        val relative = source("ui/common/RelativeTime.kt")
        assertThat(relative).contains("remember(configuration, timeFormat) { RelativeTime.Strings.from(context, timeFormat) }")
        assertThat(source(resolver)).contains("remember(configuration, preference) { ClockFormat.is24Hour(context, preference) }")
    }
}
