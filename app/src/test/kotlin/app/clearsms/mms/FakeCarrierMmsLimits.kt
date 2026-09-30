package app.clearsms.mms

/**
 * A carrier that reports [maxMessageSizeBytes] as its MMS `maxMessageSize`
 * for every SIM (null = the platform will not say). Tests use it to be
 * the reporter's 300 KiB carrier, a generous 1 MiB one, or none at all.
 */
class FakeCarrierMmsLimits(
    var maxMessageSizeBytes: Int? = null,
) : CarrierMmsLimits {
    /** Every subscription the limit was asked for, in order. */
    val asked = mutableListOf<Int?>()

    override fun maxMessageSizeBytes(subscriptionId: Int?): Int? {
        asked += subscriptionId
        return maxMessageSizeBytes
    }
}
