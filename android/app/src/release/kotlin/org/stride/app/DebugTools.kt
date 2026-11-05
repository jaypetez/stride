package org.stride.app

import android.app.Application

/** Release builds install no debug diagnostics (ANDF-013). */
object DebugTools {
    fun install(@Suppress("UNUSED_PARAMETER") app: Application) = Unit
}
