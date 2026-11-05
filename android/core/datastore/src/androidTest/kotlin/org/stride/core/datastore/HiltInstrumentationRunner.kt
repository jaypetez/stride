package org.stride.core.datastore

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Boots [HiltTestApplication] for this module's instrumented tests. Same as
 * `core:testing`'s HiltTestRunner, kept local so the test APK does not pull in
 * core:testing's Robolectric and Roborazzi dependencies.
 */
class HiltInstrumentationRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
