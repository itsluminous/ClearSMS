package app.clearsms.data.prefs

import app.clearsms.data.prefs.BlockedKeywords.ValidationError
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockedKeywordsTest {
    @Test
    fun `matching is a case-insensitive substring test`() {
        val keywords = setOf("LOAN OFFER")
        assertThat(BlockedKeywords.matches("Pre-approved loan offer inside!", keywords)).isTrue()
        assertThat(BlockedKeywords.matches("LOAN OFFER just for you", keywords)).isTrue()
        assertThat(BlockedKeywords.matches("Loan Offer", keywords)).isTrue()
        assertThat(BlockedKeywords.matches("Your parcel is out for delivery", keywords)).isFalse()
    }

    @Test
    fun `no keywords means no match, ever`() {
        assertThat(BlockedKeywords.matches("anything at all", emptySet())).isFalse()
    }

    @Test
    fun `blank and one-character keywords are refused`() {
        assertThat(BlockedKeywords.validate("", emptySet())).isEqualTo(ValidationError.TOO_SHORT)
        assertThat(BlockedKeywords.validate("   ", emptySet())).isEqualTo(ValidationError.TOO_SHORT)
        assertThat(BlockedKeywords.validate("a", emptySet())).isEqualTo(ValidationError.TOO_SHORT)
        assertThat(BlockedKeywords.validate(" x ", emptySet())).isEqualTo(ValidationError.TOO_SHORT)
        assertThat(BlockedKeywords.validate("ok", emptySet())).isNull()
    }

    @Test
    fun `duplicates are refused case-insensitively`() {
        assertThat(BlockedKeywords.validate("Loan Offer", setOf("loan offer")))
            .isEqualTo(ValidationError.DUPLICATE)
    }

    @Test
    fun `the list is capped at 100 keywords`() {
        val full = (1..BlockedKeywords.MAX_COUNT).map { "keyword-$it" }.toSet()
        assertThat(BlockedKeywords.validate("one more", full)).isEqualTo(ValidationError.LIMIT_REACHED)
        assertThat(BlockedKeywords.validate("fits", full.drop(1).toSet())).isNull()
    }
}
