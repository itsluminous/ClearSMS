package app.clearsms.ui.finance

import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.MessageEntity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Narrow read-only message lookup used by the finance/alerts screens to trace
 * a card back to its source SMS. Keeps the ViewModels decoupled from the full
 * [MessageDao] surface and trivially fakeable in tests.
 */
fun interface MessageLookup {
    suspend fun byId(id: Long): MessageEntity?
}

@Module
@InstallIn(SingletonComponent::class)
object MessageLookupModule {
    @Provides
    @Singleton
    fun provideMessageLookup(messageDao: MessageDao): MessageLookup = MessageLookup(messageDao::getById)
}
