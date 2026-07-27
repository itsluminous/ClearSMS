package app.clearsms.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsSearchTest {
    private data class Row(
        val section: String,
        val title: String,
        val summary: String,
    )

    private val rows =
        listOf(
            Row("Backup", "Back up now", "Export messages and settings to a file"),
            Row("Backup", "Automatic backup", "Off"),
            Row("Appearance", "Theme", "System default"),
            Row("Appearance", "Show logos and contact photos", "Logos and photos are shown"),
            Row("OTP", "OTP display size", "Option 2"),
            Row("OTP", "Auto delete OTP", "Never"),
            Row("About", "Version", "0.1.0"),
        )

    private fun filter(query: String) = filterSettingsRows(rows, query, { it.title }, { it.summary })

    @Test
    fun `matches by title case-insensitively`() {
        assertThat(filter("theme")).containsExactly(rows[2])
        assertThat(filter("OTP").map { it.title })
            .containsExactly("OTP display size", "Auto delete OTP")
    }

    @Test
    fun `matches by summary keywords`() {
        assertThat(filter("export")).containsExactly(rows[0])
        assertThat(filter("photos shown")).containsExactly(rows[3])
    }

    @Test
    fun `no match returns an empty list`() {
        assertThat(filter("bluetooth")).isEmpty()
    }

    @Test
    fun `blank or whitespace query restores the full list in order`() {
        assertThat(filter("")).isEqualTo(rows)
        assertThat(filter("   ")).isEqualTo(rows)
    }

    @Test
    fun `every row is reachable by searching its own title`() {
        rows.forEach { row ->
            assertThat(filter(row.title)).contains(row)
        }
    }

    @Test
    fun `multiple keywords must all match`() {
        assertThat(filter("delete otp")).containsExactly(rows[5])
        assertThat(filter("delete theme")).isEmpty()
    }

    @Test
    fun `filtered rows keep their original order so section grouping stays stable`() {
        val visible = filter("o")
        val originalIndices = visible.map { rows.indexOf(it) }
        assertThat(originalIndices).isInStrictOrder()
    }
}
