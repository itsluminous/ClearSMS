package app.clearsms.domain.categorizer

import app.clearsms.domain.model.SenderInfo

/**
 * Resolves an SMS sender ID against the bundled sender directory.
 *
 * Implemented by the sender ID store in the data layer; extracted as an
 * interface so the categorizer can be unit tested with fakes.
 */
fun interface SenderIdLookup {
    fun lookup(senderId: String): SenderInfo?
}
