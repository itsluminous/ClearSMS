package app.clearsms.receiver

import app.clearsms.receiver.SmsReceiver.Part
import com.google.common.truth.Truth.assertThat
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
}
