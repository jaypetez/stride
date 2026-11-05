package org.stride.app

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** Release hardening that a JVM test can check without building an APK (ANDF-012). */
class ReleaseConfigTest {
    private val buildFile = File("build.gradle.kts").readText()

    @Test
    fun releaseIsMinifiedShrunkAndNotDebuggable() {
        assertTrue(buildFile.contains("isMinifyEnabled = true"))
        assertTrue(buildFile.contains("isShrinkResources = true"))
        assertTrue(buildFile.contains("isDebuggable = false"))
    }

    @Test
    fun noHttpBodyLoggingAnywhereInTheApp() {
        val sources = File("..").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .map { it.readText() }
        assertFalse(sources.any { "HttpLoggingInterceptor" in it }, "no HTTP logging interceptor in production code")
    }

    @Test
    fun pullRequestCiBuildsTheMinifiedReleaseAndReleasesKeepTheMapping() {
        // The merged-manifest checks live in ManifestSecurityTest; this keeps CI honest (AUD-040).
        val prCi = File("../../.github/workflows/android.yml").readText()
        assertTrue(":app:assembleRelease" in prCi, "android.yml must build the minified release on PRs")
        val release = File("../../.github/workflows/release-android.yml").readText()
        assertTrue("dist/*-mapping.txt" in release, "the R8 mapping must be attached to each GitHub Release")
        assertTrue("mapping.txt" in File("../../scripts/android/release-build.sh").readText())
    }

    @Test
    fun versionComesFromVersionJson() {
        assertTrue(buildFile.contains("version.json"))
        assertTrue(File("../version.json").readText().contains("versionCode"))
    }
}
