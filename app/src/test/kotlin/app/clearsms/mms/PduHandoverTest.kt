package app.clearsms.mms

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The decisions made before an outgoing PDU is handed to the platform
 * (issue #51): who gets the read grant and in what order, what a
 * failing grant or resolver does, and when the staged file is safe to
 * hand over. The carrier's `maxMessageSize` is no longer overridden here -
 * attachments are sized to fit it instead (see MmsSizeBudgetTest). Pure
 * logic, no framework.
 */
class PduHandoverTest {
    private fun grant(
        resolved: () -> List<String>,
        failing: Set<String> = emptySet(),
    ): Pair<PduReadGrant, MutableList<String>> {
        val attempted = mutableListOf<String>()
        val grant =
            PduReadGrant(resolved) { pkg ->
                attempted += pkg
                if (pkg in failing) throw IllegalArgumentException("Unknown package: $pkg")
            }
        return grant to attempted
    }

    @Test
    fun `resolved readers are granted first, then the two AOSP fallbacks`() {
        val (grant, attempted) = grant({ listOf("com.example.carrier.messaging", "com.oem.telephony") })

        val outcomes = grant.grantAll()

        assertThat(attempted)
            .containsExactly(
                "com.example.carrier.messaging",
                "com.oem.telephony",
                "com.android.phone",
                "com.android.mms.service",
            ).inOrder()
        assertThat(outcomes.map { it.packageName }).isEqualTo(attempted)
        assertThat(outcomes.map { it.resolved }).containsExactly(true, true, false, false).inOrder()
        assertThat(outcomes.all { it.granted }).isTrue()
    }

    @Test
    fun `a resolved package that is also a fallback is granted once, as resolved`() {
        val (grant, attempted) = grant({ listOf("com.android.phone", "com.android.phone") })

        val outcomes = grant.grantAll()

        assertThat(attempted).containsExactly("com.android.phone", "com.android.mms.service").inOrder()
        assertThat(outcomes.single { it.packageName == "com.android.phone" }.resolved).isTrue()
        assertThat(outcomes.single { it.packageName == "com.android.mms.service" }.resolved).isFalse()
    }

    @Test
    fun `empty resolution behaves exactly as before - the two fallbacks only`() {
        val (grant, attempted) = grant({ emptyList() })

        val outcomes = grant.grantAll()

        assertThat(attempted).isEqualTo(PduReadGrant.FALLBACK_PACKAGES)
        assertThat(outcomes.none { it.resolved }).isTrue()
    }

    @Test
    fun `a throwing resolver yields the fallbacks and nothing else`() {
        val (grant, attempted) = grant({ throw SecurityException("package visibility") })

        val outcomes = grant.grantAll()

        assertThat(attempted).isEqualTo(PduReadGrant.FALLBACK_PACKAGES)
        assertThat(outcomes).hasSize(2)
    }

    @Test
    fun `a package the grant throws for is recorded as not granted and the rest still proceed`() {
        val (grant, attempted) =
            grant({ listOf("com.oem.telephony") }, failing = setOf("com.oem.telephony", "com.android.mms.service"))

        val outcomes = grant.grantAll()

        assertThat(attempted).hasSize(3)
        assertThat(outcomes.map { it.packageName to it.granted })
            .containsExactly(
                "com.oem.telephony" to false,
                "com.android.phone" to true,
                "com.android.mms.service" to false,
            ).inOrder()
    }

    @Test
    fun `resolved names that are not package names are ignored - a resolver cannot smuggle junk into a grant or a log`() {
        val (grant, attempted) = grant({ listOf("holiday-with-priya.jpg", "9876543210", "", "com.oem.telephony") })

        grant.grantAll()

        assertThat(attempted).containsExactly("com.oem.telephony", "com.android.phone", "com.android.mms.service").inOrder()
        assertThat(PduReadGrant.isPackageName("com.android.phone")).isTrue()
        assertThat(PduReadGrant.isPackageName("phone")).isFalse()
        assertThat(PduReadGrant.isPackageName("com.1x.y")).isFalse()
    }

    @Test
    fun `the staged file is safe to hand over only when present, non-empty and complete`() {
        assertThat(StagedPduCheck.of(exists = true, lengthBytes = 326_369L, expectedBytes = 326_369).handoverSafe).isTrue()
        // Missing: length is reported as 0 whatever the caller measured.
        val missing = StagedPduCheck.of(exists = false, lengthBytes = 326_369L, expectedBytes = 326_369)
        assertThat(missing.handoverSafe).isFalse()
        assertThat(missing.lengthBytes).isEqualTo(0L)
        // Zero-length: the platform would read 0 bytes and answer IO_ERROR.
        assertThat(StagedPduCheck.of(exists = true, lengthBytes = 0L, expectedBytes = 326_369).handoverSafe).isFalse()
        // Truncated or overwritten by a racing attempt.
        assertThat(StagedPduCheck.of(exists = true, lengthBytes = 12L, expectedBytes = 326_369).handoverSafe).isFalse()
        assertThat(StagedPduCheck.of(exists = true, lengthBytes = 326_370L, expectedBytes = 326_369).handoverSafe).isFalse()
    }
}
