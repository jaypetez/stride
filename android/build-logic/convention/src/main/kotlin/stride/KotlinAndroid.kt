package stride

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

internal fun Project.configureApplication() {
    extensions.configure<ApplicationExtension> {
        compileSdk = StrideSdk.COMPILE_SDK
        defaultConfig {
            minSdk = StrideSdk.MIN_SDK
            targetSdk = StrideSdk.TARGET_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            vectorDrawables.useSupportLibrary = true
        }
        compileOptions {
            sourceCompatibility = StrideSdk.JAVA_VERSION
            targetCompatibility = StrideSdk.JAVA_VERSION
        }
        testOptions {
            unitTests.isIncludeAndroidResources = true
            unitTests.isReturnDefaultValues = true
        }
        lint {
            configureStrideLint(this, project)
        }
        packaging {
            resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
        }
    }
    configureKotlinCompile()
    configureUnitTests()
    addCommonTestDependencies()
}

internal fun Project.configureLibrary() {
    extensions.configure<LibraryExtension> {
        compileSdk = StrideSdk.COMPILE_SDK
        defaultConfig {
            minSdk = StrideSdk.MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = StrideSdk.JAVA_VERSION
            targetCompatibility = StrideSdk.JAVA_VERSION
        }
        testOptions {
            targetSdk = StrideSdk.TARGET_SDK
            unitTests.isIncludeAndroidResources = true
            unitTests.isReturnDefaultValues = true
        }
        lint {
            targetSdk = StrideSdk.TARGET_SDK
            configureStrideLint(this, project)
        }
        // Namespaces are derived from the module path: :core:designsystem -> org.stride.core.designsystem
        namespace = "org.stride." + path.removePrefix(":").replace(':', '.').replace('-', '_')
    }
    configureKotlinCompile()
    configureUnitTests()
    addCommonTestDependencies()
}

private fun configureStrideLint(lint: com.android.build.api.dsl.Lint, project: Project) {
    lint.warningsAsErrors = true
    lint.abortOnError = true
    lint.checkDependencies = false
    lint.xmlReport = true
    lint.sarifReport = true
    // Version-freshness checks are Dependabot's job (GOV-002), not a build breaker.
    lint.disable += setOf(
        "GradleDependency",
        "NewerVersionAvailable",
        "AndroidGradlePluginVersion",
        "OldTargetApi",
        "ObsoleteLintCustomCheck",
    )
}

internal fun Project.configureKotlinCompile() {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        val options = compilerOptions
        if (options is KotlinJvmCompilerOptions) {
            options.jvmTarget.set(JvmTarget.JVM_17)
        }
        options.allWarningsAsErrors.set(false)
        options.freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xconsistent-data-class-copy-visibility",
        )
    }
}

internal fun Project.configureUnitTests() {
    tasks.withType<Test>().configureEach {
        // Robolectric: pin the SDK and use native graphics so Roborazzi output is deterministic.
        systemProperty("robolectric.graphicsMode", "NATIVE")
        systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        systemProperty("robolectric.enabledSdks", StrideSdk.ROBOLECTRIC_SDK.toString())
        maxHeapSize = "1536m"
    }
}

private fun Project.addCommonTestDependencies() {
    dependencies {
        add("testImplementation", libs.lib("junit4"))
        add("testImplementation", libs.lib("kotlin-test"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        add("testImplementation", libs.lib("turbine"))
        add("testImplementation", libs.lib("robolectric"))
        add("testImplementation", libs.lib("androidx-test-core"))
        add("testImplementation", libs.lib("androidx-test-ext-junit"))
    }
}
