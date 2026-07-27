package app.clearsms.ui.settings

import app.clearsms.domain.model.OtpDisplaySize
import app.clearsms.notification.OtpNotifier
import app.clearsms.ui.components.otpBannerFontSp
import app.clearsms.ui.components.otpPreviewFontSp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OtpDisplaySizeTest {
    @Test
    fun `exactly five options and no separate default entry`() {
        assertThat(OtpDisplaySize.entries.map { it.name })
            .containsExactly("OPTION_1", "OPTION_2", "OPTION_3", "OPTION_4", "OPTION_5")
            .inOrder()
    }

    @Test
    fun `option 2 is the default`() {
        assertThat(OtpDisplaySize.DEFAULT).isEqualTo(OtpDisplaySize.OPTION_2)
        assertThat(OtpDisplaySize.fromStored(null)).isEqualTo(OtpDisplaySize.OPTION_2)
    }

    @Test
    fun `legacy Default value migrates to option 2`() {
        assertThat(OtpDisplaySize.fromStored("DEFAULT")).isEqualTo(OtpDisplaySize.OPTION_2)
    }

    @Test
    fun `legacy lettered values migrate keeping their rendered size order`() {
        // Old scale by rendered size: A < Default < B < C < D.
        assertThat(OtpDisplaySize.fromStored("OPTION_A")).isEqualTo(OtpDisplaySize.OPTION_1)
        assertThat(OtpDisplaySize.fromStored("OPTION_B")).isEqualTo(OtpDisplaySize.OPTION_3)
        assertThat(OtpDisplaySize.fromStored("OPTION_C")).isEqualTo(OtpDisplaySize.OPTION_4)
        assertThat(OtpDisplaySize.fromStored("OPTION_D")).isEqualTo(OtpDisplaySize.OPTION_5)
    }

    @Test
    fun `current names round-trip and garbage falls back to the default`() {
        OtpDisplaySize.entries.forEach { size ->
            assertThat(OtpDisplaySize.fromStored(size.name)).isEqualTo(size)
        }
        assertThat(OtpDisplaySize.fromStored("")).isEqualTo(OtpDisplaySize.DEFAULT)
        assertThat(OtpDisplaySize.fromStored("HUGE")).isEqualTo(OtpDisplaySize.DEFAULT)
    }

    @Test
    fun `preview and banner font sizes increase strictly with the option`() {
        val previews = OtpDisplaySize.entries.map(::otpPreviewFontSp)
        val banners = OtpDisplaySize.entries.map(::otpBannerFontSp)
        assertThat(previews).isInStrictOrder()
        assertThat(banners).isInStrictOrder()
    }

    @Test
    fun `notification scale increases strictly and the default is native size`() {
        val scales = OtpDisplaySize.entries.map(OtpNotifier::scaleFor)
        assertThat(scales).isInStrictOrder()
        assertThat(OtpNotifier.scaleFor(OtpDisplaySize.DEFAULT)).isEqualTo(1.0f)
    }
}
