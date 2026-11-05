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
        super.onCreate()
        DebugTools.install(this)
        appScope.launch {
            // Re-attach the demo database if the app was left in demo mode, and keep
            // the daily Garmin sync scheduled while a key is stored (MVP-008).
            demoMode.restore()
            if (secretStore.apiKey() != null) syncManager.schedulePeriodic()
        }
    }
}
