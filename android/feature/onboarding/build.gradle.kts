plugins {
    alias(libs.plugins.stride.android.feature)
}

// First launch (MVP-013): welcome → profile → PAR-Q → disclaimer → connect Garmin or try demo.
dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.activity.compose)
}
