plugins {
    alias(libs.plugins.stride.android.feature)
}

// Guided Garmin Personal API key setup, validation, expiry and disconnect (MVP-005).
dependencies {
    implementation(project(":core:data"))
}
