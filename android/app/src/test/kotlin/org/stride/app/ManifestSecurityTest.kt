package org.stride.app

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.stride.core.testing.ScreenshotDefaults

/** MASVS L1 network & storage controls on the merged manifest and resources (SEC-003). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK])
class ManifestSecurityTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun cleartextTrafficIsNotPermitted() {
        val flags = app.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC, "usesCleartextTraffic must be off")
        val nsc = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(nsc.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(nsc.contains("cleartextTrafficPermitted=\"true\""))
    }

    @Test
    fun secretsAreExcludedFromBackupAndTransfer() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(rules.contains("<cloud-backup>"))
        assertTrue(rules.contains("datastore/stride_secrets.preferences_pb"))
        assertTrue(rules.contains("stride_tink_keyset.xml"))
    }

    @Test
    fun releaseIsNotDebuggable() {
        if (!BuildConfig.DEBUG) {
            assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        }
    }
}
