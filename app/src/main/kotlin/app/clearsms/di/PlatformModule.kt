package app.clearsms.di

import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.sms.ContactLookupImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Application-lifetime coroutine scope for work started from broadcast
 * receivers and services (paired with `goAsync()` so ingestion survives the
 * receiver's return).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Hilt wiring for the platform layer (receivers, notifications, SMS I/O). */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
}

/** Fulfils the data layer's optional [ContactLookup] binding. */
@Module
@InstallIn(SingletonComponent::class)
internal interface PlatformBindings {
    @Binds
    fun contactLookup(impl: ContactLookupImpl): ContactLookup
}
