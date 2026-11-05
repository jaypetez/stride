package org.stride.core.common

/** A typed success/failure result used across layers instead of throwing. */
sealed interface StrideResult<out T, out E> {
    data class Ok<T>(val value: T) : StrideResult<T, Nothing>

    data class Err<E>(val error: E) : StrideResult<Nothing, E>
}

inline fun <T, E, R> StrideResult<T, E>.map(transform: (T) -> R): StrideResult<R, E> = when (this) {
    is StrideResult.Ok -> StrideResult.Ok(transform(value))
    is StrideResult.Err -> this
}

fun <T, E> StrideResult<T, E>.getOrNull(): T? = (this as? StrideResult.Ok)?.value
