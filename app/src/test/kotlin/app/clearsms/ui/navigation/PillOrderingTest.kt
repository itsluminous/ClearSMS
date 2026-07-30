package app.clearsms.ui.navigation

import app.clearsms.domain.model.Category
import app.clearsms.ui.settings.move
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The pill order the user configures is advisory: it decides SEQUENCE, never
 * membership. A stored order can be stale (a pill removed in a later version)
 * or incomplete (a pill added later), and neither may crash or hide a pill.
 */
class PillOrderingTest {
    private val all = Category.entries.toList()

    @Test
    fun `an empty configured order keeps the declaration order`() {
        assertThat(orderedPills(emptyList(), all)).isEqualTo(all)
    }

    @Test
    fun `pills render in the configured order`() {
        val configured = listOf(Category.OTP, Category.PERSONAL)
        val result = orderedPills(configured, all)
        assertThat(result.take(2)).containsExactly(Category.OTP, Category.PERSONAL).inOrder()
    }

    @Test
    fun `pills missing from the configured order are appended, never hidden`() {
        val configured = listOf(Category.OTP)
        val result = orderedPills(configured, all)
        assertThat(result).containsExactlyElementsIn(all)
        assertThat(result.first()).isEqualTo(Category.OTP)
    }

    @Test
    fun `duplicates in the configured order collapse to the first occurrence`() {
        val configured = listOf(Category.OTP, Category.OTP, Category.PERSONAL)
        val result = orderedPills(configured, all)
        assertThat(result).containsExactlyElementsIn(all)
        assertThat(result.count { it == Category.OTP }).isEqualTo(1)
    }

    @Test
    fun `entries absent from the pill set are dropped`() {
        // PROMOTIONAL is dropped from `all` here, standing in for a pill removed
        // in a later version while still present in a stored order.
        val reduced = all - Category.PROMOTIONAL
        val result = orderedPills(listOf(Category.PROMOTIONAL, Category.OTP), reduced)
        assertThat(result).containsExactlyElementsIn(reduced)
        assertThat(result).doesNotContain(Category.PROMOTIONAL)
    }

    @Test
    fun `move reorders within bounds and ignores invalid targets`() {
        val list = mutableListOf("a", "b", "c")
        list.move(2, 0)
        assertThat(list).containsExactly("c", "a", "b").inOrder()
        list.move(0, -1)
        list.move(0, 9)
        assertThat(list).containsExactly("c", "a", "b").inOrder()
    }
}
