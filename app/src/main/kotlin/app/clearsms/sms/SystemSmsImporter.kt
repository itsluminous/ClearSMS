package app.clearsms.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.SenderNormalizer
import app.clearsms.di.IoDispatcher
import app.clearsms.domain.model.Category
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bulk-imports the existing message history from the system SMS provider
 * (`content://sms`) into the local database.
 *
 * Received messages go through [MessageRepository.insertIncoming] so the full
 * categorization + extraction pipeline runs over the user's history; outgoing
 * (sent) messages are stored directly as read personal messages.
 */
@Singleton
class SystemSmsImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val messageRepository: MessageRepository,
        private val messageDao: MessageDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Imports all inbox and sent messages, oldest first, invoking
         * [onProgress] with (imported, total) after each message.
         *
         * @return the number of messages imported.
         */
        suspend fun importAll(onProgress: suspend (imported: Int, total: Int) -> Unit = { _, _ -> }): Int =
            withContext(ioDispatcher) {
                val resolver = context.contentResolver
                val projection =
                    arrayOf(
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE,
                        Telephony.Sms.READ,
                    )
                val cursor =
                    try {
                        resolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} ASC")
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot query the system SMS provider", e)
                        null
                    } ?: return@withContext 0

                var imported = 0
                cursor.use {
                    val total = it.count
                    val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                    val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                    val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                    val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)
                    val readIdx = it.getColumnIndex(Telephony.Sms.READ)
                    while (it.moveToNext()) {
                        val address = it.getString(addressIdx) ?: continue
                        val body = it.getString(bodyIdx) ?: continue
                        val date = it.getLong(dateIdx)
                        val read = it.getInt(readIdx) == 1
                        when (it.getInt(typeIdx)) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> {
                                val entity = messageRepository.insertIncoming(address, body, date)
                                if (read) messageRepository.markRead(entity.id)
                                imported++
                            }
                            Telephony.Sms.MESSAGE_TYPE_SENT -> {
                                insertSent(address, body, date)
                                imported++
                            }
                            // Drafts, outbox and failed messages are skipped.
                            else -> Unit
                        }
                        onProgress(imported, total)
                    }
                }
                imported
            }

        private suspend fun insertSent(
            destination: String,
            body: String,
            timestampMs: Long,
        ) {
            val normalized = SenderNormalizer.normalize(destination)
            val threadId = messageDao.threadIdFor(normalized) ?: ((messageDao.maxThreadId() ?: 0L) + 1L)
            messageDao.insert(
                MessageEntity(
                    threadId = threadId,
                    sender = destination,
                    normalizedSender = normalized,
                    body = body,
                    timestamp = timestampMs,
                    isRead = true,
                    category = Category.PERSONAL,
                ),
            )
        }

        private companion object {
            const val TAG = "SystemSmsImporter"
        }
    }
