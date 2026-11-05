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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import org.stride.core.ui.R
import org.stride.core.ui.UiText

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
    errors: Map<ProfileField, UiText> = emptyMap()
) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FormField(
            label = stringResource(R.string.profile_name),
            value = state.name,
            onValueChange = { onChange(state.copy(name = it)) },
            error = errors[ProfileField.NAME],
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
        )
        FormField(
            label = stringResource(R.string.profile_birth_year),
            value = state.birthYear,
            onValueChange = { onChange(state.copy(birthYear = it.digits(4))) },
            error = errors[ProfileField.BIRTH_YEAR],
            keyboard = NUMBER
        )

        SectionHeader(stringResource(R.string.profile_sex))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SEX_OPTIONS.forEach { (sex, label) ->
                FilterChip(selected = state.sex == sex, onClick = {
                    onChange(state.copy(sex = sex))
                }, label = { Text(stringResource(label)) })
            }
        }

        SectionHeader(stringResource(R.string.profile_units))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnitSystem.entries.forEach { units ->
                FilterChip(
                    selected = state.units == units,
                    onClick = { onChange(state.copy(units = units)) },
                    label = { Text(stringResource(if (units == UnitSystem.METRIC) R.string.units_kilometres else R.string.units_miles)) }
                )
            }
        }

        SectionHeader(stringResource(R.string.profile_heart_rate))
        Text(
            stringResource(R.string.profile_heart_rate_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FormField(stringResource(R.string.profile_max_hr), state.maxHr, {
            onChange(state.copy(maxHr = it.digits(3)))
        }, errors[ProfileField.MAX_HR], NUMBER)
        FormField(
            stringResource(R.string.profile_resting_hr),
            state.restingHr,
            { onChange(state.copy(restingHr = it.digits(3))) },
            errors[ProfileField.RESTING_HR],
            NUMBER
        )
        FormField(
            stringResource(R.string.profile_threshold_hr),
            state.thresholdHr,
            { onChange(state.copy(thresholdHr = it.digits(3))) },
            errors[ProfileField.THRESHOLD_HR],
            NUMBER
        )

        SectionHeader(stringResource(R.string.profile_race_goal))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.raceDistance == null,
                onClick = { onChange(state.copy(raceDistance = null, raceDate = null)) },
                label = { Text(stringResource(R.string.profile_race_none)) }
            )
            RaceDistance.entries.forEach { distance ->
                FilterChip(
                    selected = state.raceDistance == distance,
                    onClick = { onChange(state.copy(raceDistance = distance)) },
                    label = { Text(stringResource(distance.labelRes)) }
                )
            }
        }
        if (state.raceDistance != null) {
            StrideOutlinedButton(
                text = state.raceDate?.let { stringResource(R.string.profile_race_day, Format.dayWithYear(it)) }
                    ?: stringResource(R.string.profile_pick_race_date),
                onClick = { pickingDate = true }
            )
            errors[ProfileField.RACE_DATE]?.let { ErrorText(it.resolve()) }
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
            ) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    ) {
        DatePicker(state = pickerState)
    }
}

@Composable
private fun FormField(label: String, value: String, onValueChange: (String) -> Unit, error: UiText?, keyboard: KeyboardOptions) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it.resolve()) } },
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

private val SEX_OPTIONS = listOf(
    Sex.FEMALE to R.string.profile_sex_female,
    Sex.MALE to R.string.profile_sex_male,
    Sex.UNSPECIFIED to R.string.profile_sex_unspecified
)

private fun String.digits(max: Int) = filter(Char::isDigit).take(max)

/** Material3 date pickers work in UTC millis at midnight. */
fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
