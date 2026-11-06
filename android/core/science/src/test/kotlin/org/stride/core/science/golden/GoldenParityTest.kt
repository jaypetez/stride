package org.stride.core.science.golden

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.stride.core.science.anchors.estimateAnchors
import org.stride.core.science.anchors.resolveLthr
import org.stride.core.science.anchors.resolveMaxHr
import org.stride.core.science.anchors.resolveRestingHr
import org.stride.core.science.anchors.resolveThresholdSpeed
import org.stride.core.science.coach.DISCLAIMER
import org.stride.core.science.coach.PARQ_QUESTIONS
import org.stride.core.science.coach.RedFlagInput
import org.stride.core.science.coach.classifierWarnings
import org.stride.core.science.coach.detectRedFlags
import org.stride.core.science.coach.makeSession
import org.stride.core.science.coach.proposeNextWorkout
import org.stride.core.science.coach.resolveRaceDistanceM
import org.stride.core.science.coach.screenReadiness
import org.stride.core.science.coach.shouldHalt
import org.stride.core.science.efficiency.aerobicDecoupling
import org.stride.core.science.efficiency.efficiencyFactor
import org.stride.core.science.load.banisterTrimp
import org.stride.core.science.load.computeActivityLoad
import org.stride.core.science.load.computeNgp
import org.stride.core.science.model.Activity
import org.stride.core.science.model.ActivityStreams
import org.stride.core.science.model.AthleteProfile
import org.stride.core.science.model.CoachContext
import org.stride.core.science.model.DailyLoad
import org.stride.core.science.model.ExperienceLevel
import org.stride.core.science.model.HrZone
import org.stride.core.science.model.PmcPoint
import org.stride.core.science.model.RaceGoal
import org.stride.core.science.model.RedFlag
import org.stride.core.science.model.TrainingPlan
import org.stride.core.science.model.WorkoutType
import org.stride.core.science.plan.PlanGuardrailContext
import org.stride.core.science.plan.PlanSkeletonParams
import org.stride.core.science.plan.buildPlanSkeleton
import org.stride.core.science.plan.fitnessSeed
import org.stride.core.science.plan.repairPlan
import org.stride.core.science.plan.validatePlan
import org.stride.core.science.pmc.AcwrOptions
import org.stride.core.science.pmc.PmcOptions
import org.stride.core.science.pmc.buildAcwrSeries
import org.stride.core.science.pmc.buildPmcSeries
import org.stride.core.science.pmc.ctlRampCap
import org.stride.core.science.pmc.rampRatePerWeek
import org.stride.core.science.pmc.toDailyLoads
import org.stride.core.science.util.gradeAdjustFactor
import org.stride.core.science.vdot.predictRaceTimeSec
import org.stride.core.science.vdot.thresholdSpeedFromVdot
import org.stride.core.science.vdot.trainingSpeeds
import org.stride.core.science.vdot.vdotFromEffort
import org.stride.core.science.zones.computeHrZones
import org.stride.core.science.zones.computePaceZones
import org.stride.core.science.zones.computeZones
import org.stride.core.science.zones.timeInHrZones
import org.stride.core.science.zones.zoneDistribution

/** Every case in contracts/science-golden must be reproduced by the Kotlin port (ADR 0010). */
class GoldenParityTest {
    private fun GoldenCase.d(key: String): Double = arg(key)
    private fun GoldenCase.activity(): Activity = arg("activity")
    private fun GoldenCase.profile(): AthleteProfile = arg("profile")
    private fun num(x: Double?): JsonElement = encode(x)

    @Test
    fun load() {
        runGoldenFile(
            "load.json",
            mapOf(
                "gradeAdjustFactor" runs { c -> num(gradeAdjustFactor(c.d("gradeFraction"))) },
                "computeNgp" runs { c -> encode(computeNgp(c.arg<ActivityStreams>("streams"))) },
                "computeActivityLoad" runs { c -> encode(computeActivityLoad(c.activity(), c.profile())) },
                "banisterTrimp" runs { c -> num(banisterTrimp(c.activity(), c.profile())) }
            )
        )
    }

    @Test
    fun pmc() {
        runGoldenFile(
            "pmc.json",
            mapOf(
                "toDailyLoads" runs { c -> encode(toDailyLoads(c.arg<List<Activity>>("activities"), c.profile())) },
                "buildPmcSeries" runs { c -> encode(buildPmcSeries(c.arg<List<DailyLoad>>("dailyLoads"), c.arg<PmcOptions>("options"))) },
                "buildAcwrSeries" runs { c ->
                    encode(buildAcwrSeries(c.arg<List<DailyLoad>>("dailyLoads"), c.arg<AcwrOptions>("options")))
                },
                "rampRatePerWeek" runs { c -> num(rampRatePerWeek(c.arg<List<PmcPoint>>("series"), c.arg<Int>("weeks"))) },
                "ctlRampCap" runs { c -> num(ctlRampCap(c.arg<ExperienceLevel>("experience"))) }
            )
        )
    }

    @Test
    fun zones() {
        runGoldenFile(
            "zones.json",
            mapOf(
                "computeHrZones" runs { c -> encode(computeHrZones(c.d("lthr"), c.arg<Double?>("maxHr"))) },
                "computePaceZones" runs { c -> encode(computePaceZones(c.d("thresholdSpeedMps"))) },
                "computeZones" runs { c -> encode(computeZones(c.profile())) },
                "timeInHrZones" runs { c ->
                    encode(
                        timeInHrZones(
                            c.arg<List<Double>>("hr"),
                            c.arg<List<Double>?>("time"),
                            c.arg<List<HrZone>>("zones"),
                            c.arg<List<Boolean>?>("moving")
                        )
                    )
                },
                "zoneDistribution" runs { c -> encode(zoneDistribution(c.arg<List<Activity>>("activities"), c.profile())) }
            )
        )
    }

    @Test
    fun vdot() {
        runGoldenFile(
            "vdot.json",
            mapOf(
                "vdotFromEffort" runs { c -> num(vdotFromEffort(c.d("distanceM"), c.d("timeSec"))) },
                "predictRaceTimeSec" runs { c -> num(predictRaceTimeSec(c.d("vdot"), c.d("distanceM"))) },
                "trainingSpeeds" runs { c -> encode(trainingSpeeds(c.d("vdot"))) },
                "thresholdSpeedFromVdot" runs { c -> num(thresholdSpeedFromVdot(c.d("vdot"))) }
            )
        )
    }

    @Test
    fun anchors() {
        runGoldenFile(
            "anchors.json",
            mapOf(
                "estimateAnchors" runs { c -> encode(estimateAnchors(c.arg<List<Activity>>("activities"))) },
                "resolveMaxHr" runs { c -> num(resolveMaxHr(c.profile())) },
                "resolveLthr" runs { c -> num(resolveLthr(c.profile())) },
                "resolveRestingHr" runs { c -> num(resolveRestingHr(c.profile())) },
                "resolveThresholdSpeed" runs { c -> num(resolveThresholdSpeed(c.profile())) }
            )
        )
    }

    @Test
    fun efficiency() {
        runGoldenFile(
            "efficiency.json",
            mapOf(
                "efficiencyFactor" runs { c -> num(efficiencyFactor(c.activity())) },
                "aerobicDecoupling" runs { c -> num(aerobicDecoupling(c.activity())) }
            )
        )
    }

    @Test
    fun planner() {
        runGoldenFile(
            "planner.json",
            mapOf(
                "proposeNextWorkout" runs { c -> encode(proposeNextWorkout(c.arg<CoachContext>("context"), c.profile())) },
                "buildPlanSkeleton" runs { c -> encode(buildPlanSkeleton(c.arg<PlanSkeletonParams>("params"))) },
                "makeSession" runs { c ->
                    val opts = c.input["opts"]?.jsonObject ?: JsonObject(emptyMap())
                    fun opt(k: String) = opts[k]?.jsonPrimitive?.contentOrNull
                    encode(
                        makeSession(
                            c.arg<WorkoutType>("type"),
                            c.d("durationMin"),
                            c.d("thresholdSpeedMps"),
                            date = opt("date"),
                            rationale = opt("rationale"),
                            description = opt("description")
                        )
                    )
                },
                "resolveRaceDistanceM" runs { c -> num(resolveRaceDistanceM(c.arg<RaceGoal>("goal"))) }
            )
        )
    }

    @Test
    fun seed() {
        runGoldenFile(
            "seed.json",
            mapOf(
                "fitnessSeed" runs { c -> encode(fitnessSeed(c.arg<List<Activity>>("activities"), c.arg<String>("asOf"))) }
            )
        )
    }

    @Test
    fun guardrail() {
        runGoldenFile(
            "guardrail.json",
            mapOf(
                "validatePlan" runs { c -> encode(validatePlan(c.arg<TrainingPlan>("plan"), c.arg<PlanGuardrailContext>("ctx"))) },
                "repairPlan" runs { c -> encode(repairPlan(c.arg<TrainingPlan>("plan"), c.arg<PlanGuardrailContext>("ctx"))) }
            )
        )
    }

    @Test
    fun safety() {
        runGoldenFile(
            "safety.json",
            mapOf(
                "PARQ_QUESTIONS" runs { _ -> encode(PARQ_QUESTIONS) },
                "DISCLAIMER" runs { _ -> JsonPrimitive(DISCLAIMER) },
                "detectRedFlags" runs { c -> encode(detectRedFlags(c.arg<RedFlagInput>("input"))) },
                "shouldHalt" runs { c -> JsonPrimitive(shouldHalt(c.arg<List<RedFlag>>("flags"))) },
                "screenReadiness" runs { c -> encode(screenReadiness(c.arg<List<Boolean>>("answers"))) },
                "classifierWarnings" runs { c -> encode(classifierWarnings(c.arg<List<String>>("labels"))) }
            )
        )
    }

    @Test
    fun safetyTextIsByteEqual() {
        val cases = loadGolden("safety.json")
        val disclaimer = cases.single { it.fn == "DISCLAIMER" }.output.jsonPrimitive.content
        assertEquals(disclaimer, DISCLAIMER)
        val parq = cases.single { it.fn == "PARQ_QUESTIONS" }.output
        assertEquals(encode(PARQ_QUESTIONS), parq)
    }
}
