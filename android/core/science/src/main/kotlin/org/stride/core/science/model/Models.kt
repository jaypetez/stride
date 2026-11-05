package org.stride.core.science.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Data types mirroring packages/schemas (activity.ts, athlete.ts, metrics.ts, coach.ts).
 * Units follow the TS schema: metres, seconds, m/s, bpm. Dates are ISO strings; day keys are
 * "YYYY-MM-DD". A TS `undefined` is a Kotlin `null`.
 */

/** Index-aligned time series. Mirrors packages/schemas/src/activity.ts#ActivityStreams. */
@Serializable
data class ActivityStreams(
    /** Seconds from start. */
    val time: List<Double>? = null,
    /** Cumulative distance (m). */
    val distance: List<Double>? = null,
    /** Altitude (m). */
    val altitude: List<Double>? = null,
    /** Smoothed velocity (m/s). */
    val velocitySmooth: List<Double>? = null,
    val heartrate: List<Double>? = null,
    val cadence: List<Double>? = null,
    val watts: List<Double>? = null,
    /** Grade in PERCENT (not a fraction). */
    val gradeSmooth: List<Double>? = null,
    val moving: List<Boolean>? = null
)

/** A normalized activity. Mirrors packages/schemas/src/activity.ts#Activity. */
@Serializable
data class Activity(
    val id: String,
    val source: ActivitySource,
    val sportType: SportType,
    val name: String,
    /** ISO-8601 UTC start. */
    val startDate: String,
    /** ISO-8601 local start; its first 10 chars are the activity's day key. */
    val startDateLocal: String? = null,
    val timezone: String? = null,
    /** Metres. */
    val distance: Double,
    /** Seconds. */
    val movingTime: Double,
    /** Seconds. */
    val elapsedTime: Double,
    val totalElevationGain: Double = 0.0,
    val elevHigh: Double? = null,
    val elevLow: Double? = null,
    /** m/s. */
    val averageSpeed: Double? = null,
    val maxSpeed: Double? = null,
    val averageHeartrate: Double? = null,
    val maxHeartrate: Double? = null,
    val hasHeartrate: Boolean = false,
    val averageCadence: Double? = null,
    /** Treadmill: belt-estimated distance, so pace is not trusted. */
    val trainer: Boolean = false,
    /** Manually entered: pace is not trusted. */
    val manual: Boolean = false,
    val streams: ActivityStreams? = null,
    val fetchedAt: String? = null
)

/** The athlete model. Mirrors packages/schemas/src/athlete.ts#AthleteProfile. */
@Serializable
data class AthleteProfile(
    val id: String = "me",
    val name: String? = null,
    val sex: Sex = Sex.UNSPECIFIED,
    val age: Int? = null,
    val weightKg: Double? = null,
    val units: Units = Units.METRIC,
    val experienceLevel: ExperienceLevel = ExperienceLevel.INTERMEDIATE,
    /** Functional threshold speed (m/s). */
    val thresholdSpeedMps: Double? = null,
    val lthr: Double? = null,
    val maxHr: Double? = null,
    val restingHr: Double? = null,
    val vdot: Double? = null,
    val anchorsUpdatedAt: String? = null,
    val goals: String? = null,
    val injuryHistory: List<String> = emptyList(),
    val medicalClearance: Boolean = false,
    val healthFlags: List<String> = emptyList(),
    val updatedAt: String? = null
)

/** Mirrors packages/schemas/src/athlete.ts#RaceGoal. */
@Serializable
data class RaceGoal(
    val distance: RaceDistance = RaceDistance.TEN_K,
    val customDistanceM: Double? = null,
    val name: String? = null,
    val date: String? = null,
    val goalTimeSec: Double? = null
)

/** Mirrors packages/schemas/src/metrics.ts#HrZone. */
@Serializable
data class HrZone(val zone: Int, val name: String, val minBpm: Double, val maxBpm: Double)

/** Mirrors packages/schemas/src/metrics.ts#PaceZone (bounds as speeds, m/s). */
@Serializable
data class PaceZone(val zone: Int, val label: IntensityLabel, val name: String, val minSpeedMps: Double, val maxSpeedMps: Double)

/** Mirrors packages/schemas/src/metrics.ts#Zones. */
@Serializable
data class Zones(val hr: List<HrZone> = emptyList(), val pace: List<PaceZone> = emptyList())

/** Polarized split. Mirrors packages/schemas/src/metrics.ts#ZoneDistribution. */
@Serializable
data class ZoneDistribution(
    val easySec: Double,
    val moderateSec: Double,
    val hardSec: Double,
    val easyPct: Double,
    val moderatePct: Double,
    val hardPct: Double
)

/** One day of aggregated load. Mirrors packages/schemas/src/metrics.ts#DailyLoad. */
@Serializable
data class DailyLoad(
    /** YYYY-MM-DD. */
    val date: String,
    val tss: Double,
    val durationSec: Double,
    val distanceM: Double,
    val method: LoadMethod,
    val activityIds: List<String> = emptyList()
)

/** Mirrors packages/schemas/src/metrics.ts#PmcPoint. */
@Serializable
data class PmcPoint(val date: String, val ctl: Double, val atl: Double, val tsb: Double)

/** Mirrors packages/schemas/src/metrics.ts#AcwrPoint. */
@Serializable
data class AcwrPoint(val date: String, val acwr: Double, val acuteLoad: Double, val chronicLoad: Double, val flag: AcwrFlag)

/** Compact activity summary inside [CoachContext]. Mirrors CoachContext.recentActivities[] in packages/schemas/src/coach.ts. */
@Serializable
data class RecentActivity(
    val date: String,
    val name: String,
    val sportType: SportType,
    val distanceKm: Double,
    val durationSec: Double,
    val tss: Double,
    val loadMethod: LoadMethod,
    val avgHr: Double? = null,
    val avgPaceSecPerKm: Double? = null
)

/** The pre-computed facts bundle. Mirrors packages/schemas/src/coach.ts#CoachContext. */
@Serializable
data class CoachContext(
    val generatedAt: String,
    val profile: AthleteProfile,
    val fitness: PmcPoint? = null,
    val acwr: AcwrPoint? = null,
    val rampRatePerWeek: Double? = null,
    val weeklyDistribution: ZoneDistribution? = null,
    val weeklyVolumeKm: Double? = null,
    /** Newest first: index 0 is the most recent activity. */
    val recentActivities: List<RecentActivity> = emptyList(),
    val goal: RaceGoal? = null,
    val daysToRace: Double? = null,
    val planPhase: PlanPhase? = null,
    val notes: String? = null
)

/** A prescribed session. Mirrors packages/schemas/src/coach.ts#WorkoutSuggestion. */
@Serializable
data class WorkoutSuggestion(
    val type: WorkoutType,
    val label: IntensityLabel? = null,
    val title: String,
    val description: String,
    val date: String? = null,
    val targetDistanceM: Double? = null,
    val targetDurationSec: Double? = null,
    val targetPaceSecPerKm: Double? = null,
    val targetHrZone: Int? = null,
    val targetTss: Double? = null,
    val rationale: String
)

/** Mirrors packages/schemas/src/coach.ts#PlanDay (day 1 = Monday ... 7 = Sunday). */
@Serializable
data class PlanDay(val day: Int, val date: String? = null, val sessions: List<WorkoutSuggestion>)

/** Mirrors packages/schemas/src/coach.ts#PlanWeek. */
@Serializable
data class PlanWeek(
    val weekNumber: Int,
    val phase: PlanPhase,
    val focus: String,
    val targetTss: Double? = null,
    val targetDistanceKm: Double? = null,
    val days: List<PlanDay>
)

/** Mirrors packages/schemas/src/coach.ts#TrainingPlan. */
@Serializable
data class TrainingPlan(
    val id: String,
    val createdAt: String,
    val goal: RaceGoal,
    val startDate: String,
    val endDate: String? = null,
    val summary: String? = null,
    val weeks: List<PlanWeek>
)

/** Mirrors packages/schemas/src/coach.ts#GuardrailViolation. [severity] is "error" or "warning". */
@Serializable
data class GuardrailViolation(val rule: String, val message: String, val severity: String, val weekNumber: Int? = null)

/** Mirrors packages/schemas/src/coach.ts#PlanValidation. */
@Serializable
data class PlanValidation(val valid: Boolean, val violations: List<GuardrailViolation> = emptyList(), val repaired: Boolean = false)

/** Mirrors packages/schemas/src/coach.ts#RedFlag. [source] is "keyword", "health", "load" or "classifier". */
@Serializable
data class RedFlag(val severity: RedFlagSeverity, val message: String, val source: String)

/** Daniels training speeds (m/s). Mirrors packages/core/src/science/vdot.ts#TrainingSpeeds. */
@Serializable
data class TrainingSpeeds(
    @SerialName("E") val easy: Double,
    @SerialName("M") val marathon: Double,
    @SerialName("T") val threshold: Double,
    @SerialName("I") val interval: Double,
    @SerialName("R") val repetition: Double
)
