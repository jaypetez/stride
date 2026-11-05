package org.stride.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.stride.core.common.ApplicationScope
import org.stride.core.data.demo.DemoMode
import org.stride.core.data.sync.SyncManager
import org.stride.core.datastore.SecretStore

@HiltAndroidApp
class StrideApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var demoMode: DemoMode

    @Inject lateinit var syncManager: SyncManager

    @Inject lateinit var secretStore: SecretStore

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // StrictMode first (AUD-036), so anything Hilt does on the main thread while injecting
        // this class in super.onCreate() is reported. Crypto is lazy (LazySecretCipher) and the
        // stores below are only read inside appScope, off the main thread.
        DebugTools.install(this)
        super.onCreate()
        appScope.launch {
            // Re-attach the demo database if the app was left in demo mode, and keep
            // the daily Garmin sync scheduled while a key is stored (MVP-008).
            demoMode.restore()
            if (secretStore.apiKey() != null) syncManager.schedulePeriodic()
        }
    }
}
