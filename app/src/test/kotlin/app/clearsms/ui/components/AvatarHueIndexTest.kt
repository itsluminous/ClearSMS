package app.clearsms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The letter avatar picks its hue by hashing the name. `abs(hash) % n` is
 * wrong for exactly one hash value - Int.MIN_VALUE has no positive
 * counterpart, so `abs` returns it unchanged and the index goes negative.
 * Hardening for #48 (a per-contact crash in a picker that draws letter
 * avatars); the fixture string hashes to Int.MIN_VALUE.
 */
class AvatarHueIndexTest {
    @Test
    fun `a name hashing to Int MIN_VALUE still lands inside the hue table`() {
        val name = "aAgaAXq"
        assertThat(name.hashCode()).isEqualTo(Int.MIN_VALUE)
        assertThat(avatarHueIndex(name)).isAtLeast(0)
    }

    @Test
    fun `every index is within bounds for names with negative hashes`() {
        listOf("Ernő", "Pernilla", "érny", "", "#", "P".repeat(300)).forEach { name ->
            val index = avatarHueIndex(name)
            assertThat(index).isAtLeast(0)
            assertThat(index).isLessThan(9)
        }
    }

    @Test
    fun `initials of an accented name come from the original letters`() {
        assertThat(initialsOf("Ernő Példa")).isEqualTo("EP")
        assertThat(initialsOf("  ")).isEqualTo("#")
        assertThat(initialsOf("ágnes-pernille")).isEqualTo("ÁP")
    }
}
