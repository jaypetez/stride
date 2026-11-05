package org.stride.app

import android.app.Application
import android.os.StrictMode

/**
 * Debug-only diagnostics (ANDF-013): StrictMode catches disk/network on the main
 * thread and leaked closeables; LeakCanary (debugImplementation) installs itself.
 */
object DebugTools {
    fun install(@Suppress("UNUSED_PARAMETER") app: Application) {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
    }
}
