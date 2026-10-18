package app.clearsms.sms

import app.clearsms.data.db.MessageDao
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.data.repository.ThreadIdentity
import app.clearsms.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Does this recipient already have a conversation?" for the composer
 * (issue #119): the thread a message to [threadFor]'s address WOULD JOIN,
 * decided by [ThreadIdentity.existing] with the platform's thread id as
 * the primary key and the sender key as the fallback - exactly the order
 * every insert uses, so the conversation opened is the one the sent
 * message would land in. Never a raw number match: a person whose
 * dialling variants #42 merged into one thread must be routed to THAT
 * thread, not to whichever variant happened to be typed.
 *
 * A thread counts only while it has a live message: a conversation that
 * was binned whole is history the user removed, so the composer stays put.
 */
@Singleton
class ExistingThreadResolver
    @Inject
    constructor(
        private val messageDao: MessageDao,
        private val providerThreads: ProviderThreadLookup,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun threadFor(address: String): Long? {
            val trimmed = address.trim()
            if (trimmed.isEmpty()) return null
            return withContext(ioDispatcher) {
                val normalized = SenderNormalizer.normalize(trimmed)
                val providerThreadId = providerThreads.threadIdFor(trimmed)
                ThreadIdentity
                    .existing(messageDao, trimmed, normalized, providerThreadId)
                    ?.takeIf { messageDao.firstInThread(it) != null }
            }
        }
    }
