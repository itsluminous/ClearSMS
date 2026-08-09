package app.clearsms.ui.inbox

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Held/dismissed → visible mapping of the default-SMS-role banner: it must
 * show whenever the role is missing, hide live when the role is (re)gained,
 * and a dismissal must only last for the session.
 */
class DefaultSmsBannerStateTest {
    @Test
    fun `role not held shows banner`() {
        val state = DefaultSmsBannerState()
        state.onRoleChecked(held = false)
        assertThat(state.visible).isTrue()
    }

    @Test
    fun `role held hides banner`() {
        val state = DefaultSmsBannerState()
        state.onRoleChecked(held = true)
        assertThat(state.visible).isFalse()
    }

    @Test
    fun `banner hidden before any check completes`() {
        // Assume the role is held until proven otherwise: no error flash at launch.
        assertThat(DefaultSmsBannerState().visible).isFalse()
    }

    @Test
    fun `resume re-check after gaining the role hides banner live`() {
        val state = DefaultSmsBannerState()
        state.onRoleChecked(held = false)
        assertThat(state.visible).isTrue()
        // User set the app as default in the role dialog; ON_RESUME re-checks.
        state.onRoleChecked(held = true)
        assertThat(state.visible).isFalse()
    }

    @Test
    fun `dismiss hides banner for the session`() {
        val state = DefaultSmsBannerState()
        state.onRoleChecked(held = false)
        state.dismiss()
        assertThat(state.visible).isFalse()
        // Further checks while the role is still missing keep it dismissed.
        state.onRoleChecked(held = false)
        assertThat(state.visible).isFalse()
    }

    @Test
    fun `new session shows banner again while role is still missing`() {
        // Session scope: a fresh instance (new composition/launch) forgets dismissal.
        val previous = DefaultSmsBannerState()
        previous.onRoleChecked(held = false)
        previous.dismiss()

        val next = DefaultSmsBannerState()
        next.onRoleChecked(held = false)
        assertThat(next.visible).isTrue()
    }

    @Test
    fun `regaining then losing the role shows banner despite earlier dismissal`() {
        val state = DefaultSmsBannerState()
        state.onRoleChecked(held = false)
        state.dismiss()
        state.onRoleChecked(held = true)
        state.onRoleChecked(held = false)
        assertThat(state.visible).isTrue()
    }

    @Test
    fun `absent to held transition reports a regain - the catch-up import trigger`() {
        val state = DefaultSmsBannerState()
        assertThat(state.onRoleChecked(held = false)).isFalse()
        // The banner-flow grant (or an external switch back) is observed.
        assertThat(state.onRoleChecked(held = true)).isTrue()
    }

    @Test
    fun `first check of a session never reports a regain`() {
        // Cold start while the role is held: no observed transition - the
        // provider gap probe covers messages missed while the app was dead.
        assertThat(DefaultSmsBannerState().onRoleChecked(held = true)).isFalse()
    }

    @Test
    fun `steady held checks report no regain`() {
        val state = DefaultSmsBannerState()
        state.onRoleChecked(held = true)
        assertThat(state.onRoleChecked(held = true)).isFalse()
    }

    @Test
    fun `losing the role reports no regain`() {
        val state = DefaultSmsBannerState()
        state.onRoleChecked(held = true)
        assertThat(state.onRoleChecked(held = false)).isFalse()
    }

    @Test
    fun `each absent to held transition reports its own regain`() {
        val state = DefaultSmsBannerState()
        state.onRoleChecked(held = false)
        assertThat(state.onRoleChecked(held = true)).isTrue()
        state.onRoleChecked(held = false)
        assertThat(state.onRoleChecked(held = true)).isTrue()
    }
}
