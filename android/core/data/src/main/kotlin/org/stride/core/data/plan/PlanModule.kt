package org.stride.core.data.plan

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import org.stride.core.common.ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlanStore

@Module
@InstallIn(SingletonComponent::class)
object PlanModule {
    @Provides
    @Singleton
    @PlanStore
    fun planDataStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(PlanRepository.FILE_NAME) }
}
