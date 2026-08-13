package app.clearsms.di

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.mms.MmsDownloader
import app.clearsms.mms.SystemMmsDownloader
import app.clearsms.sms.ContactLookupImpl
import app.clearsms.sms.DeviceSubscriptionSource
import app.clearsms.sms.FrameworkSmsGateway
import app.clearsms.sms.SmsGateway
import app.clearsms.sms.SubscriptionSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
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
    ): CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher + ingestionExceptionHandler())

    /**
     * Last-line exception handler for the application scope. A SupervisorJob
     * only stops failures from cancelling sibling jobs - it does NOT swallow
     * an exception thrown by a root `launch`; without a handler it would
     * reach the thread's default handler and crash the process. Work in this
     * scope is triggered by incoming broadcasts (attacker-influenced data),
     * so an unexpected failure must degrade into a logged, dropped unit of
     * work - never a crash of the user's default SMS app.
     */
    private fun ingestionExceptionHandler(): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            Log.e("ClearSmsAppScope", "Uncaught exception in application scope", throwable)
        }

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)
}

/** Fulfils the data layer's optional [ContactLookup] binding. */
@Module
@InstallIn(SingletonComponent::class)
internal interface PlatformBindings {
    @Binds
    fun contactLookup(impl: ContactLookupImpl): ContactLookup

    /** Framework-backed SIM subscription access for the dual-SIM send UI. */
    @Binds
    fun subscriptionSource(impl: DeviceSubscriptionSource): SubscriptionSource

    /** Framework-backed radio access for [app.clearsms.sms.SmsSender]. */
    @Binds
    fun smsGateway(impl: FrameworkSmsGateway): SmsGateway

    /** Platform MMS retrieval for [app.clearsms.mms.MmsInbound]. */
    @Binds
    fun mmsDownloader(impl: SystemMmsDownloader): MmsDownloader
}
