package org.stride.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.stride.core.testing.ScreenshotDefaults

/** Proves the Hilt graph builds and MainActivity receives the feature entries (ANDF-006). */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK], application = HiltTestApplication::class)
class HiltGraphTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Test
    fun mainActivityIsInjectedWithEntryProviders() {
        hilt.inject()
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            assertTrue(controller.get().entryProviderInstallers.isNotEmpty())
        }
    }
}
