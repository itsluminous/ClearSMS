package app.clearsms.ui.finance

import app.clearsms.domain.model.AccountType
import app.clearsms.domain.model.ReminderType
import app.clearsms.ui.components.AvatarStyle
import app.clearsms.ui.components.BrandGlyph
import app.clearsms.ui.components.avatarStyleFor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FinanceAvatarsTest {
    @Test
    fun `transaction avatar prefers the merchant and falls back to the bank`() {
        assertThat(financeTransactionAvatarName("Swiggy", "HDFC Bank")).isEqualTo("Swiggy")
        assertThat(financeTransactionAvatarName(null, "HDFC Bank")).isEqualTo("HDFC Bank")
        assertThat(financeTransactionAvatarName("  ", "HDFC Bank")).isEqualTo("HDFC Bank")
        assertThat(financeTransactionAvatarName(null, "")).isEqualTo("?")
    }

    @Test
    fun `account glyph follows the account type`() {
        assertThat(accountGlyph(AccountType.SAVINGS)).isEqualTo(BrandGlyph.BANK)
        assertThat(accountGlyph(AccountType.CREDIT_CARD)).isEqualTo(BrandGlyph.CARD)
        assertThat(accountGlyph(AccountType.WALLET)).isEqualTo(BrandGlyph.WALLET)
    }

    @Test
    fun `reminder glyph covers every reminder type`() {
        assertThat(reminderGlyph(ReminderType.CREDIT_CARD)).isEqualTo(BrandGlyph.CARD)
        assertThat(reminderGlyph(ReminderType.EMI)).isEqualTo(BrandGlyph.BANK)
        assertThat(reminderGlyph(ReminderType.DEPOSIT)).isEqualTo(BrandGlyph.BANK)
        assertThat(reminderGlyph(ReminderType.INSURANCE)).isEqualTo(BrandGlyph.HEALTH)
        assertThat(reminderGlyph(ReminderType.DELIVERY)).isEqualTo(BrandGlyph.DELIVERY)
        assertThat(reminderGlyph(ReminderType.SUBSCRIPTION)).isEqualTo(BrandGlyph.NONE)
        assertThat(reminderGlyph(ReminderType.OTHER)).isEqualTo(BrandGlyph.NONE)
    }

    @Test
    fun `finance avatars honour the showRichAvatars setting through the shared chain`() {
        // Rich avatars off: even a known bank with a bundled logo stays plain.
        assertThat(
            avatarStyleFor(
                richAvatars = false,
                photoUri = null,
                isKnownSender = true,
                hasBundledLogo = true,
                hasBrand = true,
            ),
        ).isEqualTo(AvatarStyle.PLAIN)
        // Rich avatars on: the same shared chain the inbox uses.
        assertThat(
            avatarStyleFor(
                richAvatars = true,
                photoUri = null,
                isKnownSender = true,
                hasBundledLogo = true,
                hasBrand = true,
            ),
        ).isEqualTo(AvatarStyle.BUNDLED)
        assertThat(
            avatarStyleFor(
                richAvatars = true,
                photoUri = null,
                isKnownSender = true,
                hasBundledLogo = false,
                hasBrand = false,
            ),
        ).isEqualTo(AvatarStyle.BRAND_MARK)
    }
}
