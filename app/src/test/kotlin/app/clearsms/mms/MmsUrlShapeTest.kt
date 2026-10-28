package app.clearsms.mms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The URL-shape derivation is pure; pin it on the shapes a carrier MMSC URL takes. */
class MmsUrlShapeTest {
    @Test
    fun `host is the authority without scheme, user-info, port or path`() {
        assertThat(MmsUrlShape.of("http://mms.example.com/servlets/mms?message-id=abc").host).isEqualTo("mms.example.com")
        assertThat(MmsUrlShape.of("HTTP://MMSC.Example.NET:8002/mm1/x").host).isEqualTo("mmsc.example.net")
        assertThat(MmsUrlShape.of("http://user:pw@10.1.2.3:80/a/b").host).isEqualTo("10.1.2.3")
        assertThat(MmsUrlShape.of("http://[2001:db8::1]/a").host).isEqualTo("2001:db8::1")
        assertThat(MmsUrlShape.of("mms?no-scheme").host).isNull()
        assertThat(MmsUrlShape.of("http://").host).isNull()
    }

    @Test
    fun `length, query and non-ASCII describe the whole URL without repeating it`() {
        val plain = MmsUrlShape.of("http://mms.example.com/servlets/mms?message-id=abc")
        assertThat(plain.length).isEqualTo(50)
        assertThat(plain.hasQuery).isTrue()
        assertThat(plain.nonAscii).isFalse()

        val mangled = MmsUrlShape.of("http://mms.example.com/\uFFFDmsg/1")
        assertThat(mangled.hasQuery).isFalse()
        assertThat(mangled.nonAscii).isTrue()
    }

    @Test
    fun `the query is reduced to parameter names and value lengths - never a value`() {
        // Issue #135: `message-id(0)` versus `message-id(16)` is what tells
        // an id we dropped from an id the carrier refused.
        assertThat(MmsUrlShape.of("http://192.0.2.1/servlets/mms?message-id=ABC123").query)
            .containsExactly("message-id" to 6)
            .inOrder()
        assertThat(MmsUrlShape.of("http://192.0.2.1/servlets/mms?message-id=").query)
            .containsExactly("message-id" to 0)
        assertThat(MmsUrlShape.of("http://192.0.2.1/mms?id=abcdefghijklmnop&t=1&flag").query)
            .containsExactly("id" to 16, "t" to 1, null to 4)
            .inOrder()
        // A parameter with an empty name, a value holding `=`, a fragment.
        assertThat(MmsUrlShape.of("http://192.0.2.1/mms?=x&k=a=b#frag").query)
            .containsExactly(null to 1, "k" to 3)
            .inOrder()
        // No `?` at all is null; a bare `?` is an empty list - each says so.
        assertThat(MmsUrlShape.of("http://192.0.2.1/mms/1").query).isNull()
        assertThat(MmsUrlShape.of("http://192.0.2.1/mms/1?").query).isEmpty()
        assertThat(MmsUrlShape.of("http://192.0.2.1/mms/1?&").query).isEmpty()
    }

    @Test
    fun `the path travels as a mask that keeps its outline and nothing it could name`() {
        assertThat(MmsUrlShape.of("http://192.0.2.1/servlets/mms?message-id=ABC123").mask).isEqualTo("/xxxxxxxx/xxx")
        assertThat(MmsUrlShape.of("http://192.0.2.1:8080/mm1/Msg_42.pdu;v=1~x").mask).isEqualTo("/xx#/xxx_##.xxx%x%#~x")
        assertThat(MmsUrlShape.of("http://192.0.2.1").mask).isEmpty()
        assertThat(MmsUrlShape.of("http://192.0.2.1?x=1").mask).isEmpty()
        // Non-ASCII letters are letters; a stray control octet is `%`.
        assertThat(MmsUrlShape.of("http://192.0.2.1/caf\u00e9/\u0001").mask).isEqualTo("/xxxx/%")
        // Without a scheme the whole string is the resource, as before: the
        // `?` still counts as a query and the host stays unknown.
        val schemeless = MmsUrlShape.of("mms?no-scheme")
        assertThat(schemeless.hasQuery).isTrue()
        assertThat(schemeless.query).containsExactly(null to 9)
        assertThat(schemeless.mask).isEqualTo("xxx")
    }

    @Test
    fun `the 44-character arithmetic - an IPv4 MMSC URL with an empty message id is exactly 44`() {
        // The reporter's two URLs were 44 characters each, with a query.
        // `http://` + a 12-character IPv4 + `/servlets/mms?message-id=` with
        // NO value is exactly that, and renders as message-id(0).
        val url = "http://10.10.200.30/servlets/mms?message-id="
        val shape = MmsUrlShape.of(url)
        assertThat(shape.length).isEqualTo(44)
        assertThat(shape.hasQuery).isTrue()
        assertThat(shape.query).containsExactly("message-id" to 0)
        assertThat(shape.mask).isEqualTo("/xxxxxxxx/xxx")
    }
}
