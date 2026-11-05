package org.stride.core.testing

/**
 * Pinned Robolectric qualifiers so Roborazzi screenshots are identical on every
 * machine (ANDF-008, docs/testing/screenshots.md).
 */
object ScreenshotDefaults {
    /** A Pixel-sized phone at xxhdpi. */
    const val PHONE = "w411dp-h891dp-xxhdpi"

    /** A foldable / small tablet (medium width class). */
    const val MEDIUM = "w700dp-h891dp-xhdpi"

    /** A tablet in landscape (expanded width class). */
    const val EXPANDED = "w1100dp-h800dp-mdpi"

    const val SDK = 36
}
