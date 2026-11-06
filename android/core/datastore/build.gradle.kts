plugins {
    alias(libs.plugins.stride.android.library)
    alias(libs.plugins.stride.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig {
        // Boots HiltTestApplication for the on-device SecretStore test (AUD-039).
        testInstrumentationRunner = "org.stride.core.datastore.HiltInstrumentationRunner"
    }
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

    // Instrumented tests run on a real device/emulator with the real Android Keystore
    // (`./gradlew -p android :core:datastore:connectedDebugAndroidTest`).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    kspAndroidTest(libs.hilt.compiler)
}
