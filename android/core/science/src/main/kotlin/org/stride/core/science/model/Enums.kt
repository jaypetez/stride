package org.stride.core.science.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors packages/schemas/src/enums.ts#ActivitySource. */
@Serializable
enum class ActivitySource {
    @SerialName("strava")
    STRAVA,

    @SerialName("upload")
    UPLOAD,

    @SerialName("manual")
    MANUAL
}

/** Mirrors packages/schemas/src/enums.ts#SportType. */
@Serializable
enum class SportType {
    @SerialName("run")
    RUN,

    @SerialName("trail_run")
    TRAIL_RUN,

    @SerialName("treadmill_run")
    TREADMILL_RUN,

    @SerialName("walk")
    WALK,

    @SerialName("hike")
    HIKE,

    @SerialName("other")
    OTHER
}

/** Mirrors packages/schemas/src/enums.ts#Sex. */
@Serializable
enum class Sex {
    @SerialName("male")
    MALE,

    @SerialName("female")
    FEMALE,

    @SerialName("unspecified")
    UNSPECIFIED
}

/** Mirrors packages/schemas/src/enums.ts#ExperienceLevel. [wire] is the TS string (used in guardrail messages). */
@Serializable
enum class ExperienceLevel(val wire: String) {
    @SerialName("beginner")
    BEGINNER("beginner"),

    @SerialName("intermediate")
    INTERMEDIATE("intermediate"),

    @SerialName("advanced")
    ADVANCED("advanced")
}

/** Mirrors packages/schemas/src/enums.ts#Units. */
@Serializable
enum class Units {
    @SerialName("metric")
    METRIC,

    @SerialName("imperial")
    IMPERIAL
}

/**
 * How a training load was derived. Mirrors packages/schemas/src/enums.ts#LoadMethod.
 * [rank] is the preference order used when several activities share a day.
 */
@Serializable
enum class LoadMethod(val rank: Int) {
    @SerialName("rtss")
    RTSS(4),

    @SerialName("hrtss")
    HRTSS(3),

    @SerialName("trimp")
    TRIMP(2),

    @SerialName("duration")
    DURATION(1),

    @SerialName("none")
    NONE(0)
}

/** Daniels intensity label. Mirrors packages/schemas/src/enums.ts#IntensityLabel. */
@Serializable
enum class IntensityLabel {
    E,
    M,
    T,
    I,
    R
}

/** Mirrors packages/schemas/src/enums.ts#WorkoutType. */
@Serializable
enum class WorkoutType {
    @SerialName("easy")
    EASY,

    @SerialName("long")
    LONG,

    @SerialName("recovery")
    RECOVERY,

    @SerialName("tempo")
    TEMPO,

    @SerialName("threshold")
    THRESHOLD,

    @SerialName("interval")
    INTERVAL,

    @SerialName("repetition")
    REPETITION,

    @SerialName("race")
    RACE,

    @SerialName("rest")
    REST,

    @SerialName("cross_training")
    CROSS_TRAINING
}

/** Mesocycle phase. Mirrors packages/schemas/src/enums.ts#PlanPhase. */
@Serializable
enum class PlanPhase {
    @SerialName("base")
    BASE,

    @SerialName("build")
    BUILD,

    @SerialName("peak")
    PEAK,

    @SerialName("taper")
    TAPER,

    @SerialName("recovery")
    RECOVERY
}

/** Mirrors packages/schemas/src/enums.ts#RaceDistance. [wire] is the TS string (used in plan summaries). */
@Serializable
enum class RaceDistance(val wire: String) {
    @SerialName("5k")
    FIVE_K("5k"),

    @SerialName("10k")
    TEN_K("10k"),

    @SerialName("half")
    HALF("half"),

    @SerialName("marathon")
    MARATHON("marathon"),

    @SerialName("custom")
    CUSTOM("custom")
}

/** Mirrors packages/schemas/src/metrics.ts#AcwrFlag. */
@Serializable
enum class AcwrFlag {
    @SerialName("low")
    LOW,

    @SerialName("ok")
    OK,

    @SerialName("high")
    HIGH,

    @SerialName("very_high")
    VERY_HIGH
}

/** Mirrors packages/schemas/src/coach.ts#RedFlagSeverity. */
@Serializable
enum class RedFlagSeverity {
    @SerialName("info")
    INFO,

    @SerialName("warning")
    WARNING,

    @SerialName("stop")
    STOP
}
