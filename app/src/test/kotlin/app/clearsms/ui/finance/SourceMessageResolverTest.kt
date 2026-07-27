package app.clearsms.ui.finance

import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceMessageResolverTest {
    @Test
    fun `resolves a message id to its thread for conversation navigation`() {
        val message =
            MessageEntity(
                id = 42,
                threadId = 7,
                sender = "VM-IDFCFB",
                normalizedSender = "IDFCFB",
                body = "Your statement is ready",
                timestamp = 1_000L,
                category = Category.IMPORTANT,
            )

        val ref = SourceMessageResolver.resolve(message)

        assertThat(ref).isEqualTo(MessageRef(threadId = 7, messageId = 42))
    }

    @Test
    fun `deleted source message resolves to null instead of crashing`() {
        assertThat(SourceMessageResolver.resolve(null)).isNull()
    }
}
