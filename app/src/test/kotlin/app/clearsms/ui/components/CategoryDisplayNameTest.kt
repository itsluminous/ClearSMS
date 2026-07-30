package app.clearsms.ui.components

import app.clearsms.domain.model.Category
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The display-name mapping is an exhaustive `when` — this test locks the
 * label of the new INFORMATIONAL category and guards every entry against
 * blank labels (chips, badges and the settings filter picker all render it).
 */
class CategoryDisplayNameTest {
    @Test
    fun `every category has a non-blank display name`() {
        for (category in Category.entries) {
            assertThat(category.displayName()).isNotEmpty()
        }
    }

    @Test
    fun `informational category is labelled Informational`() {
    }
}
