package app.clearsms.receiver

import app.clearsms.receiver.SmsReceiver.Part
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SmsReceiverPartsTest {
    @Test
    fun `single part passes through unchanged`() {
        val merged = SmsReceiver.mergeParts(listOf(Part("VM-HDFCBK", "Hello", 100L)))
        assertThat(merged).containsExactly(Part("VM-HDFCBK", "Hello", 100L))
    }

    @Test
    fun `multipart segments from one sender are concatenated in order`() {
        val merged =
            SmsReceiver.mergeParts(
                listOf(
                    Part("VM-HDFCBK", "Your a/c XX1234 was debited by Rs.500 ", 100L),
                    Part("VM-HDFCBK", "on 26-07-26. Avl Bal Rs.10,000.", 105L),
                ),
            )
        assertThat(merged).hasSize(1)
        assertThat(merged[0].body)
            .isEqualTo("Your a/c XX1234 was debited by Rs.500 on 26-07-26. Avl Bal Rs.10,000.")
    }

    @Test
    fun `merged message keeps the earliest timestamp`() {
        val merged =
            SmsReceiver.mergeParts(
                listOf(
                    Part("SENDER", "part2", 200L),
                    Part("SENDER", "part1", 100L),
                ),
            )
        assertThat(merged[0].timestampMs).isEqualTo(100L)
    }

    @Test
    fun `parts from different senders stay separate messages`() {
        val merged =
            SmsReceiver.mergeParts(
                listOf(
                    Part("VM-HDFCBK", "bank alert", 100L),
                    Part("9876543210", "hi there", 110L),
                ),
            )
        assertThat(merged).hasSize(2)
        assertThat(merged.map { it.sender }).containsExactly("VM-HDFCBK", "9876543210")
    }

    @Test
    fun `only contiguous runs from the same sender are merged`() {
        // A, B, A: the two A parts are distinct messages (another sender's
        // part arrived between them), so they must not be glued together.
        val merged =
            SmsReceiver.mergeParts(
                listOf(
                    Part("VM-HDFCBK", "first message", 100L),
                    Part("9876543210", "hello", 105L),
                    Part("VM-HDFCBK", "second message", 110L),
                ),
            )
        assertThat(merged).hasSize(3)
        assertThat(merged.map { it.body })
            .containsExactly("first message", "hello", "second message")
            .inOrder()
    }

    @Test
    fun `empty bodies merge without error`() {
        val merged =
            SmsReceiver.mergeParts(
                listOf(
                    Part("SENDER", "", 100L),
                    Part("SENDER", "tail", 105L),
                ),
            )
        assertThat(merged).hasSize(1)
        assertThat(merged[0].body).isEqualTo("tail")
    }

    @Test
    fun `empty part list yields no messages`() {
        assertThat(SmsReceiver.mergeParts(emptyList())).isEmpty()
    }

    @Test
    fun `a message that throws does not abort the rest of the batch`() =
        runTest {
            val parts =
                listOf(
                    Part("A", "ok-1", 1L),
                    Part("B", "boom", 2L),
                    Part("C", "ok-2", 3L),
                )
            val processed = mutableListOf<String>()
            val failed = mutableListOf<String>()

            SmsReceiver.processIsolating(
                parts,
                onError = { part, e ->
                    failed += part.sender
                    assertThat(e).hasMessageThat().contains("db write failed")
                },
            ) { part ->
                if (part.body == "boom") throw IllegalStateException("db write failed")
                processed += part.sender
            }

            assertThat(processed).containsExactly("A", "C").inOrder()
            assertThat(failed).containsExactly("B")
        }
}
