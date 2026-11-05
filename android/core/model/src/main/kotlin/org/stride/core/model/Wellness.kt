package org.stride.core.model

import kotlinx.serialization.Serializable

/** One day of Garmin Health data. Any field may be missing; the UI hides empty tiles. */
@Serializable
data class DailyWellness(
    /** ISO local date, e.g. 2026-11-01. */
    val date: String,
    val restingHr: Int? = null,
    val hrvLastNightMs: Double? = null,
    val hrvWeeklyAvgMs: Double? = null,
    val hrvStatus: String? = null,
    val sleepSec: Int? = null,
    val sleepScore: Int? = null,
    val stressAvg: Int? = null,
    val bodyBatteryHigh: Int? = null,
    val bodyBatteryLow: Int? = null,
    val steps: Int? = null,
    val weightKg: Double? = null,
    val source: DataSource = DataSource.GARMIN
)
