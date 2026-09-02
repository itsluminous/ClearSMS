package app.clearsms.domain.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * How much has to be re-sorted for a newly saved rule to take effect. Saving a
 * rule used to change nothing until the user ran the full re-sort by hand,
 * which reads as the feature being broken; re-sorting everything on every save
 * is the opposite mistake, costing minutes of phone time for one sender.
 */
class RuleApplyScopeTest {
    private fun resolve(
        senderPattern: String = "(?i)HDFCBK",
        sourceSender: String = "VM-HDFCBK",
        boundToSender: Boolean = true,
        senderPatternEdited: Boolean = false,
    ) = RuleScopeResolver.resolve(senderPattern, sourceSender, boundToSender, senderPatternEdited)

    @Test
    fun `a rule built from a message is scoped to that sender`() {
        assertThat(resolve()).isEqualTo(RuleApplyScope.Sender("HDFCBK"))
    }

    @Test
    fun `the scope carries the TRAI-stripped core, matching the rule's own pattern`() {
        // "(?i)HDFCBK" matches "VM-HDFCBK" and "AX-HDFCBK" alike, so the core is
        // what defines the affected set - not the raw sender string.
        val scope = resolve(senderPattern = "(?i)JIOPAY", sourceSender = "VM-JIOPAY")

        assertThat(scope).isEqualTo(RuleApplyScope.Sender("JIOPAY"))
    }

    @Test
    fun `a body-only rule needs the full re-sort`() {
        assertThat(resolve(boundToSender = false)).isEqualTo(RuleApplyScope.Everything)
    }

    @Test
    fun `a hand-edited sender pattern needs the full re-sort`() {
        // An edited pattern deliberately reaches beyond one sender; re-sorting
        // only the source sender would silently under-apply it.
        assertThat(resolve(senderPattern = "(?i).*BANK", senderPatternEdited = true))
            .isEqualTo(RuleApplyScope.Everything)
    }

    @Test
    fun `a regex sender pattern is not treated as a literal even if not flagged as edited`() {
        // Belt and braces: anything that is not flag-plus-literal is broad.
        for (pattern in listOf("(?i).*BANK", "(?i)HDFC|ICICI", "(?i)HDFC.*", "(?i)^HDFCBK$")) {
            assertThat(resolve(senderPattern = pattern)).isEqualTo(RuleApplyScope.Everything)
        }
    }

    @Test
    fun `editing an existing rule with no source message needs the full re-sort`() {
        assertThat(resolve(sourceSender = "")).isEqualTo(RuleApplyScope.Everything)
    }

    @Test
    fun `an empty sender pattern needs the full re-sort`() {
        assertThat(resolve(senderPattern = "")).isEqualTo(RuleApplyScope.Everything)
        assertThat(resolve(senderPattern = "(?i)")).isEqualTo(RuleApplyScope.Everything)
    }
}
