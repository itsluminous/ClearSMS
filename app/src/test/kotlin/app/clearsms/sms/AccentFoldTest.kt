package app.clearsms.sms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Faithful-only accent folding (GitHub #17), covering the reporter's
 * languages - Polish, Czech, Spanish, French. All fixtures are synthetic
 * phrases, no personal data.
 */
class AccentFoldTest {
    @Test
    fun `polish diacritics fold to plain letters`() {
        // ł does not NFD-decompose; the explicit override covers it.
        assertThat(AccentFold.fold("zażółć gęślą jaźń")).isEqualTo("zazolc gesla jazn")
        assertThat(AccentFold.fold("ŁÓDŹ")).isEqualTo("LODZ")
    }

    @Test
    fun `czech diacritics fold to plain letters`() {
        assertThat(AccentFold.fold("příliš žluťoučký kůň")).isEqualTo("prilis zlutoucky kun")
        assertThat(AccentFold.fold("Ěščřž ďťň")).isEqualTo("Escrz dtn")
    }

    @Test
    fun `spanish keeps its gsm-native letters and folds the rest`() {
        // ñ ¿ ¡ ü é á(->a? no: á is NOT gsm; à is) - check each faithfully:
        // ñ, ü, ¿, ¡ are GSM-7 and must survive; á í ó ú fold.
        assertThat(AccentFold.fold("¿Qué pasó, Señor? ñandú")).isEqualTo("¿Qué paso, Señor? ñandu")
        assertThat(AccentFold.fold("áíóú")).isEqualTo("aiou")
    }

    @Test
    fun `french keeps gsm-native letters and folds the rest`() {
        // é è ù à ç(->c: ç is not in the GSM basic table) ê ô î û ë ï œ.
        assertThat(AccentFold.fold("être sûr, voilà")).isEqualTo("etre sur, voilà")
        assertThat(AccentFold.fold("garçon")).isEqualTo("garcon")
        assertThat(AccentFold.fold("cœur")).isEqualTo("coeur")
        // é is IN GSM-7: folding it would gain nothing, so it stays.
        assertThat(AccentFold.fold("éclair")).isEqualTo("éclair")
    }

    @Test
    fun `hungarian double acutes fold to the gsm umlauts like android-smsmms`() {
        assertThat(AccentFold.fold("Őrült űrhajó")).isEqualTo("Örült ürhajo")
    }

    @Test
    fun `the fold table knows e-acute has base e even though fold never rewrites it`() {
        // The faithful ASCII twin of é IS e - but fold() leaves é alone
        // because it is already a 1-septet GSM-7 character.
        assertThat(AccentFold.foldChar('é')).isEqualTo("e")
        assertThat(AccentFold.foldChar('č')).isEqualTo("c")
    }

    @Test
    fun `unfaithful folds never happen`() {
        // Emoji, CJK, Cyrillic and Greek have no plain-Latin GSM twin; ß is
        // itself GSM-7. None of them may be touched.
        assertThat(AccentFold.fold("日本語のメッセージ")).isEqualTo("日本語のメッセージ")
        assertThat(AccentFold.fold("привет, как дела")).isEqualTo("привет, как дела")
        assertThat(AccentFold.fold("καλημέρα")).isEqualTo("καλημέρα")
        assertThat(AccentFold.fold("Straße \uD83D\uDE00")).isEqualTo("Straße \uD83D\uDE00")
    }

    @Test
    fun `folding is idempotent`() {
        val texts = listOf("zażółć gęślą jaźń", "příliš žluťoučký kůň", "Ő cœur č 日本 \uD83D\uDE00")
        for (t in texts) {
            assertThat(AccentFold.fold(AccentFold.fold(t))).isEqualTo(AccentFold.fold(t))
        }
    }

    @Test
    fun `no gain means no plan - short message already one segment`() {
        // "ahoj č" is UCS-2 but a single segment either way: folding would
        // rewrite the text for zero saving, so the affordance must not appear.
        assertThat(AccentFold.plan("ahoj číslo")).isNull()
    }

    @Test
    fun `no gain means no plan - pure gsm text`() {
        assertThat(AccentFold.plan("plain text " + "a".repeat(300))).isNull()
    }

    @Test
    fun `no gain means no plan - an emoji keeps the message ucs-2 anyway`() {
        // Folding the č would not rescue this message from UCS-2 (the emoji
        // pins it there), so stripping accents would mangle for nothing.
        val text = "č \uD83D\uDE00 " + "a".repeat(100)
        assertThat(AccentFold.plan(text)).isNull()
    }

    @Test
    fun `plan appears exactly when folding cuts the bill`() {
        // 100 letters + one č: UCS-2, 2 segments; folded: GSM-7, 1 segment.
        val text = "č" + "a".repeat(100)
        val plan = AccentFold.plan(text)
        assertThat(plan).isNotNull()
        assertThat(plan!!.segmentsBefore).isEqualTo(2)
        assertThat(plan.segmentsAfter).isEqualTo(1)
        assertThat(plan.folded).isEqualTo("c" + "a".repeat(100))
        assertThat(plan.original).isEqualTo(text)
    }

    @Test
    fun `a medium czech text collapses from four segments to one`() {
        // The issue's motivating case: ~150 chars with diacritics bill as
        // 3 UCS-2 segments; folded they fit one GSM segment.
        val text =
            "Přijedu zítra večer kolem osmé, vezmu s sebou kytaru a něco dobrého k jídlu. " +
                "Dej vědět, jestli mám vzít i deskové hry nebo půjdeme ven na procházku."
        val plan = AccentFold.plan(text)
        assertThat(plan).isNotNull()
        assertThat(plan!!.segmentsBefore).isEqualTo(3)
        assertThat(plan.segmentsAfter).isEqualTo(1)
        assertThat(SmsSegments.isGsmText(plan.folded)).isTrue()
    }

    @Test
    fun `foldIfItSaves is the fold gated on the plan`() {
        val saving = "č" + "a".repeat(100)
        assertThat(AccentFold.foldIfItSaves(saving)).isEqualTo("c" + "a".repeat(100))
        // No saving: text passes through untouched, accents and all.
        assertThat(AccentFold.foldIfItSaves("ahoj číslo")).isEqualTo("ahoj číslo")
        assertThat(AccentFold.foldIfItSaves("")).isEqualTo("")
    }

    @Test
    fun `blank drafts never plan`() {
        assertThat(AccentFold.plan("")).isNull()
        assertThat(AccentFold.plan("   ")).isNull()
    }
}
