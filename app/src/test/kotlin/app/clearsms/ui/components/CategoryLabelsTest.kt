package app.clearsms.ui.components

import app.clearsms.R
import app.clearsms.domain.model.Category
import app.clearsms.domain.model.InboxPill
import app.clearsms.domain.model.SubCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The label mappings are exhaustive `when`s over string RESOURCES (the
 * compiler guards exhaustiveness; `UserVisibleLabelConventionTest` guards
 * that no branch regresses to a literal). This test locks what the compiler
 * cannot: every category and sub-category has its own resource, and a pill
 * shows exactly its category's label.
 */
class CategoryLabelsTest {
    @Test
    fun `every category has its own label resource`() {
        val ids = Category.entries.map { it.labelRes() }
        assertThat(ids).containsNoDuplicates()
        assertThat(ids).doesNotContain(0)
        assertThat(Category.OTP.labelRes()).isEqualTo(R.string.category_otp)
        assertThat(Category.SPAM.labelRes()).isEqualTo(R.string.category_spam)
    }

    @Test
    fun `every sub-category has its own label resource`() {
        val ids = SubCategory.entries.map { it.labelRes() }
        assertThat(ids).containsNoDuplicates()
        assertThat(ids).doesNotContain(0)
        assertThat(SubCategory.BANK_ALERT.labelRes()).isEqualTo(R.string.subcategory_bank_alert)
    }

    @Test
    fun `a pill is labelled by its category`() {
        for (pill in InboxPill.entries) {
            assertThat(pill.labelRes()).isEqualTo(pill.category.labelRes())
        }
    }
}
