package org.stride.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.stride.core.designsystem.component.LoadingState
import org.stride.core.designsystem.component.StatusPill
import org.stride.core.designsystem.component.StrideButton
import org.stride.core.designsystem.component.StrideCard
import org.stride.core.designsystem.component.StrideOutlinedButton
import org.stride.core.designsystem.component.StrideTextButton
import org.stride.core.designsystem.component.StrideTopAppBar
import org.stride.core.designsystem.theme.StrideTheme
import org.stride.core.science.model.GuardrailViolation
import org.stride.core.science.model.PlanDay
import org.stride.core.science.model.PlanPhase
import org.stride.core.science.model.PlanWeek
import org.stride.core.science.model.RaceDistance
import org.stride.core.science.model.TrainingPlan
import org.stride.core.science.model.WorkoutType
import org.stride.core.ui.Format
import org.stride.core.ui.LocalUnitSystem
import org.stride.core.ui.formInsets
import org.stride.core.ui.listContentPadding

@Composable
fun PlanRoute(modifier: Modifier = Modifier, viewModel: PlanViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PlanScreen(
        state = state,
        actions = PlanActions(
            onDistance = viewModel::setDistance,
            onRaceDate = viewModel::setRaceDate,
            onGoalTime = viewModel::setGoalTime,
            onCreate = viewModel::create,
            onDelete = viewModel::delete
        ),
        modifier = modifier
    )
}

/** Callbacks of the plan screen, bundled to keep signatures short. */
data class PlanActions(
    val onDistance: (RaceDistance) -> Unit = {},
    val onRaceDate: (LocalDate) -> Unit = {},
    val onGoalTime: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onDelete: () -> Unit = {}
)

/** Plan tab (MVP-018): a race-goal form, or the saved plan week by week with today highlighted. */
@Composable
fun PlanScreen(state: PlanUiState, actions: PlanActions, modifier: Modifier = Modifier) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    CompositionLocalProvider(LocalUnitSystem provides state.units) {
        Scaffold(
            modifier = modifier,
            topBar = {
                StrideTopAppBar(
                    title = "Training plan",
                    actions = {
                        if (state.plan != null) {
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(imageVector = Icons.Outlined.Delete, contentDescription = "Delete plan")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            val plan = state.plan
            when {
                state.isLoading -> LoadingState(modifier = Modifier.padding(padding))
                plan == null -> PlanFormContent(state, actions, Modifier.padding(padding))
                else -> PlanContent(plan, state, onDelete = { confirmDelete = true }, modifier = Modifier.padding(padding))
            }
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete plan?") },
                    text = { Text("Your race goal stays on your profile; you can create a new plan any time.") },
                    confirmButton = {
                        StrideTextButton(text = "Delete", onClick = {
                            confirmDelete = false
                            actions.onDelete()
                        })
                    },
                    dismissButton = { StrideTextButton(text = "Cancel", onClick = { confirmDelete = false }) }
                )
            }
        }
    }
}

@Composable
private fun PlanFormContent(state: PlanUiState, actions: PlanActions, modifier: Modifier = Modifier) {
    var pickDate by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .formInsets()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Set a race goal and Stride builds a periodised plan — base, build, peak and taper — that passes the " +
                "safety guardrails (ramp rate, rest days, no back-to-back hard days, long-run cap).",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(text = "Race distance", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RACE_DISTANCES.forEach { (distance, label) ->
                FilterChip(selected = state.form.distance == distance, onClick = { actions.onDistance(distance) }, label = { Text(label) })
            }
        }
        Text(text = "Race date", style = MaterialTheme.typography.titleSmall)
        StrideOutlinedButton(
            text = Format.day(state.form.raceDate),
            onClick = { pickDate = true },
            leadingIcon = Icons.Outlined.CalendarMonth,
            modifier = Modifier.semantics { contentDescription = "Race date ${Format.day(state.form.raceDate)}, change" }
        )
        OutlinedTextField(
            value = state.form.goalTime,
            onValueChange = actions.onGoalTime,
            label = { Text("Goal time (optional)") },
            placeholder = { Text("h:mm:ss") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        state.error?.let { Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (state.violations.isNotEmpty()) ViolationsCard(state.violations)
        StrideButton(text = "Create plan", onClick = actions.onCreate, loading = state.creating, modifier = Modifier.fillMaxWidth())
        Text(
            text = "Plans end on race day and run up to 24 weeks. Earliest race: ${Format.day(state.earliestRaceDate)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (pickDate) {
            RaceDatePicker(
                initial = state.form.raceDate,
                earliest = state.earliestRaceDate,
                onPick = {
                    pickDate = false
                    actions.onRaceDate(it)
                },
                onDismiss = { pickDate = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaceDatePicker(initial: LocalDate, earliest: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val earliestMillis = earliest.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= earliestMillis
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            StrideTextButton(text = "OK", onClick = {
                pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            })
        },
        dismissButton = { StrideTextButton(text = "Cancel", onClick = onDismiss) }
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun ViolationsCard(violations: List<GuardrailViolation>, modifier: Modifier = Modifier) {
    StrideCard(modifier = modifier, title = "Plan not saved", subtitle = "It broke the safety guardrails even after repair") {
        violations.forEach { v ->
            Text(
                text = "• ${v.weekNumber?.let { "Week $it: " }.orEmpty()}${v.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(text = "Try a later race date or a shorter distance.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlanContent(plan: TrainingPlan, state: PlanUiState, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(plan.id, state.currentWeekIndex) {
        state.currentWeekIndex?.let { listState.scrollToItem(it + 1) }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = listContentPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") { PlanHeader(plan, state.repairs) }
        itemsIndexed(plan.weeks, key = { _, w -> "week-${w.weekNumber}" }) { index, week ->
            WeekCard(week, today = state.today, isCurrent = index == state.currentWeekIndex)
        }
        item(key = "delete") {
            StrideOutlinedButton(
                text = "Delete plan",
                onClick = onDelete,
                leadingIcon = Icons.Outlined.Delete,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PlanHeader(plan: TrainingPlan, repairs: List<GuardrailViolation>, modifier: Modifier = Modifier) {
    val goal = plan.goal
    val label = RACE_DISTANCES.firstOrNull { it.first == goal.distance }?.second ?: goal.distance.wire
    StrideCard(
        modifier = modifier,
        title = "$label · ${goal.date?.let { Format.day(it) }.orEmpty()}",
        subtitle = goal.goalTimeSec?.let { "Goal ${Format.duration(it)}" } ?: "No goal time"
    ) {
        plan.summary?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            text = "${Format.day(plan.startDate)} – ${plan.endDate?.let {
                Format.day(it)
            }.orEmpty()} · checked against the safety guardrails",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (repairs.isNotEmpty()) {
            Text(
                text = "Adjusted to stay safe: " + repairs.joinToString("; ") { it.message },
                style = MaterialTheme.typography.bodySmall,
                color = StrideTheme.colors.warning
            )
        }
    }
}

@Composable
private fun WeekCard(week: PlanWeek, today: String, isCurrent: Boolean, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    val targets = listOfNotNull(
        week.targetDistanceKm?.let { Format.distanceWithUnit(it * 1000, units, 1) },
        week.targetTss?.let { "${Format.number(it, 0)} TSS" }
    ).joinToString(" · ")
    StrideCard(
        modifier = modifier,
        title = "Week ${week.weekNumber}${if (isCurrent) " · this week" else ""}",
        subtitle = targets.ifEmpty { null },
        trailing = { StatusPill(text = week.phase.displayName(), color = week.phase.color()) }
    ) {
        Text(text = week.focus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        week.days.forEach { day -> DayRow(day, isToday = day.date == today) }
    }
}

@Composable
private fun DayRow(day: PlanDay, isToday: Boolean, modifier: Modifier = Modifier) {
    val units = LocalUnitSystem.current
    val date = day.date?.let { LocalDate.parse(it) }
    val background = if (isToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .then(if (isToday) Modifier.semantics { contentDescription = "Today" } else Modifier),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.width(64.dp)) {
            Text(
                text = if (isToday) "Today" else date?.let { Format.weekday(it) } ?: "Day ${day.day}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
            )
            date?.let {
                Text(
                    text = Format.day(it).substringAfter(' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            day.sessions.forEach { s ->
                val rest = s.type == WorkoutType.REST
                Text(text = if (rest) "Rest" else s.title, style = MaterialTheme.typography.bodyMedium)
                if (!rest) {
                    val details = listOfNotNull(
                        s.targetDurationSec?.let { Format.durationShort(it) },
                        s.targetDistanceM?.let { Format.distanceWithUnit(it, units, 1) },
                        s.targetPaceSecPerKm?.let { "${Format.paceFromSecPerKm(it, units)} ${Format.paceUnit(units)}" }
                    ).joinToString(" · ")
                    if (details.isNotEmpty()) {
                        Text(text = details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

internal val RACE_DISTANCES = listOf(
    RaceDistance.FIVE_K to "5K",
    RaceDistance.TEN_K to "10K",
    RaceDistance.HALF to "Half",
    RaceDistance.MARATHON to "Marathon"
)

private fun PlanPhase.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun PlanPhase.color() = when (this) {
    PlanPhase.BASE -> StrideTheme.colors.zone2
    PlanPhase.BUILD -> StrideTheme.colors.zone3
    PlanPhase.PEAK -> StrideTheme.colors.zone4
    PlanPhase.TAPER -> StrideTheme.colors.fresh
    PlanPhase.RECOVERY -> StrideTheme.colors.zone1
}
