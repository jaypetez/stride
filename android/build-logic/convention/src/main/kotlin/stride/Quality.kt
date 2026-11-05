package stride

import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Static analysis applied to every module (ANDF-007): Detekt (+ Compose rules),
 * ktlint via Spotless, and Kover coverage instrumentation. `./gradlew -p android check`
 * runs all of them.
 */
internal fun Project.configureQuality() {
    pluginManager.apply(libs.pluginId("detekt"))
    pluginManager.apply(libs.pluginId("spotless"))
    pluginManager.apply(libs.pluginId("kover"))

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel = true
        source.setFrom("src/main/kotlin", "src/main/java", "src/test/kotlin", "src/test/java")
    }
    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            sarif.required.set(true)
            xml.required.set(false)
            txt.required.set(false)
            md.required.set(false)
        }
    }
    dependencies {
        add("detektPlugins", libs.lib("compose-rules-detekt"))
    }

    val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion
    extensions.configure<SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**")
            ktlint(ktlintVersion)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion)
        }
    }
    tasks.named("check").configure { dependsOn("spotlessCheck") }
    configureHardcodedTextCheck()

    extensions.configure<KoverProjectExtension> {
        currentProject {
            sources {
                excludedSourceSets.addAll("debug", "release")
            }
        }
    }
}
