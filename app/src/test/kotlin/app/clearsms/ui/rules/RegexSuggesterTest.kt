package app.clearsms.ui.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RegexSuggesterTest {
    @Test
    fun `digit runs become d plus`() {
        val pattern = RegexSuggester.suggestBodyPattern("OTP is 482910 for login")
        assertThat(pattern).isEqualTo("OTP\\s+is\\s+\\d+\\s+for\\s+login")
    }

    @Test
    fun `suggested pattern matches sibling messages with different digits`() {
        val original = "Rs.1,250.00 debited from a/c XX3456 on 12-07-26"
        val pattern = Regex(RegexSuggester.suggestBodyPattern(original))
        assertThat(pattern.containsMatchIn(original)).isTrue()
        assertThat(pattern.containsMatchIn("Rs.99,999.50 debited from a/c XX9999 on 01-01-27")).isTrue()
    }

    @Test
    fun `regex specials are escaped`() {
        val pattern = RegexSuggester.suggestBodyPattern("Save 10% (limited offer)! visit a.b")
        // The literal parentheses and dot must be escaped so compiling succeeds and matches literally.
        val regex = Regex(pattern)
        assertThat(regex.containsMatchIn("Save 20% (limited offer)! visit a.b")).isTrue()
        assertThat(regex.containsMatchIn("Save 20% limited offer visit aXb")).isFalse()
    }

    @Test
    fun `whitespace runs are generalized`() {
        val pattern = RegexSuggester.suggestBodyPattern("Hello   world")
        assertThat(pattern).isEqualTo("Hello\\s+world")
        assertThat(Regex(pattern).containsMatchIn("Hello world")).isTrue()
    }

    @Test
    fun `sender pattern strips route prefix and suffix and is case-insensitive`() {
        val pattern = RegexSuggester.suggestSenderPattern("VM-HDFCBK-S")
        val regex = Regex(pattern)
        assertThat(regex.containsMatchIn("AD-HDFCBK")).isTrue()
        assertThat(regex.containsMatchIn("hdfcbk")).isTrue()
        assertThat(regex.containsMatchIn("ICICIB")).isFalse()
    }
}
