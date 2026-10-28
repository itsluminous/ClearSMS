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
        assertThat(
            factories.map {
                it.name
            },
        ).containsExactly(
            "count",
            "count",
            "flag",
            "id",
            "code",
            "label",
            "ruleId",
            "sender",
            "mime",
            "packageName",
            "host",
            "urlMask",
            "query",
        )
        factories.forEach { factory ->
            // Skip the receiver (Companion) parameter.
            val params = factory.parameters.drop(1)
            val stringParams = params.filter { it.type.jvmErasure == String::class }
            when (factory.name) {
                // The six validated string inputs take exactly one String: the value itself.
                "sender", "ruleId", "mime", "packageName", "host", "urlMask" -> {
                    assertThat(params).hasSize(1)
                }

                // The query takes (name, value LENGTH) pairs - no String can
                // reach it as a value, only as a validated parameter name.
                "query" -> {
                    assertThat(params).hasSize(1)
                    assertThat(stringParams).isEmpty()
                    assertThat(params.single().type.jvmErasure).isEqualTo(List::class)
                }

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
    fun `package names keep a dotted identifier and drop everything else`() {
        assertThat(render(DiagField.packageName("com.android.phone"))).contains("package=com.android.phone")
        assertThat(render(DiagField.packageName("com.android.mms.service"))).contains("package=com.android.mms.service")
        assertThat(render(DiagField.packageName("com.example.carrier_msg.Svc2"))).contains("package=com.example.carrier_msg.Svc2")
        assertThat(render(DiagField.packageName(null))).contains("package=null")
        // A file name, a body, a number, an email or a single segment is not
        // a package name and never travels.
        listOf(
            "holiday-with-priya.jpg",
            "Your OTP is 4321",
            "9876543210",
            "alice@example.com",
            "phone",
            "com..android",
            ".com.android",
            "com.1android",
            "com.android.phone ",
            "",
            "a." + "b".repeat(260),
        ).forEach {
            assertWithMessage(it).that(render(DiagField.packageName(it))).contains("package=[dropped]")
        }
    }

    @Test
    fun `host names keep a bare carrier host and drop everything else`() {
        // Issue #135: the MMSC host out of a notification's content location
        // is what tells a wrong route from a wrong path - and it is carrier
        // infrastructure, never the message's identity.
        assertThat(render(DiagField.host("mms.vtext.com"))).contains("host=mms.vtext.com")
        assertThat(render(DiagField.host("MMSC.Example.NET"))).contains("host=mmsc.example.net")
        assertThat(render(DiagField.host("10.1.2.3"))).contains("host=10.1.2.3")
        assertThat(render(DiagField.host("mmsc"))).contains("host=mmsc")
        assertThat(render(DiagField.host(null))).contains("host=null")
        // A URL, a path, a query, a token, a body or a number with its
        // punctuation is not a host name and never travels.
        listOf(
            "http://mms.vtext.com/servlets/mms?message-id=abc",
            "mms.vtext.com/servlets/mms",
            "mms.vtext.com:8080",
            "servlets/mms?message-id=abc",
            "message-id=abc",
            "user@mms.vtext.com",
            "+1 555 123 4567",
            "Your OTP is 4321",
            "-mms.vtext.com",
            "mms..vtext.com",
            "mms_c.vtext.com",
            "",
            "a".repeat(64) + ".com",
            "abc.".repeat(70) + "com",
        ).forEach {
            assertWithMessage(it).that(render(DiagField.host(it))).contains("host=[dropped]")
        }
    }

    @Test
    fun `a URL mask keeps only its own alphabet - a path's text can never travel`() {
        // Issue #135: the path's outline tells `/servlets/mms` from `/mm1`
        // without a character that could spell a message id.
        assertThat(render(DiagField.urlMask("/xxxxxxxx/xxx"))).contains("urlMask=/xxxxxxxx/xxx")
        assertThat(render(DiagField.urlMask("/xx#/xxx_##.xxx%~-"))).contains("urlMask=/xx#/xxx_##.xxx%~-")
        assertThat(render(DiagField.urlMask(""))).contains("urlMask=empty")
        assertThat(render(DiagField.urlMask(null))).contains("urlMask=null")
        listOf(
            "/servlets/mms",
            "/xxxxxxxx/mms",
            "/xxxx/ABC123",
            "/xxxx/#####1",
            "/xxxx?message-id=abc",
            "x x",
            "X",
            "/" + "x".repeat(128),
        ).forEach {
            assertWithMessage(it).that(render(DiagField.urlMask(it))).contains("urlMask=[dropped]")
        }
    }

    @Test
    fun `a query renders parameter names and value lengths - the factory never sees a value`() {
        // `message-id(0)` is the decisive datum of issue #135's fourth round.
        assertThat(render(DiagField.query(listOf("message-id" to 0)))).contains("query=message-id(0)")
        assertThat(render(DiagField.query(listOf("message-id" to 16)))).contains("query=message-id(16)")
        assertThat(render(DiagField.query(listOf("id" to 16, "t" to 1, null to 4)))).contains("query=id(16)&t(1)&(4)")
        assertThat(render(DiagField.query(listOf("Msg_Id.v2" to 3)))).contains("query=Msg_Id.v2(3)")
        assertThat(render(DiagField.query(null))).contains("query=none")
        assertThat(render(DiagField.query(emptyList()))).contains("query=empty")
        assertThat(render(DiagField.query(listOf("k" to -1)))).contains("query=k(0)")
        // A name that is an id in disguise, a number, a body, a space, too
        // long or too many parameters: the name is dropped, the length kept.
        listOf(
            "0123456789abcdef",
            "abc1234567",
            "9876543210",
            "message id",
            "message-id=abc",
            "a&b",
            "x".repeat(33),
            "-leading",
            "",
        ).forEach {
            assertWithMessage(it).that(render(DiagField.query(listOf(it to 7)))).contains("query=[dropped](7)")
        }
        assertThat(render(DiagField.query(List(17) { "p$it" to 1 }))).contains("query=[dropped]")
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
