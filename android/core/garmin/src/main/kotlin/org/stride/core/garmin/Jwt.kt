package org.stride.core.garmin

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object Jwt {
    /** Reads the `exp` claim (epoch seconds) from a JWT without verifying it; null if absent/malformed. */
    fun expiry(token: String): Long? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return null
        val json = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
        Json.parseToJsonElement(json).jsonObject["exp"]?.jsonPrimitive?.longOrNull
    }.getOrNull()

    /**
     * The token endpoint returns the raw JWT as text, sometimes quoted, and some
     * clients report a JSON `{ "access_token": … }` body. Accept all three.
     */
    fun parseTokenBody(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            return runCatching {
                Json.parseToJsonElement(trimmed).jsonObject["access_token"]?.jsonPrimitive?.content
            }.getOrNull()
        }
        return trimmed.removeSurrounding("\"").takeIf { it.isNotBlank() }
    }
}
