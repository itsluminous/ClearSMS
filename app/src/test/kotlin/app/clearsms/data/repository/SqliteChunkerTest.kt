package app.clearsms.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SqliteChunkerTest {
    @Test
    fun `empty input yields no chunks`() {
        assertThat(SqliteChunker.chunk(emptyList<Long>())).isEmpty()
    }

    @Test
    fun `small lists stay in one chunk`() {
        val chunks = SqliteChunker.chunk((1L..10L).toList())
        assertThat(chunks).hasSize(1)
        assertThat(chunks.single()).hasSize(10)
    }

    @Test
    fun `lists beyond the sqlite variable limit are split`() {
        val ids = (1L..2500L).toList()
        val chunks = SqliteChunker.chunk(ids)
        assertThat(chunks.size).isEqualTo(3)
        chunks.forEach { assertThat(it.size).isAtMost(SqliteChunker.MAX_VARIABLES) }
        // The limit itself stays under SQLite's 999-variable default.
        assertThat(SqliteChunker.MAX_VARIABLES).isLessThan(999)
    }

    @Test
    fun `chunking preserves order and loses nothing`() {
        val ids = (1L..1234L).toList()
        assertThat(SqliteChunker.chunk(ids).flatten()).isEqualTo(ids)
    }
}
