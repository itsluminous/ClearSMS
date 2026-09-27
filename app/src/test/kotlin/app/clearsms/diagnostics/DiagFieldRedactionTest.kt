package app.clearsms.diagnostics

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.jvm.jvmErasure

/**
 * Redaction by construction: each sensitive kind of value has NO path into
 * the buffer, and the one string-shaped input (a sender) drops anything
 * phone-number-shaped at the logger.
 */
class DiagFieldRedactionTest {
    private fun render(vararg fields: DiagField): String = DiagLine.render(0L, DiagLevel.INFO, "T", "event", fields.toList(), null).text

    @Test
    fun `there is no factory that accepts an arbitrary string value`() {
        val factories =
            DiagField.Companion::class
                .declaredFunctions
                .filter { it.visibility == KVisibility.PUBLIC }
        assertThat(factories.map { it.name }).containsExactly("count", "count", "flag", "id", "code", "label", "ruleId", "sender", "mime")
        factories.forEach { factory ->
            // Skip the receiver (Companion) parameter.
            val params = factory.parameters.drop(1)
            val stringParams = params.filter { it.type.jvmErasure == String::class }
            when (factory.name) {
                // The three validated string inputs take exactly one String: the value itself.
                "sender", "ruleId", "mime" -> assertThat(params).hasSize(1)
                else -> {
                    // Every other factory's only String is the field NAME; the
                    // value is Int / Long / Boolean / Enum.
                    assertWithMessage(factory.name).that(stringParams.map { it.name }).containsExactly("name")
                    val valueType = params.last().type.jvmErasure
                    assertWithMessage(factory.name)
                        .that(
                            valueType == Int::class ||
                                valueType == Long::class ||
                                valueType == Boolean::class ||
                                valueType.isSubclassOf(Enum::class),
                        ).isTrue()
                }
            }
        }
    }

    @Test
    fun `the logger API itself accepts only tag, event, throwable and fields`() {
        Diag::class
            .declaredFunctions
            .filter { it.visibility == KVisibility.PUBLIC && it.name in setOf("d", "i", "w", "e") }
            .forEach { fn ->
                val params = fn.parameters.drop(1)
                assertWithMessage(fn.name).that(params.map { it.name }).containsAtLeast("tag", "event", "fields")
                assertWithMessage(fn.name)
                    .that(params.filter { it.type.jvmErasure == String::class }.map { it.name })
                    .containsExactly("tag", "event")
                assertWithMessage(fn.name).that(params.last().isVararg).isTrue()
            }
    }

    @Test
    fun `an alphanumeric sender id is kept verbatim`() {
        listOf("VM-HDFCBK", "HDFCBK", "AX-AMAZON-S", "JD-ICICIB", "AD-SBIINB-P", "56767", "121", "CP-A1B2C3").forEach {
            assertWithMessage(it).that(LoggableSender.of(it)).isEqualTo(it)
            assertWithMessage(it).that(render(DiagField.sender(it))).contains("sender=$it")
        }
    }

    @Test
    fun `a phone-number-shaped sender is dropped at the logger`() {
        listOf(
            "9876543210",
            "+919876543210",
            "+91 98765 43210",
            "098765-43210",
            "(022) 2345 6789",
            "1800123456",
            "12345678",
            "9876543",
            "Call 9876543210",
        ).forEach {
            assertWithMessage(it).that(LoggableSender.of(it)).isEqualTo(DiagField.PHONE)
            val text = render(DiagField.sender(it))
            assertWithMessage(it).that(text).contains("sender=[phone]")
            assertWithMessage(it).that(text).doesNotContainMatch("\\d{7,}")
        }
    }

    @Test
    fun `anything that is neither a header nor a number is dropped too`() {
        listOf("someone@example.com", "Your OTP is 4321 do not share", "", "   ", "a b c").forEach {
            assertWithMessage(it).that(LoggableSender.of(it)).isEqualTo(DiagField.DROPPED)
        }
        assertThat(LoggableSender.of(null)).isEqualTo("null")
    }

    @Test
    fun `rule ids keep their public shape and drop everything else`() {
        assertThat(render(DiagField.ruleId("generic-scam-01"))).contains("rule=generic-scam-01")
        assertThat(render(DiagField.ruleId("user_1a2b3c4d"))).contains("rule=user_1a2b3c4d")
        assertThat(render(DiagField.ruleId(null))).contains("rule=null")
        // A digit-only id is exactly the shape an OTP or a number would take.
        assertThat(render(DiagField.ruleId("987654"))).contains("rule=[dropped]")
        assertThat(render(DiagField.ruleId("has space"))).contains("rule=[dropped]")
        assertThat(render(DiagField.ruleId("x".repeat(80)))).contains("rule=[dropped]")
    }

    @Test
    fun `mime types keep a bare type-subtype token and drop everything else`() {
        assertThat(render(DiagField.mime("image/jpeg"))).contains("mime=image/jpeg")
        assertThat(render(DiagField.mime(" Video/MP4 "))).contains("mime=video/mp4")
        assertThat(render(DiagField.mime("application/vnd.oma.drm.message"))).contains("mime=application/vnd.oma.drm.message")
        assertThat(render(DiagField.mime(null))).contains("mime=null")
        // A body, a name, a file name, a number or a made-up top-level type
        // is not a MIME token and never travels.
        listOf(
            "Your OTP is 4321",
            "photo of us.jpg",
            "IMG_20260927_1234.jpg",
            "9876543210",
            "alice/bob",
            "image/jpeg; name=holiday.jpg",
            "image/",
            "",
        ).forEach {
            assertWithMessage(it).that(render(DiagField.mime(it))).contains("mime=[dropped]")
        }
    }

    @Test
    fun `exception messages never reach the buffer - only classes and frames`() {
        val cause = IllegalArgumentException("account 1234567890 vpa alice@upi amount 4,500.00")
        val error = IllegalStateException("OTP 654321 from 9876543210", cause)
        val text = DiagLine.render(0L, DiagLevel.ERROR, "T", "boom", emptyList(), error).text

        assertThat(text).contains("! java.lang.IllegalStateException")
        assertThat(text).contains("caused by java.lang.IllegalArgumentException")
        assertThat(text).contains("at app.clearsms.diagnostics.DiagFieldRedactionTest")
        assertThat(text).doesNotContain("654321")
        assertThat(text).doesNotContain("9876543210")
        assertThat(text).doesNotContain("alice")
        assertThat(text).doesNotContain("4,500")
    }

    @Test
    fun `structured fields render as name=value and cannot smuggle a body through the name`() {
        val text =
            render(
                DiagField.count("parts", 3),
                DiagField.flag("duplicate", false),
                DiagField.id("message", 42L),
                DiagField.code("result", 1),
                DiagField.label("level", DiagLevel.WARN),
            )
        assertThat(text).endsWith("event parts=3 duplicate=false message=42 result=1 level=WARN")
        // A name with whitespace or '=' would break the line grammar the
        // masker and the file parser rely on; it is flattened, never honoured.
        assertThat(render(DiagField.count("a b=c", 1))).contains("a_b_c=1")
    }
}
