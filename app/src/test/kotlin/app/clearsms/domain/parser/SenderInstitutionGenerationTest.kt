package app.clearsms.domain.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * `SenderNameResolver`'s institution table is GENERATED from
 * `rules/brands/brands.json` - the single source of truth - instead of being
 * a hand-maintained Kotlin duplicate kept in sync by a test. These tests lock
 * the generation: the bundled classpath copy is the master file, the
 * generated table carries the same names / sender keys / aliases / issuer
 * flags the old constants did, and a malformed table degrades to empty
 * without crashing.
 */
class SenderInstitutionGenerationTest {
    @Test
    fun `classpath brands resource is the community master file`() {
        val master = repoFile("rules/brands/brands.json").readText()
        assertThat(SenderNameResolver.readBrandsResource()).isEqualTo(master)
    }

    @Test
    fun `generation yields the same institutions the deleted constant table had`() {
        val institutions = SenderNameResolver.parseInstitutions(repoFile("rules/brands/brands.json").readText())
        assertThat(institutions).hasSize(30)

        val byName = institutions.associateBy { it.name }
        // Spot-check the entries the old constants pinned, including every
        // field kind: plain, sender-key override, alias override, name
        // override, and non-issuer flags.
        assertThat(byName.getValue("HDFC Bank").senderKeys).containsExactly("HDFCBK", "HDFCB")
        assertThat(byName.getValue("State Bank of India").senderKeys)
            .containsExactly(
                "SBIINB",
                "SBIUPI",
                "SBIPSG",
                "SBIOTP",
                "CBSSBI",
                "ATMSBI",
                "SBICRD",
                "SBIBNK",
                "SBICAR",
                "SBIYON",
            )
        assertThat(byName.getValue("State Bank of India").aliases)
            .containsExactly("STATE BANK OF INDIA", "STATE BANK", "SBI CARD", "SBI")
        assertThat(byName.getValue("Paytm Payments Bank").senderKeys)
            .containsExactly("PYTMPB", "PAYTMB", "IPAYTM", "PAYTM")
        assertThat(byName.getValue("Citi").senderKeys).containsExactly("CITIBK", "CITIBA", "CITI")
        assertThat(byName.getValue("Pluxee").aliases).containsExactly("PLUXEE", "SODEXO")
        // The two NPS CRAs (Protean, KFintech) generate under the ONE unified
        // "NPS" issuer name - a subscriber has ONE PRAN, whichever CRA
        // reports it - while keeping their own avatar brands.
        val npsInstitutions = institutions.filter { it.name == "NPS" }
        assertThat(npsInstitutions).hasSize(2)
        assertThat(npsInstitutions.flatMap { it.senderKeys })
            .containsAtLeast("PTNNPS", "KFNCRA")
        assertThat(npsInstitutions.all { it.isRetirementProduct }).isTrue()

        // Issuer-ness must survive generation exactly.
        val issuers = institutions.filter { it.isIssuer }.map { it.name }
        val nonIssuers = institutions.filterNot { it.isIssuer }.map { it.name }
        assertThat(nonIssuers).containsExactly("CRED", "Flipkart", "Airtel", "Jio", "Vi", "BSNL", "Sony LIV")
        assertThat(issuers).hasSize(23)
    }

    @Test
    fun `resolver behaves identically to the deleted constant table`() {
        // Name override: "Paytm" canonicalizes to the payments bank.
        assertThat(SenderNameResolver.canonicalize("Paytm")).isEqualTo("Paytm Payments Bank")
        // Sender-key override: SBI Card ids stay attributed to the bank.
        assertThat(SenderNameResolver.bankNameFor("VK-SBICRD", "")).isEqualTo("State Bank of India")
        // Issuer flags: CRED is a channel, EPFO owns provident-fund accounts.
        assertThat(SenderNameResolver.isPlausibleIssuer("CRED")).isFalse()
        assertThat(SenderNameResolver.isPlausibleIssuer("EPFO")).isTrue()
        assertThat(SenderNameResolver.isPlausibleIssuer("Sony LIV")).isFalse()
    }

    @Test
    fun `intent of the deleted sync test - every institution sender resolves to a name`() {
        // Ported from the deleted brands-sync test: every sender key of every
        // generated institution must resolve to SOME display name.
        val brandsJson = repoFile("rules/brands/brands.json").readText()
        val institutions = SenderNameResolver.parseInstitutions(brandsJson)
        assertThat(institutions).isNotEmpty()
        for (institution in institutions) {
            for (sender in institution.senderKeys) {
                val resolved = SenderNameResolver.bankNameFor(sender)
                assertThat(resolved).isNotNull()
                assertThat(resolved).isNotEmpty()
            }
        }
    }

    @Test
    fun `malformed brands json degrades to an empty table without crashing`() {
        assertThat(SenderNameResolver.parseInstitutions("{ definitely not json")).isEmpty()
        assertThat(SenderNameResolver.parseInstitutions("""{"brands": 42}""")).isEmpty()
        assertThat(SenderNameResolver.parseInstitutions(null)).isEmpty()
    }

    @Test
    fun `entries without an issuer field are not institutions`() {
        val json =
            """{"version":"1.1","brands":[
              {"key":"a","name":"Some Brand","color":"#000000","monogram":"S","senders":["SOMEBR"]},
              {"key":"b","name":"Some Bank","color":"#000000","monogram":"B","senders":["SOMEBK"],"is_issuer":true}
            ]}"""
        val institutions = SenderNameResolver.parseInstitutions(json)
        assertThat(institutions.map { it.name }).containsExactly("Some Bank")
        assertThat(institutions.single().isIssuer).isTrue()
    }

    private fun repoFile(repoRelativePath: String): File =
        sequenceOf(File(repoRelativePath), File("..", repoRelativePath))
            .first(File::exists)
}
