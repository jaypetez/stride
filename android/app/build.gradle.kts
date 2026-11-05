import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.stride.android.application.compose)
    alias(libs.plugins.stride.android.hilt)
    alias(libs.plugins.stride.android.roborazzi)
    alias(libs.plugins.kotlin.serialization)
}

// versionName/versionCode come from android/version.json (REL-003, scripts/android-version.mjs).
@Suppress("UNCHECKED_CAST")
val appVersion = JsonSlurper().parse(rootProject.file("version.json")) as Map<String, Any>

// Release signing (REL-002): CI decodes the keystore from the `release` environment
// into a temp file and passes its path + credentials as env vars. Without them the
// release build falls back to the debug key (local builds, PR dry runs).
val releaseKeystore: String? = providers.environmentVariable("STRIDE_KEYSTORE_PATH").orNull

android {
    namespace = "org.stride.app"

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("STRIDE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("STRIDE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("STRIDE_KEY_PASSWORD").get()
            }
        }
    }

    defaultConfig {
        applicationId = "org.stride.app"
        versionCode = (appVersion["versionCode"] as Number).toInt()
        versionName = appVersion["versionName"] as String
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
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:connect"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:coach"))
    implementation(project(":feature:today"))
    implementation(project(":feature:activities"))
    implementation(project(":feature:fitness"))
    implementation(project(":feature:plan"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
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
    testImplementation(project(":core:data-testing"))
}

// ManifestSecurityTest asserts on the merged *release* manifest (AUD-040), not just on
// the debug variant Robolectric runs: every unit-test task gets its path and depends on it.
abstract class ReleaseManifestArgument : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    override fun asArguments(): List<String> = listOf("-Dstride.releaseManifest=${manifest.get().asFile.absolutePath}")
}

val releaseManifest = objects.newInstance<ReleaseManifestArgument>()
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        releaseManifest.manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
}
tasks.withType<Test>().configureEach { jvmArgumentProviders.add(releaseManifest) }

tasks.register("printVersion") {
    val name = appVersion["versionName"]
    val code = appVersion["versionCode"]
    doLast { println("versionName=$name versionCode=$code") }
}
