package org.stride.core.garmin

import org.stride.core.common.StrideResult

/**
 * Typed failures. Messages never contain the API key or access token, so they
 * are safe to log or show (MVP-003).
 */
sealed interface GarminError {
    /** No key has been stored yet. */
    data object KeyMissing : GarminError

    /** The key was rejected (revoked, expired or mistyped). Sync stops and the Connect screen is flagged. */
    data object KeyInvalid : GarminError

    /** No connectivity / DNS / TLS / timeout. */
    data class Network(val reason: String) : GarminError

    /** Still throttled (HTTP 429) after the bounded retries. */
    data class RateLimited(val retryAfterSec: Long?) : GarminError

    /** HTTP 5xx after the bounded retries. */
    data class Server(val code: Int) : GarminError

    /** Any other non-success status. */
    data class Http(val code: Int) : GarminError

    /** The body could not be decoded. */
    data class Parse(val reason: String) : GarminError
}

typealias GarminResult<T> = StrideResult<T, GarminError>
