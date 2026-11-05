package org.stride.core.ui

import androidx.annotation.StringRes
import org.stride.core.model.FormBand
import org.stride.core.model.LoadMethod
import org.stride.core.model.SportType

/**
 * Localised labels for domain enums (AUD-034). The English `label`s on the
 * model stay as they are for the coach facts; screens use these resources.
 * The privacy note and Garmin attribution are `R.string.privacy_note` and
 * `R.string.garmin_attribution` (MVP-013/021 contract text).
 */
@get:StringRes
val FormBand.labelRes: Int
    get() = when (this) {
        FormBand.FRESH -> R.string.form_band_fresh
        FormBand.NEUTRAL -> R.string.form_band_neutral
        FormBand.PRODUCTIVE -> R.string.form_band_productive
        FormBand.OVERREACHING -> R.string.form_band_overreaching
    }

@get:StringRes
val FormBand.descriptionRes: Int
    get() = when (this) {
        FormBand.FRESH -> R.string.form_band_fresh_description
        FormBand.NEUTRAL -> R.string.form_band_neutral_description
        FormBand.PRODUCTIVE -> R.string.form_band_productive_description
        FormBand.OVERREACHING -> R.string.form_band_overreaching_description
    }

@get:StringRes
val SportType.labelRes: Int
    get() = when (this) {
        SportType.RUN -> R.string.sport_run
        SportType.TRAIL_RUN -> R.string.sport_trail_run
        SportType.TREADMILL_RUN -> R.string.sport_treadmill_run
        SportType.WALK -> R.string.sport_walk
        SportType.HIKE -> R.string.sport_hike
        SportType.CYCLE -> R.string.sport_cycle
        SportType.SWIM -> R.string.sport_swim
        SportType.STRENGTH -> R.string.sport_strength
        SportType.OTHER -> R.string.sport_other
    }

@get:StringRes
val LoadMethod.labelRes: Int
    get() = when (this) {
        LoadMethod.RTSS -> R.string.load_method_rtss
        LoadMethod.TRIMP -> R.string.load_method_trimp
        LoadMethod.DURATION -> R.string.load_method_duration
    }
