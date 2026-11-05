plugins {
    alias(libs.plugins.stride.android.application.compose)
    alias(libs.plugins.stride.android.hilt)
    alias(libs.plugins.stride.android.roborazzi)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.stride.app"

    defaultConfig {
        applicationId = "org.stride.app"
        versionCode = 1
        versionName = "0.1.0-mvp"
        testInstrumentationRunner = "org.stride.core.testing.HiltTestRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-signed until REL-002 provides the release keystore; CI overrides via signing env.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigationSuite)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.ktx)
    implementation(libs.androidx.hilt.work)

    debugImplementation(libs.leakcanary.android)

    testImplementation(project(":core:testing"))
}
