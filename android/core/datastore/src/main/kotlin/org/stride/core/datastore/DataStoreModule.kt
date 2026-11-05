package org.stride.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.stride.core.common.ApplicationScope
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideDispatchers
import org.stride.core.common.StrideLog
import org.stride.core.garmin.GarminKeyProvider
import org.stride.core.garmin.TokenCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecretsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PrefsStore

/**
 * A preferences DataStore that survives a corrupt file (AUD-027): instead of throwing
 * `CorruptionException` on every read, the file is replaced with empty preferences
 * (defaults) and the event is logged.
 */
fun resilientPreferencesDataStore(scope: CoroutineScope, name: String, produceFile: () -> File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { e ->
            StrideLog.w("DataStore", "$name was corrupt; replaced with defaults", e)
            emptyPreferences()
        },
        scope = scope,
        produceFile = produceFile
    )

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    @SecretsStore
    fun secretsDataStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        resilientPreferencesDataStore(scope, SecretStore.FILE_NAME) { context.preferencesDataStoreFile(SecretStore.FILE_NAME) }

    @Provides
    @Singleton
    @PrefsStore
    fun prefsDataStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        resilientPreferencesDataStore(scope, UserPreferences.FILE_NAME) { context.preferencesDataStoreFile(UserPreferences.FILE_NAME) }

    @Provides
    @Singleton
    // Lazy and self-healing: a broken Keystore keyset must not crash injection in Application.onCreate (AUD-027).
    fun secretCipher(@ApplicationContext context: Context): SecretCipher = KeystoreAead.recoveringCipher(context)

    @Provides
    @Singleton
    fun secretStore(
        @SecretsStore dataStore: DataStore<Preferences>,
        cipher: SecretCipher,
        @Dispatcher(StrideDispatchers.IO) io: CoroutineDispatcher
    ): SecretStore = SecretStore(dataStore, cipher, io)

    @Provides
    @Singleton
    fun userPreferences(@PrefsStore dataStore: DataStore<Preferences>): UserPreferences = UserPreferences(dataStore)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SecretBindings {
    @Binds
    abstract fun keyProvider(store: SecretStore): GarminKeyProvider

    @Binds
    abstract fun tokenCache(store: SecretStore): TokenCache
}
