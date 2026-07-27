package app.clearsms.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchQueryFormatTest {
    @Test
    fun `tokens split on any non-alphanumeric`() {
        assertThat(SearchQueryFormat.tokens("VM-HDFCBK, salary!")).containsExactly("VM", "HDFCBK", "salary").inOrder()
    }

    @Test
    fun `match expression appends prefix star per token`() {
        assertThat(SearchQueryFormat.toFtsMatch("salary credited")).isEqualTo("salary* credited*")
    }

    @Test
    fun `fts operators and quotes cannot survive sanitization`() {
        assertThat(SearchQueryFormat.toFtsMatch("\"a\" OR b* NEAR/2 -c sender:x")).isEqualTo("a* OR* b* NEAR* 2* c* sender* x*")
        assertThat(SearchQueryFormat.toFtsMatch("\"*\"()-")).isNull()
    }

    @Test
    fun `punctuation-only input yields no match`() {
        assertThat(SearchQueryFormat.toFtsMatch("  --- !!! ")).isNull()
        assertThat(SearchQueryFormat.isSearchable("---")).isFalse()
    }

    @Test
    fun `queries below the minimum length are not searchable`() {
        assertThat(SearchQueryFormat.isSearchable("")).isFalse()
        assertThat(SearchQueryFormat.isSearchable("s")).isFalse()
        assertThat(SearchQueryFormat.isSearchable(" s ")).isFalse()
        assertThat(SearchQueryFormat.isSearchable("sa")).isTrue()
        assertThat(SearchQueryFormat.isSearchable("salary")).isTrue()
    }
}
