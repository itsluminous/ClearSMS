package app.clearsms.mms

import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.db.MmsStatus
import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Snippet substitution for MMS rows (inbox + notification share this). */
class MmsSnippetTest {
    private fun message(
        body: String = "",
        mmsStatus: MmsStatus? = null,
        attachmentKinds: String? = null,
    ) = MessageEntity(
        id = 1L,
        threadId = 1L,
        sender = "+15551234567",
        normalizedSender = "+15551234567",
        body = body,
        timestamp = 1_000L,
        category = Category.PERSONAL,
        mmsStatus = mmsStatus,
        attachmentKinds = attachmentKinds,
    )

    @Test
    fun `plain sms uses its body`() {
        assertThat(MmsSnippet.overrideRes(message(body = "hello"))).isNull()
    }

    @Test
    fun `mms with a text part uses its body`() {
        assertThat(
            MmsSnippet.overrideRes(message(body = "caption", mmsStatus = MmsStatus.DOWNLOADED, attachmentKinds = "IMAGE")),
        ).isNull()
    }

    @Test
    fun `image-only mms reads Photo`() {
        assertThat(
            MmsSnippet.overrideRes(message(mmsStatus = MmsStatus.DOWNLOADED, attachmentKinds = "IMAGE")),
        ).isEqualTo(R.string.mms_snippet_photo)
    }

    @Test
    fun `non-image attachment reads Attachment`() {
        assertThat(
            MmsSnippet.overrideRes(message(mmsStatus = MmsStatus.DOWNLOADED, attachmentKinds = "FILE")),
        ).isEqualTo(R.string.mms_snippet_attachment)
    }

    @Test
    fun `pending and failed states override even a body`() {
        assertThat(MmsSnippet.overrideRes(message(mmsStatus = MmsStatus.PENDING))).isEqualTo(R.string.mms_downloading)
        assertThat(MmsSnippet.overrideRes(message(mmsStatus = MmsStatus.FAILED))).isEqualTo(R.string.mms_download_failed)
    }

    @Test
    fun `downloaded mms with no attachments and no text falls back to the body`() {
        assertThat(MmsSnippet.overrideRes(message(mmsStatus = MmsStatus.DOWNLOADED))).isNull()
    }
}
