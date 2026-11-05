plugins {
    alias(libs.plugins.stride.jvm.library)
    alias(libs.plugins.stride.android.hilt)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
