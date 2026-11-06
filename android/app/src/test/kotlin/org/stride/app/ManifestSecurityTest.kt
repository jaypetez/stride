package org.stride.app

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.stride.core.testing.ScreenshotDefaults
import org.w3c.dom.Element

/**
 * MASVS L1 network & storage controls (SEC-003) on the merged manifest Robolectric
 * runs, on the source resources, and on the merged *release* manifest that ships
 * in the APK (AUD-040). Backup settings follow ADR 0011 (AUD-041).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ScreenshotDefaults.SDK])
class ManifestSecurityTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun xml(file: File): Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)
        .documentElement

    /** The `<application>` element of the merged release manifest (path passed in by app/build.gradle.kts). */
    private val releaseApplication: Element by lazy {
        val path = assertNotNull(System.getProperty("stride.releaseManifest"), "stride.releaseManifest is not set by the build")
        val manifest = File(path)
        assertTrue(manifest.isFile, "merged release manifest not found at $path")
        xml(manifest).getElementsByTagName("application").item(0) as Element
    }

    private fun Element.androidAttr(name: String): String? = getAttributeNS(ANDROID_NS, name).ifEmpty { null }

    private fun deviceTransferExcludes(): List<Pair<String, String>> {
        val rules = xml(File("src/main/res/xml/data_extraction_rules.xml"))
        val transfer = rules.getElementsByTagName("device-transfer").item(0) as Element
        val excludes = transfer.getElementsByTagName("exclude")
        return (0 until excludes.length).map { i ->
            val e = excludes.item(i) as Element
            e.getAttribute("domain") to e.getAttribute("path")
        }
    }

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
        val transfer = deviceTransferExcludes()
        assertTrue("file" to "datastore/stride_secrets.preferences_pb" in transfer)
        assertTrue("sharedpref" to "stride_tink_keyset.xml" in transfer)
    }

    @Test
    fun backupIsOffAndTheDatabaseNeverLeavesTheDevice() {
        // ADR 0011: the Room database is not encrypted, so it is never backed up or transferred.
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP, "allowBackup must be false")
        assertTrue("database" to "." in deviceTransferExcludes(), "device-to-device transfer must exclude the database")
        val cloud = File(
            "src/main/res/xml/data_extraction_rules.xml"
        ).readText().substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        listOf("root", "file", "database", "sharedpref", "external").forEach {
            assertTrue(cloud.contains("<exclude domain=\"$it\" path=\".\" />"), "cloud backup must exclude $it")
        }
    }

    @Test
    fun releaseIsNotDebuggable() {
        // AGP writes android:debuggable="true" into the merged manifest of debuggable variants only.
        assertNull(releaseApplication.androidAttr("debuggable")?.takeIf { it != "false" }, "release must not be debuggable")
        assertNull(releaseApplication.androidAttr("testOnly")?.takeIf { it != "false" }, "release must not be testOnly")
    }

    @Test
    fun releaseManifestKeepsTheStorageAndNetworkControls() {
        assertEquals("false", releaseApplication.androidAttr("allowBackup"))
        assertEquals("@xml/data_extraction_rules", releaseApplication.androidAttr("dataExtractionRules"))
        assertEquals("@xml/backup_rules", releaseApplication.androidAttr("fullBackupContent"))
        assertEquals("@xml/network_security_config", releaseApplication.androidAttr("networkSecurityConfig"))
        assertNull(releaseApplication.androidAttr("usesCleartextTraffic")?.takeIf { it != "false" })
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
