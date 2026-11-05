import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "org.stride.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
    compileOnly(libs.kover.gradlePlugin)
    compileOnly(libs.roborazzi.gradlePlugin)

    // Unit tests for the build's own guards, e.g. checkHardcodedText (AUD-034).
    testImplementation(libs.junit4)
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "stride.android.application"
            implementationClass = "stride.AndroidApplicationConventionPlugin"
        }
        register("androidApplicationCompose") {
            id = "stride.android.application.compose"
            implementationClass = "stride.AndroidApplicationComposeConventionPlugin"
        }
        register("androidLibrary") {
            id = "stride.android.library"
            implementationClass = "stride.AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "stride.android.library.compose"
            implementationClass = "stride.AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "stride.android.feature"
            implementationClass = "stride.AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "stride.android.hilt"
            implementationClass = "stride.HiltConventionPlugin"
        }
        register("androidRoborazzi") {
            id = "stride.android.roborazzi"
            implementationClass = "stride.RoborazziConventionPlugin"
        }
        register("androidRoom") {
            id = "stride.android.room"
            implementationClass = "stride.RoomConventionPlugin"
        }
        register("jvmLibrary") {
            id = "stride.jvm.library"
            implementationClass = "stride.JvmLibraryConventionPlugin"
        }
    }
}
