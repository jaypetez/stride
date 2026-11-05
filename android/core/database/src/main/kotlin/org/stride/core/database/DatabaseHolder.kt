package org.stride.core.database

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the active database: the real one or the demo one. Repositories observe
 * [current] so every Flow switches over when demo mode is entered or exited.
 */
class DatabaseHolder(private val factory: (demo: Boolean) -> StrideDatabase, initialDemo: Boolean = false) {
    private val state = MutableStateFlow(factory(initialDemo))
    private var demo = initialDemo

    val current: StateFlow<StrideDatabase> = state.asStateFlow()

    val isDemo: Boolean get() = demo

    val db: StrideDatabase get() = state.value

    @Synchronized
    fun switchTo(demoMode: Boolean) {
        if (demoMode == demo) return
        val old = state.value
        demo = demoMode
        state.value = factory(demoMode)
        old.close()
    }

    companion object {
        fun forContext(context: Context, initialDemo: Boolean): DatabaseHolder = DatabaseHolder(
            factory = { demo ->
                Room.databaseBuilder(context, StrideDatabase::class.java, if (demo) StrideDatabase.DEMO_NAME else StrideDatabase.NAME)
                    .build()
            },
            initialDemo = initialDemo
        )
    }
}
