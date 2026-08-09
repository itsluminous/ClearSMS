package app.clearsms.ui.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rule-editor routes: edit carries the tapped rule's id (save updates
 * in place), duplicate flags the copy mode, and the plain create route
 * stays id-free.
 */
@RunWith(RobolectricTestRunner::class)
class RuleWizardRoutesTest {
    @Test
    fun `edit route carries the rule id without the duplicate flag`() {
        assertThat(Routes.ruleWizardEdit("user_abc123"))
            .isEqualTo("ruleWizard?sender=&body=&ruleId=user_abc123&duplicate=false")
    }

    @Test
    fun `duplicate route carries the rule id with the duplicate flag`() {
        assertThat(Routes.ruleWizardDuplicate("hdfc-debit"))
            .isEqualTo("ruleWizard?sender=&body=&ruleId=hdfc-debit&duplicate=true")
    }

    @Test
    fun `create route has no rule id`() {
        assertThat(Routes.ruleWizard()).contains("ruleId=&duplicate=false")
        assertThat(Routes.ruleWizard("HDFCBK", "hello")).contains("sender=HDFCBK")
    }

    @Test
    fun `rule ids are uri encoded`() {
        assertThat(Routes.ruleWizardEdit("user:my rule")).contains("ruleId=user%3Amy%20rule")
    }
}
