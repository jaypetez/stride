package org.stride.core.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.stride.core.designsystem.component.SectionHeader
import org.stride.core.designsystem.component.StrideOutlinedButton
import org.stride.core.model.Sex
import org.stride.core.model.UnitSystem
import org.stride.core.ui.Format

/**
 * The local profile form shared by onboarding and Settings → Edit profile.
 * Stateless: [state] in, [onChange] out; [errors] are shown under their fields.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileForm(
    state: ProfileFormState,
    onChange: (ProfileFormState) -> Unit,
    modifier: Modifier = Modifier,
    errors: Map<ProfileField, String> = emptyMap()
) {
    var pickingDate by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField(
            label = "Name",
            value = state.name,
            onValueChange = { onChange(state.copy(name = it)) },
            error = errors[ProfileField.NAME],
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
        )
        FormField(
            label = "Birth year",
            value = state.birthYear,
            onValueChange = { onChange(state.copy(birthYear = it.digits(4))) },
            error = errors[ProfileField.BIRTH_YEAR],
            keyboard = NUMBER
        )

        SectionHeader("Sex")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SEX_OPTIONS.forEach { (sex, label) ->
                FilterChip(selected = state.sex == sex, onClick = { onChange(state.copy(sex = sex)) }, label = { Text(label) })
            }
        }

        SectionHeader("Units")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnitSystem.entries.forEach { units ->
                FilterChip(
                    selected = state.units == units,
                    onClick = { onChange(state.copy(units = units)) },
                    label = { Text(if (units == UnitSystem.METRIC) "Kilometres" else "Miles") }
                )
            }
        }

        SectionHeader("Heart rate (optional)")
        Text(
            "Leave blank and Stride estimates these from your runs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FormField("Max HR (bpm)", state.maxHr, { onChange(state.copy(maxHr = it.digits(3))) }, errors[ProfileField.MAX_HR], NUMBER)
        FormField(
            "Resting HR (bpm)",
            state.restingHr,
            { onChange(state.copy(restingHr = it.digits(3))) },
            errors[ProfileField.RESTING_HR],
            NUMBER
        )
        FormField(
            "Threshold HR (bpm)",
            state.thresholdHr,
            { onChange(state.copy(thresholdHr = it.digits(3))) },
            errors[ProfileField.THRESHOLD_HR],
            NUMBER
        )

        SectionHeader("Race goal (optional)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.raceDistance == null,
                onClick = { onChange(state.copy(raceDistance = null, raceDate = null)) },
                label = { Text("None") }
            )
            RaceDistance.entries.forEach { distance ->
                FilterChip(
                    selected = state.raceDistance == distance,
                    onClick = { onChange(state.copy(raceDistance = distance)) },
                    label = { Text(distance.label) }
                )
            }
        }
        if (state.raceDistance != null) {
            StrideOutlinedButton(
                text = state.raceDate?.let { "Race day: ${Format.day(it)} ${it.year}" } ?: "Pick race date",
                onClick = { pickingDate = true }
            )
            errors[ProfileField.RACE_DATE]?.let { ErrorText(it) }
        }
        if (pickingDate) {
            RaceDatePicker(
                initial = state.raceDate,
                onPicked = {
                    onChange(state.copy(raceDate = it))
                    pickingDate = false
                },
                onDismiss = { pickingDate = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaceDatePicker(initial: LocalDate?, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial?.toUtcMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { pickerState.selectedDateMillis?.let { onPicked(it.toUtcDate()) } ?: onDismiss() }
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun FormField(label: String, value: String, onValueChange: (String) -> Unit, error: String?, keyboard: KeyboardOptions) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = keyboard,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 4.dp)
    )
}

private val NUMBER = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)

private val SEX_OPTIONS = listOf(Sex.FEMALE to "Female", Sex.MALE to "Male", Sex.UNSPECIFIED to "Prefer not to say")

private fun String.digits(max: Int) = filter(Char::isDigit).take(max)

/** Material3 date pickers work in UTC millis at midnight. */
fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
