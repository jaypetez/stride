package org.stride.core.garmin

/** A Garmin access token with its expiry (JWT `exp`, epoch seconds). */
data class AccessToken(val value: String, val expiresAtEpochSec: Long) {
    override fun toString(): String = "AccessToken(value=██, expiresAtEpochSec=$expiresAtEpochSec)"
}

/** Supplies the stored Personal API key (implemented by core:datastore's GarminKeyStore). */
fun interface GarminKeyProvider {
    suspend fun apiKey(): String?
}

/** Caches the 24h access token. The default keeps it in memory; core:datastore persists it encrypted. */
interface TokenCache {
    suspend fun get(): AccessToken?

    suspend fun put(token: AccessToken)

    suspend fun clear()
}

class InMemoryTokenCache : TokenCache {
    @Volatile private var token: AccessToken? = null

    override suspend fun get(): AccessToken? = token

    override suspend fun put(token: AccessToken) {
        this.token = token
    }

    override suspend fun clear() {
        token = null
    }
}
