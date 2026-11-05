package org.stride.core.database

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import org.stride.core.common.ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /**
     * Starts on the real database; core:data switches to the demo database when
     * the persisted demo flag is on (it is read off the main thread at startup).
     */
    @Provides
    @Singleton
    fun databaseHolder(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DatabaseHolder =
        // The app scope closes a replaced database after a grace period (AUD-030).
        DatabaseHolder.forContext(context, initialDemo = false, closeScope = scope)
}
