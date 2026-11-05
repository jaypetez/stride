package org.stride.core.science.coach

import kotlinx.serialization.Serializable
import org.stride.core.science.model.AcwrFlag
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.RedFlag
import org.stride.core.science.model.RedFlagSeverity

/* Deterministic safety layer. Mirrors packages/core/src/coach/safety.ts; every string is contract text. */

/** Standard disclaimer attached to all coaching output. Mirrors safety.ts#DISCLAIMER. */
const val DISCLAIMER: String =
    "Stride is for informational and educational purposes only and is not a substitute " +
        "for professional medical advice. Consult a qualified healthcare provider before " +
        "beginning any fitness program, especially if you have any pre-existing conditions."

/** PAR-Q-style screening questions shown at onboarding. Mirrors safety.ts#PARQ_QUESTIONS. */
val PARQ_QUESTIONS: List<String> = listOf(
    "Has a doctor ever said you have a heart condition and should only do physical activity recommended by a doctor?",
    "Do you feel pain in your chest when you do physical activity?",
    "In the past month, have you had chest pain when you were not doing physical activity?",
    "Do you lose your balance because of dizziness, or do you ever lose consciousness?",
    "Do you have a bone or joint problem that could be made worse by a change in your physical activity?",
    "Is your doctor currently prescribing drugs for your blood pressure or a heart condition?",
    "Do you know of any other reason you should not do physical activity?"
)

/** Label recorded when the answer list is incomplete (never cleared). Mirrors safety.ts#INCOMPLETE_SCREENING_FLAG. */
const val INCOMPLETE_SCREENING_FLAG = "incomplete screening"

/** Short labels aligned 1:1 with [PARQ_QUESTIONS], stored as AthleteProfile.healthFlags. */
private val PARQ_FLAG_LABELS = listOf(
    "heart condition",
    "chest pain on exertion",
    "chest pain at rest",
    "dizziness or fainting",
    "bone or joint problem",
    "blood-pressure/heart medication",
    "other medical reason"
)

/** JS `\b` (ASCII word characters) so behaviour does not depend on JVM Unicode flags. */
private const val WB_START = "(?<![A-Za-z0-9_])"
private const val WB_END = "(?![A-Za-z0-9_])"

private fun keyword(body: String, endBoundary: Boolean = true) =
    Regex(WB_START + body + if (endBoundary) WB_END else "", RegexOption.IGNORE_CASE)

private val STOP_KEYWORDS = listOf(
    keyword("chest pain"),
    keyword("chest tightness"),
    keyword("chest (?:feels|felt|is|was|got|getting|going) (?:tight|heavy)"),
    keyword("tight(?:ness)? (?:in|across) (?:my|the) chest"),
    keyword("short(ness)? of breath"),
    keyword("(?:can['’]?t|cannot|can not|couldn['’]?t|could not) (?:breathe|catch my breath)"),
    keyword("(?:trouble|difficulty|struggling|struggled) (?:to )?breath(?:e|ing)"),
    keyword("dizz(y|iness)"),
    keyword("light[- ]?headed(?:ness)?"),
    keyword("faint(ed|ing)?"),
    keyword("pass(ed)? out"),
    keyword("black(ed)? out"),
    keyword("palpitations?"),
    keyword("heart (?:was |is |kept )?(?:racing|pounding|fluttering|skipping)"),
    keyword("irregular heart ?beat"),
    keyword("severe pain")
)

private val WARN_KEYWORDS = listOf(
    keyword("injur(y|ed)"),
    keyword("pain"),
    keyword("sore(ness)?"),
    keyword("sick"),
    keyword("fever")
)

/** Input to [detectRedFlags]. Mirrors safety.ts#RedFlagInput. */
@Serializable
data class RedFlagInput(
    /** Free text such as an athlete note. */
    val text: String? = null,
    val profile: AthleteProfile? = null,
    /** Latest TSB (form). */
    val tsb: Double? = null,
    val acwrFlag: AcwrFlag? = null
)

/**
 * Red flags, in order: one keyword STOP (chest pain, dizziness, fainting, ...) or else one keyword WARNING
 * (pain, injury, sore, sick, fever); health flags on file; no medical clearance (INFO); ACWR very high;
 * TSB < -30. Mirrors packages/core/src/coach/safety.ts#detectRedFlags.
 */
fun detectRedFlags(input: RedFlagInput): List<RedFlag> {
    val flags = mutableListOf<RedFlag>()
    val text = input.text ?: ""
    if (STOP_KEYWORDS.any { it.containsMatchIn(text) }) {
        flags += RedFlag(
            RedFlagSeverity.STOP,
            "Symptoms you described (e.g. chest pain, dizziness, fainting) can be serious. " +
                "Stop exercising and seek advice from a medical professional before continuing.",
            "keyword"
        )
    } else if (WARN_KEYWORDS.any { it.containsMatchIn(text) }) {
        flags += RedFlag(
            RedFlagSeverity.WARNING,
            "You mentioned pain, injury, or illness — prioritize recovery and do not push through it.",
            "keyword"
        )
    }
    val profile = input.profile
    if (profile != null && profile.healthFlags.isNotEmpty()) {
        flags += RedFlag(
            RedFlagSeverity.WARNING,
            "Health screening flags on file: ${profile.healthFlags.joinToString(", ")}. Get medical clearance before hard training.",
            "health"
        )
    }
    if (profile != null && !profile.medicalClearance) {
        flags += RedFlag(
            RedFlagSeverity.INFO,
            "No medical clearance recorded. Complete the readiness screening before starting a plan.",
            "health"
        )
    }
    if (input.acwrFlag == AcwrFlag.VERY_HIGH) {
        flags += RedFlag(
            RedFlagSeverity.WARNING,
            "Your acute:chronic workload ratio is very high (>1.5) — a spike associated with elevated injury risk. Ease off.",
            "load"
        )
    }
    val tsb = input.tsb
    if (tsb != null && tsb < -30) {
        flags += RedFlag(
            RedFlagSeverity.WARNING,
            "Your form (TSB) is deeply negative — a sign of accumulated fatigue. Consider a recovery week.",
            "load"
        )
    }
    return flags
}

/** True when any flag requires halting coaching. Mirrors safety.ts#shouldHalt. */
fun shouldHalt(flags: List<RedFlag>): Boolean = flags.any { it.severity == RedFlagSeverity.STOP }

/** Result of the PAR-Q screening. Mirrors safety.ts#ReadinessScreening. */
@Serializable
data class ReadinessScreening(val cleared: Boolean, val flaggedQuestions: List<String>, val healthFlags: List<String>)

/**
 * PAR-Q screening: answers[i] is the yes (true) / no answer to PARQ_QUESTIONS[i]. Cleared only when exactly one
 * answer per question was given and every answer is no; a wrong-length list adds INCOMPLETE_SCREENING_FLAG. Mirrors packages/core/src/coach/safety.ts#screenReadiness.
 */
fun screenReadiness(answers: List<Boolean>): ReadinessScreening {
    val yes = PARQ_QUESTIONS.indices.filter { answers.getOrElse(it) { false } }
    val complete = answers.size == PARQ_QUESTIONS.size
    return ReadinessScreening(
        cleared = complete && yes.isEmpty(),
        flaggedQuestions = yes.map { PARQ_QUESTIONS[it] },
        healthFlags = yes.map { PARQ_FLAG_LABELS[it] } + if (complete) emptyList() else listOf(INCOMPLETE_SCREENING_FLAG)
    )
}

private val CLASSIFIER_MESSAGES = mapOf(
    "injury" to
        "Your note suggests a possible injury — prioritize recovery and consider a professional assessment before hard training.",
    "illness" to "Your note suggests illness — rest and let symptoms fully resolve before resuming intensity.",
    "pain" to "Your note mentions pain — do not push through it; ease back and reassess.",
    "overreaching" to "Your note suggests you may be overreaching — an easier stretch now protects the gains you have made.",
    "fatigue" to "Your note suggests unusual fatigue — extra easy volume or a rest day may be warranted.",
    "sleep" to "Your note suggests poor sleep — recovery is compromised, so keep intensity conservative.",
    "stress" to "Your note suggests elevated life stress — treat it as training load and keep sessions easy."
)

/** Classifier labels (trimmed, lower-cased, de-duplicated) mapped to WARNING flags; unknown labels are ignored. Mirrors safety.ts#classifierWarnings. */
fun classifierWarnings(labels: List<String>): List<RedFlag> {
    val seen = mutableSetOf<String>()
    return labels.mapNotNull { raw ->
        val label = raw.trim().lowercase()
        val message = CLASSIFIER_MESSAGES[label]
        if (message != null && seen.add(label)) RedFlag(RedFlagSeverity.WARNING, message, "classifier") else null
    }
}
