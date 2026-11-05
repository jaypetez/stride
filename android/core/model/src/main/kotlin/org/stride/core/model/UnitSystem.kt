package org.stride.core.model

import kotlinx.serialization.Serializable

/** Display units chosen by the user. All stored values are SI (metres, seconds). */
@Serializable
enum class UnitSystem {
    METRIC,
    IMPERIAL
}
