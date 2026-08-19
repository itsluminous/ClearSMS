package app.clearsms.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import app.clearsms.BuildConfig
import app.clearsms.data.backup.BackupManager
import app.clearsms.data.backup.SettingsBackupManager
import app.clearsms.data.db.AccountDao
import app.clearsms.data.db.AttachmentDao
import app.clearsms.data.db.BackfillMessageDirections
import app.clearsms.data.db.ClearSmsDatabase
import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.ReminderDao
import app.clearsms.data.db.RuleDao
import app.clearsms.data.db.TransactionDao
import app.clearsms.data.prefs.SettingsRepository
import app.clearsms.data.prefs.SettingsRepositoryImpl
import app.clearsms.data.repository.FinanceRepository
import app.clearsms.data.repository.FinanceRepositoryImpl
import app.clearsms.data.repository.MessageRepository
import app.clearsms.data.repository.MessageRepositoryImpl
import app.clearsms.data.repository.RuleRepository
import app.clearsms.data.repository.RuleRepositoryImpl
import app.clearsms.data.repository.UndoManager
import app.clearsms.data.rules.BundledRuleLoader
import app.clearsms.data.rules.RuleEngine
import app.clearsms.data.rules.RuleExporter
import app.clearsms.data.rules.RuleImporter
import app.clearsms.data.senderid.SenderIdStore
import app.clearsms.domain.categorizer.ContactLookup
import app.clearsms.domain.categorizer.MessageCategorizer
import app.clearsms.mms.AttachmentStore
import app.clearsms.notification.NotificationDismisser
import app.clearsms.receiver.DefaultSendReportSideEffects
import app.clearsms.receiver.SendReportSideEffects
import app.clearsms.sms.SystemSentSmsSource
import app.clearsms.sms.TelephonyWriter
import app.clearsms.work.BackupDocumentStore
import app.clearsms.work.SafBackupDocumentStore
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.util.Optional
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifier for the IO dispatcher used by data-layer work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Qualifier for the UI-preferences DataStore ("ui_settings") as opposed to
 * the core settings DataStore. Injected so tests can substitute an isolated
 * store - binding the process-wide delegate inside UiPrefs made Robolectric
 * test classes poison each other through the cached static singleton.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UiSettingsDataStore

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.uiPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ui_settings")

/**
 * Optional bindings satisfied by other layers.
 *
 * [ContactLookup] is implemented by the platform layer (contacts provider);
 * until that binding exists the categorizer treats every sender as a
 * non-contact.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface DataOptionalBindings {
    @BindsOptionalOf
    fun contactLookup(): ContactLookup
}

/** Interface bindings for the data layer. */
@Module
@InstallIn(SingletonComponent::class)
internal interface DataBindings {
    /** SAF-backed document access for the periodic backup worker. */
    @Binds
    fun backupDocumentStoreFactory(impl: SafBackupDocumentStore.Factory): BackupDocumentStore.Factory

    /** Provider mirroring + failure notification for outgoing send reports. */
    @Binds
    fun sendReportSideEffects(impl: DefaultSendReportSideEffects): SendReportSideEffects
}

/** Hilt wiring for the data and domain layers. */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): ClearSmsDatabase {
        // The v6→v7 direction backfill reads the system SMS provider's sent
        // box; hand it the source before the database can open and migrate.
        BackfillMessageDirections.sentSmsSource = SystemSentSmsSource(context)
        return Room
            .databaseBuilder(context, ClearSmsDatabase::class.java, ClearSmsDatabase.NAME)
            .build()
    }

    @Provides
    fun provideMessageDao(db: ClearSmsDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideAccountDao(db: ClearSmsDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideTransactionDao(db: ClearSmsDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideRuleDao(db: ClearSmsDatabase): RuleDao = db.ruleDao()

    @Provides
    fun provideReminderDao(db: ClearSmsDatabase): ReminderDao = db.reminderDao()

    @Provides
    fun provideAttachmentDao(db: ClearSmsDatabase): AttachmentDao = db.attachmentDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    @UiSettingsDataStore
    fun provideUiSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.uiPrefsDataStore

    @Provides
    @Singleton
    fun provideSenderIdStore(
        @ApplicationContext context: Context,
    ): SenderIdStore = SenderIdStore(context)

    @Provides
    @Singleton
    fun provideRuleEngine(): RuleEngine = RuleEngine(log = { Log.w("RuleEngine", it) })

    @Provides
    @Singleton
    fun provideBundledRuleLoader(
        @ApplicationContext context: Context,
        ruleDao: RuleDao,
        json: Json,
        dataStore: DataStore<Preferences>,
    ): BundledRuleLoader = BundledRuleLoader(context, ruleDao, json, dataStore)

    @Provides
    @Singleton
    fun provideMessageCategorizer(
        ruleEngine: RuleEngine,
        senderIdStore: SenderIdStore,
        contactLookup: Optional<ContactLookup>,
    ): MessageCategorizer =
        MessageCategorizer(
            ruleEngine = ruleEngine,
            senderIdLookup = senderIdStore,
            contactLookup = ContactLookup { address -> contactLookup.map { it.isContact(address) }.orElse(false) },
        )

    @Provides
    @Singleton
    fun provideMessageRepositoryImpl(
        database: ClearSmsDatabase,
        categorizer: MessageCategorizer,
        bundledRuleLoader: BundledRuleLoader,
        json: Json,
        telephonyWriter: TelephonyWriter,
        notificationDismisser: NotificationDismisser,
        settingsRepository: SettingsRepository,
        attachmentStore: AttachmentStore,
    ): MessageRepositoryImpl =
        MessageRepositoryImpl(
            database = database,
            categorizer = categorizer,
            bundledRuleLoader = bundledRuleLoader,
            json = json,
            systemSmsDeleter = telephonyWriter,
            systemSmsReadWriter = telephonyWriter,
            systemSmsReinserter = telephonyWriter,
            readNotificationCanceler = notificationDismisser,
            blockedKeywords = { settingsRepository.blockedKeywords.first() },
            blockedSenders = { settingsRepository.blockedSenders.first() },
            recycleBinEnabled = { settingsRepository.recycleBinEnabled.first() },
            attachmentFileCleaner = attachmentStore::deleteFor,
        )

    @Provides
    @Singleton
    fun provideMessageRepository(impl: MessageRepositoryImpl): MessageRepository = impl

    @Provides
    @Singleton
    fun provideUndoManager(
        repository: MessageRepository,
        settings: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ): UndoManager =
        UndoManager(
            repository = repository,
            scope = scope,
            recycleBinEnabled = { settings.recycleBinEnabled.first() },
        )

    @Provides
    @Singleton
    fun provideFinanceRepository(
        transactionDao: TransactionDao,
        accountDao: AccountDao,
        reminderDao: ReminderDao,
    ): FinanceRepository = FinanceRepositoryImpl(transactionDao, accountDao, reminderDao)

    @Provides
    @Singleton
    fun provideRuleRepository(
        ruleDao: RuleDao,
        bundledRuleLoader: BundledRuleLoader,
        json: Json,
    ): RuleRepository = RuleRepositoryImpl(ruleDao, bundledRuleLoader, RuleImporter(json), RuleExporter(json), json)

    @Provides
    @Singleton
    fun provideSettingsRepository(dataStore: DataStore<Preferences>): SettingsRepository = SettingsRepositoryImpl(dataStore)

    @Provides
    @Singleton
    fun provideBackupManager(
        database: ClearSmsDatabase,
        json: Json,
    ): BackupManager = BackupManager(database, json)

    @Provides
    @Singleton
    fun provideSettingsBackupManager(
        dataStore: DataStore<Preferences>,
        json: Json,
    ): SettingsBackupManager = SettingsBackupManager(dataStore, json, BuildConfig.VERSION_NAME)
}
