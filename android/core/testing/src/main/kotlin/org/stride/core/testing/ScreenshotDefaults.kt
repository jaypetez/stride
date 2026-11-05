package org.stride.core.testing

/**
 * Pinned Robolectric qualifiers so Roborazzi screenshots are identical on every
 * machine (ANDF-008, docs/testing/screenshots.md). The locale is pinned to
 * en-GB: dates follow the device locale (AUD-034), so an unpinned run would
 * pick up the JVM's default.
 */
object ScreenshotDefaults {
    /** A Pixel-sized phone at xxhdpi. */
    const val PHONE = "en-rGB-w411dp-h891dp-xxhdpi"

    /** A foldable / small tablet (medium width class). */
    const val MEDIUM = "en-rGB-w700dp-h891dp-xhdpi"

    /** A tablet in landscape (expanded width class). */
    const val EXPANDED = "en-rGB-w1100dp-h800dp-mdpi"

    const val SDK = 36
}
