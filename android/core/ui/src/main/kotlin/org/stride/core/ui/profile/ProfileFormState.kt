package org.stride.core.ui.profile

import java.time.LocalDate
import org.stride.core.model.AthleteProfile
import org.stride.core.model.RaceGoal
import org.stride.core.model.Sex
import org.stride.core.model.UnitSystem

/** Race distances offered for the optional race goal. */
enum class RaceDistance(val label: String, val metres: Double) {
    FIVE_K("5K", 5000.0),
    TEN_K("10K", 10_000.0),
    HALF("Half marathon", 21_097.5),
    MARATHON("Marathon", 42_195.0);

    companion object {
        fun of(metres: Double): RaceDistance? = entries.firstOrNull { kotlin.math.abs(it.metres - metres) < 1.0 }
    }
}

enum class ProfileField {
    NAME,
    BIRTH_YEAR,
    MAX_HR,
    RESTING_HR,
    THRESHOLD_HR,
    RACE_DATE
}

/**
 * The editable profile (onboarding and Settings, MVP-013/021). Numbers stay as
 * the text the user typed until [validate] turns them into an [AthleteProfile].
 */
data class ProfileFormState(
    val name: String = "",
    val birthYear: String = "",
    val sex: Sex = Sex.UNSPECIFIED,
    val units: UnitSystem = UnitSystem.METRIC,
    val maxHr: String = "",
    val restingHr: String = "",
    val thresholdHr: String = "",
    val raceDistance: RaceDistance? = null,
    val raceDate: LocalDate? = null
) {
    companion object {
        fun from(profile: AthleteProfile): ProfileFormState = ProfileFormState(
            name = profile.name,
            birthYear = profile.birthYear?.toString().orEmpty(),
            sex = profile.sex,
            units = profile.unitSystem,
            maxHr = profile.maxHr?.toString().orEmpty(),
            restingHr = profile.restingHr?.toString().orEmpty(),
            thresholdHr = profile.thresholdHr?.toString().orEmpty(),
            raceDistance = profile.raceGoal?.let { RaceDistance.of(it.distanceM) },
            raceDate = profile.raceGoal?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        )
    }
}

/** Errors by field; [profile] is non-null only when there are none. */
data class ProfileValidation(val errors: Map<ProfileField, String>, val profile: AthleteProfile?) {
    val isValid: Boolean get() = errors.isEmpty()
}

const val MIN_AGE = 13
const val MAX_AGE = 100
val MAX_HR_RANGE = 120..230
val RESTING_HR_RANGE = 30..110
val THRESHOLD_HR_RANGE = 100..220
const val MAX_NAME_LENGTH = 40

/**
 * Validates the form against [today]. Fields the form does not edit
 * (threshold pace, race target time) are carried over from [base].
 */
fun ProfileFormState.validate(today: LocalDate, base: AthleteProfile = AthleteProfile()): ProfileValidation {
    val errors = linkedMapOf<ProfileField, String>()
    val trimmedName = name.trim()
    nameError(trimmedName)?.let { errors[ProfileField.NAME] = it }
    val year = birthYear.trim().toIntOrNull()
    birthYearError(today)?.let { errors[ProfileField.BIRTH_YEAR] = it }

    // optionalInt returns null for blank or invalid input, so the order checks only see valid values.
    val max = optionalInt(maxHr, MAX_HR_RANGE, ProfileField.MAX_HR, errors)
    val resting = optionalInt(restingHr, RESTING_HR_RANGE, ProfileField.RESTING_HR, errors)
    val threshold = optionalInt(thresholdHr, THRESHOLD_HR_RANGE, ProfileField.THRESHOLD_HR, errors)
    if (max != null) {
        if (threshold != null && threshold >= max) errors[ProfileField.THRESHOLD_HR] = "Threshold HR must be below max HR"
        if (resting != null && resting >= max) errors[ProfileField.RESTING_HR] = "Resting HR must be below max HR"
    }
    raceDateError(today)?.let { errors[ProfileField.RACE_DATE] = it }

    if (errors.isNotEmpty()) return ProfileValidation(errors, null)
    val goal = if (raceDistance != null && raceDate != null) {
        val keepTarget = base.raceGoal?.takeIf { RaceDistance.of(it.distanceM) == raceDistance }?.targetTimeSec
        RaceGoal(distanceM = raceDistance.metres, date = raceDate.toString(), targetTimeSec = keepTarget)
    } else {
        null
    }
    val profile = base.copy(
        name = trimmedName,
        birthYear = year,
        sex = sex,
        unitSystem = units,
        maxHr = max,
        restingHr = resting,
        thresholdHr = threshold,
        raceGoal = goal
    )
    return ProfileValidation(emptyMap(), profile)
}

private fun nameError(trimmed: String): String? = when {
    trimmed.isEmpty() -> "Enter your name"
    trimmed.length > MAX_NAME_LENGTH -> "Use at most $MAX_NAME_LENGTH characters"
    else -> null
}

private fun ProfileFormState.birthYearError(today: LocalDate): String? {
    val years = (today.year - MAX_AGE)..(today.year - MIN_AGE)
    val year = birthYear.trim().toIntOrNull()
    return when {
        birthYear.isBlank() -> "Enter your birth year"
        year == null || year !in years -> "Enter a year between ${years.first} and ${years.last}"
        else -> null
    }
}

private fun ProfileFormState.raceDateError(today: LocalDate): String? = when {
    raceDistance == null -> null
    raceDate == null -> "Pick the race date"
    raceDate.isBefore(today) -> "The race date must be today or later"
    else -> null
}

private fun optionalInt(text: String, range: IntRange, field: ProfileField, errors: MutableMap<ProfileField, String>): Int? {
    if (text.isBlank()) return null
    val value = text.trim().toIntOrNull()
    if (value == null || value !in range) {
        errors[field] = "Enter ${range.first}–${range.last} bpm, or leave blank"
        return null
    }
    return value
}
