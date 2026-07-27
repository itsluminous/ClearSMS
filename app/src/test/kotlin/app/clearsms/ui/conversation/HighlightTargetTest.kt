package app.clearsms.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HighlightTargetTest {
    private val ids = listOf(11L, 22L, 33L, 44L)

    @Test
    fun `target present in the thread resolves to its index`() {
        assertThat(highlightIndexFor(ids, 33L)).isEqualTo(2)
        assertThat(highlightIndexFor(ids, 11L)).isEqualTo(0)
    }

    @Test
    fun `missing target resolves to nothing`() {
        assertThat(highlightIndexFor(ids, 99L)).isNull()
    }

    @Test
    fun `absent nav argument resolves to nothing`() {
        assertThat(highlightIndexFor(ids, null)).isNull()
        assertThat(highlightIndexFor(ids, -1L)).isNull()
    }

    @Test
    fun `empty thread resolves to nothing`() {
        assertThat(highlightIndexFor(emptyList(), 11L)).isNull()
    }
}
