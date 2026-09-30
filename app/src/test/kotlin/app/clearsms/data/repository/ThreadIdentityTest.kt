package app.clearsms.data.repository

import app.clearsms.data.db.ThreadAnchor
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The pure half of thread identity (issue #42): which provider threads may
 * anchor an app thread, and when an anchor is honoured. The guards here are
 * what keep the platform's group threads and its short-code matching from
 * corrupting one-to-one threads.
 */
class ThreadIdentityTest {
    @Before
    fun setUp() {
        SenderNormalizer.defaultRegion = "PL"
    }

    @After
    fun tearDown() {
        SenderNormalizer.defaultRegion = null
    }

    @Test
    fun `a one-to-one phone-number row anchors to its provider thread`() {
        assertThat(ThreadIdentity.anchorFor("+48601234567", 77L)).isEqualTo(77L)
        assertThat(ThreadIdentity.anchorFor("601 234 567", 77L, recipientCount = 1)).isEqualTo(77L)
        assertThat(ThreadIdentity.anchorFor("+48601234567", 77L, recipientCount = 0)).isEqualTo(77L)
    }

    @Test
    fun `no provider thread, or an invalid one, anchors nothing`() {
        assertThat(ThreadIdentity.anchorFor("+48601234567", null)).isNull()
        assertThat(ThreadIdentity.anchorFor("+48601234567", 0L)).isNull()
        assertThat(ThreadIdentity.anchorFor("+48601234567", -1L)).isNull()
    }

    @Test
    fun `group MMS - several recipients - never anchors, so a group thread cannot swallow a person`() {
        assertThat(ThreadIdentity.anchorFor("+48601234567", 77L, recipientCount = 2)).isNull()
        assertThat(ThreadIdentity.anchorFor("+48601234567", 77L, recipientCount = 5)).isNull()
    }

    @Test
    fun `alphanumeric ids and short codes never anchor - the app's route-variant rule wins`() {
        // The provider keeps VM-HDFCBK and AD-HDFCBK apart; the app merges
        // them. An anchor would split what the app deliberately joins.
        assertThat(ThreadIdentity.anchorFor("VM-HDFCBK-S", 77L)).isNull()
        assertThat(ThreadIdentity.anchorFor("AD-HDFCBK", 78L)).isNull()
        // And the platform's loose digit matching can file a short code with
        // a number; the short code never carries the anchor, so it cannot.
        assertThat(ThreadIdentity.anchorFor("56767", 79L)).isNull()
    }

    @Test
    fun `an anchor is honoured for variants of one number`() {
        val anchored = listOf(ThreadAnchor(threadId = 5L, normalizedSender = "601234567"))
        // Same key.
        assertThat(ThreadIdentity.threadFromAnchor("601234567", anchored)).isEqualTo(5L)
        // A variant the normalizer could not fold (unknown-region trunk form).
        assertThat(ThreadIdentity.threadFromAnchor("0601234567", anchored)).isEqualTo(5L)
        assertThat(ThreadIdentity.threadFromAnchor("48601234567", anchored)).isEqualTo(5L)
    }

    @Test
    fun `an anchor holding a DIFFERENT person is treated as a group thread and ignored`() {
        // An OEM provider filed two people's SMS under one (group) thread id.
        val anchored = listOf(ThreadAnchor(threadId = 5L, normalizedSender = "601234567"))
        assertThat(ThreadIdentity.threadFromAnchor("509876543", anchored)).isNull()
        // Even when the thread already holds two people, a third only joins
        // the one it is a variant of.
        val group =
            listOf(
                ThreadAnchor(threadId = 5L, normalizedSender = "601234567"),
                ThreadAnchor(threadId = 9L, normalizedSender = "509876543"),
            )
        assertThat(ThreadIdentity.threadFromAnchor("48509876543", group)).isEqualTo(9L)
        assertThat(ThreadIdentity.threadFromAnchor("700000000", group)).isNull()
    }

    @Test
    fun `an anchor with nothing behind it yields nothing - the sender key decides`() {
        assertThat(ThreadIdentity.threadFromAnchor("601234567", emptyList())).isNull()
    }
}
