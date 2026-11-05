plugins {
    alias(libs.plugins.stride.android.library)
}

// Test fixtures for screens: the real demo pipeline on an in-memory database,
// plus a fake SyncController (MVP-014..018). testImplementation only.
dependencies {
    api(project(":core:data"))
    api(project(":core:testing"))
    implementation(libs.androidx.dataStore.preferences)
}
