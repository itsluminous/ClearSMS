package app.clearsms.data.db

/** One (app thread, sender key) pair anchored to a provider thread - see [MessageDao.threadsAnchoredTo]. */
data class ThreadAnchor(
    val threadId: Long,
    val normalizedSender: String,
)
