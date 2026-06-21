import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessTask
import com.diffplug.spotless.FormatterFunc
import com.diffplug.spotless.FormatterStep
import keiyoushi.gradle.extensions.alias
import keiyoushi.gradle.extensions.libs
import keiyoushi.gradle.extensions.plugins
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.registerIfAbsent
import java.io.Serializable

/**
 * No-op build service used purely as a concurrency constraint. spotless 8.x lets a
 * project's per-format tasks (spotlessKotlin/spotlessJava/spotlessXml/…) run concurrently
 * and they race on the shared `build/spotless-clean` directory, failing intermittently
 * with `Could not read path .../build/spotless-clean/spotless<Format>`. Registering this
 * service with maxParallelUsages = 1 and having every spotless task use it forces them to
 * run one at a time, eliminating the race.
 */
abstract class SpotlessExclusiveLock : BuildService<BuildServiceParameters.None>

@Suppress("UNUSED")
class PluginSpotless : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.spotless)
        }

        // Configuration should be synced with [/gradle/build-logic/build.gradle.kts]
        val ktlintVersion = libs.ktlint.bom.get().version
        spotless {
            kotlin {
                target("src/**/*.kt", "*.kts")
                targetExclude("**/build/**")
                ktlint(ktlintVersion)
                    .editorConfigOverride(
                        mapOf(
                            "max_line_length" to 2147483647,
                        ),
                    )
                trimTrailingWhitespace()
                endWithNewline()
                addStep(RandomUACheck.create())
            }

            java {
                target("src/**/*.java")
                targetExclude("**/build/**")
                googleJavaFormat()
                removeUnusedImports()
                trimTrailingWhitespace()
                endWithNewline()
            }

            format("gradle") {
                target("*.gradle")
                targetExclude("**/build/**")
                trimTrailingWhitespace()
                endWithNewline()
            }

            format("xml") {
                target("src/**/*.xml")
                targetExclude("**/build/**")
                trimTrailingWhitespace()
                endWithNewline()
            }
        }

        // Serialize spotless task execution to avoid the spotless-clean race (see
        // SpotlessExclusiveLock). registerIfAbsent is idempotent across all projects.
        val spotlessLock = gradle.sharedServices.registerIfAbsent(
            "spotlessExclusiveLock",
            SpotlessExclusiveLock::class.java,
        ) {
            maxParallelUsages.set(1)
        }
        tasks.withType(SpotlessTask::class.java).configureEach {
            usesService(spotlessLock)
        }
    }
}

private object RandomUACheck {
    fun create(): FormatterStep = FormatterStep.create(
        "randomua-requires-getMangaUrl",
        State(),
        State::toFormatter,
    )

    private class State : Serializable {
        fun toFormatter() = FormatterFunc { content ->
            if ("package keiyoushi.lib.randomua" !in content &&
                "keiyoushi.lib.randomua" in content &&
                "override fun getMangaUrl(" !in content
            ) {
                throw AssertionError(
                    "usage of :lib:randomua requires override of getMangaUrl()",
                )
            }
            content
        }
    }
}

private fun Project.spotless(block: SpotlessExtension.() -> Unit) {
    extensions.configure(block)
}
