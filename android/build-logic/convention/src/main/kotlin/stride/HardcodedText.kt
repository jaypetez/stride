package stride

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a user-facing string is hard-coded in Kotlin UI code
 * instead of coming from `res/values/strings.xml` (AUD-034). Android Lint's
 * `HardcodedText` only sees XML layouts, so Compose needs this guard.
 *
 * It flags string literals containing a letter that are passed straight to
 * text-bearing parameters and composables (`Text("…")`, `title = "…"`,
 * `contentDescription = "…"`, `StrideButton("…")`, `"Label" to value`, …).
 * The ktlint-wrapped form, where the call or parameter ends one line and the
 * literal opens the next (`Text(` then `"…",`), is flagged too, on the
 * literal's line. A deliberate exception (a proper noun, a format token) takes
 * a trailing `// i18n-ignore` comment on the same line (either line, when wrapped).
 */
@CacheableTask
abstract class CheckHardcodedTextTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val findings = sources.files.filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.flatMap { file ->
            findingsIn(file.readLines()).map { (lineNumber, literal) ->
                "${file.path}:$lineNumber: hard-coded UI text $literal — move it to strings.xml"
            }
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(findings.joinToString("\n"))
        }
        if (findings.isNotEmpty()) {
            throw GradleException(
                "Found ${findings.size} hard-coded user-facing string(s) (AUD-034):\n" + findings.joinToString("\n")
            )
        }
    }

    companion object {
        private const val TEXT_PARAMS =
            "text|title|subtitle|label|message|contentDescription|actionLabel|onClickLabel|retryLabel|caption|placeholder|description"
        private const val TEXT_CALLS =
            "Text|StrideButton|StrideOutlinedButton|StrideTextButton|SectionHeader|StepTitle|StatTile|LabelledValue|EmptyState|ErrorState"

        /** Each pattern ends at the opening quote of the literal it is about. */
        private val PATTERNS = listOf(
            Regex("\\b($TEXT_CALLS)\\(\\s*\""),
            Regex("\\b($TEXT_PARAMS)\\s*=\\s*\""),
            Regex("\\bTriple\\(\\s*\""),
            Regex("(?<![\\w)])\"(?=[^\"]*\"\\s+to\\s)")
        )

        /** A line that ends right where a text literal would open: `Text(`, `title =`, `Triple(`. */
        private val OPENER_AT_END = Regex("(\\b($TEXT_CALLS)\\(|\\b($TEXT_PARAMS)\\s*=|\\bTriple\\()\\s*$")

        /**
         * Every hard-coded literal in [lines] as (1-based line number, literal). A literal wrapped onto
         * the line after its call or parameter is joined back to that line before matching.
         */
        fun findingsIn(lines: List<String>): List<Pair<Int, String>> = lines.indices.flatMap { index ->
            val line = lines[index]
            val own = hardcodedTextIn(line)?.let { index + 1 to it }
            val next = lines.getOrNull(index + 1)
            val wrapped = if (next != null && next.trimStart().startsWith("\"") && OPENER_AT_END.containsMatchIn(line) &&
                !next.contains("i18n-ignore")
            ) {
                hardcodedTextIn(line.trimEnd() + next.trimStart())?.let { index + 2 to it }
            } else {
                null
            }
            listOfNotNull(own, wrapped).distinct()
        }

        /** The offending literal in [line], or null when the line is clean. */
        fun hardcodedTextIn(line: String): String? {
            if (line.contains("i18n-ignore")) return null
            val code = line.trimStart()
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || code.startsWith("import ")) return null
            for (pattern in PATTERNS) {
                for (match in pattern.findAll(line)) {
                    val quote = match.range.last
                    val literal = literalAt(line, quote) ?: continue
                    if (staticText(literal).any(Char::isLetter)) return "\"$literal\""
                }
            }
            return null
        }

        /** The body of the string literal opening at [quote], or null if it does not close on this line. */
        private fun literalAt(line: String, quote: Int): String? {
            var i = quote + 1
            var depth = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' -> i++
                    c == '$' && i + 1 < line.length && line[i + 1] == '{' -> {
                        depth++
                        i++
                    }
                    depth > 0 && c == '{' -> depth++
                    depth > 0 && c == '}' -> depth--
                    depth == 0 && c == '"' -> return line.substring(quote + 1, i)
                }
                i++
            }
            return null
        }

        /** The literal minus templates (`${…}`, `$name`) and escapes: the words a translator would see. */
        private fun staticText(literal: String): String {
            val out = StringBuilder()
            var i = 0
            var depth = 0
            while (i < literal.length) {
                val c = literal[i]
                when {
                    depth == 0 && c == '\\' -> i++
                    depth == 0 && c == '$' && i + 1 < literal.length && literal[i + 1] == '{' -> {
                        depth = 1
                        i++
                    }
                    depth == 0 && c == '$' && i + 1 < literal.length && literal[i + 1].isLetter() -> {
                        while (i + 1 < literal.length && (literal[i + 1].isLetterOrDigit() || literal[i + 1] == '_')) i++
                    }
                    depth > 0 && c == '{' -> depth++
                    depth > 0 && c == '}' -> depth--
                    depth == 0 -> out.append(c)
                }
                i++
            }
            return out.toString()
        }
    }
}

/** Registers `checkHardcodedText` for UI modules and wires it into `check` (AUD-034). */
internal fun Project.configureHardcodedTextCheck() {
    val ui = path.startsWith(":feature:") || path == ":core:ui" || path == ":app"
    if (!ui) return
    val task = tasks.register("checkHardcodedText", CheckHardcodedTextTask::class.java) {
        group = "verification"
        description = "Fails on user-facing strings hard-coded in Kotlin UI code (AUD-034)."
        // *Previews.kt hold @Preview sample data only; it never ships to users.
        sources.from(fileTree("src/main/kotlin") { exclude("**/*Previews.kt") })
        report.set(layout.buildDirectory.file("reports/hardcoded-text.txt"))
    }
    tasks.named("check").configure { dependsOn(task) }
}
