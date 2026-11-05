package org.stride.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import org.stride.core.common.ApplicationScope
import org.stride.core.garmin.GarminKeyProvider
import org.stride.core.garmin.TokenCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecretsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PrefsStore

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    @SecretsStore
    fun secretsDataStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(SecretStore.FILE_NAME) }

    @Provides
    @Singleton
    @PrefsStore
    fun prefsDataStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(UserPreferences.FILE_NAME) }

    @Provides
    @Singleton
    fun secretCipher(@ApplicationContext context: Context): SecretCipher = AeadSecretCipher(KeystoreAead.create(context))

    @Provides
    @Singleton
    fun secretStore(@SecretsStore dataStore: DataStore<Preferences>, cipher: SecretCipher): SecretStore = SecretStore(dataStore, cipher)

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
