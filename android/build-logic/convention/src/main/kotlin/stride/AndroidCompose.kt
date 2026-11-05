package stride

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

private fun Project.configureCompose() {
    pluginManager.apply(libs.pluginId("compose"))
    dependencies {
        val bom = libs.lib("androidx-compose-bom")
        add("implementation", platform(bom))
        add("testImplementation", platform(bom))
        add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
        add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
        add("testImplementation", libs.lib("androidx-compose-ui-test"))
        add("debugImplementation", libs.lib("androidx-compose-ui-testManifest"))
    }
    extensions.configure<ComposeCompilerGradlePluginExtension> {
        // Stability config keeps domain models (core:model) skippable.
        stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_compiler_config.conf"))
    }
}

class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("stride.android.application")
        extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
        configureCompose()
    }
}

class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("stride.android.library")
        extensions.configure<LibraryExtension> { buildFeatures.compose = true }
        configureCompose()
    }
}
