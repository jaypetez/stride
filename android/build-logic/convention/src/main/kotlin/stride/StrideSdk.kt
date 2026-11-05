package stride

import org.gradle.api.JavaVersion

/** SDK levels are set once here (ADR 0007), never per module. */
object StrideSdk {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 36
    const val MIN_SDK = 28

    /** Robolectric runs against this SDK so screenshots are reproducible across machines. */
    const val ROBOLECTRIC_SDK = 36

    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
}
