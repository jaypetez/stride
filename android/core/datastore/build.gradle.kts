plugins {
    alias(libs.plugins.stride.android.library)
    alias(libs.plugins.stride.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Encrypted secrets (Garmin key, cached token, Anthropic key) and user
// preferences on DataStore. Secrets use Tink AEAD with a Keystore-bound key (MVP-004).
dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:garmin"))
    api(libs.androidx.dataStore.preferences)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
}
