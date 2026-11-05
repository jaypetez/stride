package org.stride.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StrideApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugTools.install(this)
    }
}
