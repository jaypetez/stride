package org.stride.core.common

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Minimal logger shared by JVM and Android modules. It writes through
 * `java.util.logging`, which Android forwards to logcat.
 *
 * Never pass keys, tokens or personal data: log the failure's type, not its message.
 */
object StrideLog {
    private val logger: Logger = Logger.getLogger("Stride")

    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARNING, tag, message, error)

    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)

    private fun log(level: Level, tag: String, message: String, error: Throwable?) {
        val suffix = error?.let { " (${it::class.java.simpleName})" }.orEmpty()
        logger.log(level, "[$tag] $message$suffix")
    }
}
