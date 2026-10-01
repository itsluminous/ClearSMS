package app.clearsms.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Source contract for the per-thread representative row: every per-thread
 * list in `MessageDao` must join through [LatestPerThreadSql], and no
 * `MAX(id)`-style "newest row" selection may reappear. That selection was
 * the inbox-preview bug - the newest ROW is the newest MESSAGE only while
 * insert order happens to match message order, which old threads break -
 * and it had been copy-pasted into seven queries that could drift apart.
 */
class LatestPerThreadContractTest {
    private val daoSource = File("src/main/kotlin/app/clearsms/data/db/MessageDao.kt").readText()

    /** Per-thread "latest" joins that pick the highest row id instead of the newest message. */
    private val maxIdJoin = Regex("""MAX\(\s*(?:\w+\.)?id\s*\)""", RegexOption.IGNORE_CASE)

    @Test
    fun `MessageDao never selects a thread's representative by MAX(id)`() {
        assertThat(maxIdJoin.containsMatchIn(daoSource)).isFalse()
        assertThat(daoSource).doesNotContain("maxId")
    }

    @Test
    fun `MessageDao has no hand-rolled per-thread grouping - all of it lives in LatestPerThreadSql`() {
        assertThat(daoSource).doesNotContain("GROUP BY threadId")
        assertThat(daoSource).doesNotContain("GROUP BY m.threadId")
    }

    @Test
    fun `the seven per-thread lists all join through the shared fragment`() {
        val uses = Regex("""\$\{LatestPerThreadSql\.JOIN_BY_(RECEIVED|SENT)}""").findAll(daoSource).toList()
        assertThat(uses).hasSize(7)
        // Six canonical (received-key) joins; exactly one sent-key join, the sent-ordered inbox pager.
        assertThat(uses.count { it.groupValues[1] == "SENT" }).isEqualTo(1)
        val sentPager = daoSource.substringAfter("JOIN_BY_SENT}").substringBefore("fun ")
        assertThat(sentPager).contains("COALESCE(m.dateSent, m.timestamp) DESC, m.id DESC")
    }

    @Test
    fun `both constants are the one template instantiated for their key`() {
        assertThat(LatestPerThreadSql.JOIN_BY_RECEIVED).isEqualTo(LatestPerThreadSql.joinFor(LatestPerThreadSql.RECEIVED_KEY))
        assertThat(LatestPerThreadSql.JOIN_BY_SENT).isEqualTo(LatestPerThreadSql.joinFor(LatestPerThreadSql.SENT_KEY))
        assertThat(LatestPerThreadSql.JOIN_BY_RECEIVED).isNotEqualTo(LatestPerThreadSql.JOIN_BY_SENT)
    }

    @Test
    fun `the fragment picks the newest message by key, id only as tie-break, among live rows only`() {
        val sql = LatestPerThreadSql.JOIN_BY_RECEIVED
        // Pass 1: the maximum of the KEY per thread, never of the id.
        assertThat(sql).contains("MAX(timestamp) AS maxKey")
        // Pass 2: id decides only among rows tied at that maximum.
        assertThat(sql).contains("timestamp = newest.maxKey")
        assertThat(sql).contains("MAX(messages.id) AS maxId")
        // Binned rows can represent nothing - both passes exclude them.
        assertThat(Regex("""deletedAt IS NULL""").findAll(sql).count()).isEqualTo(2)
        // Planner hint: `newest` (one row per thread) is the outer loop of pass 2.
        assertThat(sql).contains("CROSS JOIN messages ON messages.threadId = newest.threadId")
        // The caller's alias is `m`, and the join lands on its primary key.
        assertThat(sql).endsWith("latest ON latest.threadId = m.threadId AND latest.maxId = m.id")
    }

    @Test
    fun `the sent-key fragment mirrors the sent-ordered conversation pager's key`() {
        val sql = LatestPerThreadSql.JOIN_BY_SENT
        assertThat(sql).contains("MAX(COALESCE(dateSent, timestamp)) AS maxKey")
        assertThat(sql).contains("COALESCE(dateSent, timestamp) = newest.maxKey")
        // pagingThreadBySent orders by exactly this key.
        assertThat(daoSource).contains("ORDER BY COALESCE(dateSent, timestamp) DESC, id DESC")
    }
}
