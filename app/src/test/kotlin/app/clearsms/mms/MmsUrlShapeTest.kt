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
}
