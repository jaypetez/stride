package org.stride.feature.connect

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.stride.core.common.StrideResult
import org.stride.core.data.local.DataEraser
import org.stride.core.data.sync.SyncProgress
import org.stride.core.data.sync.SyncScheduler
import org.stride.core.datastore.SecretCipher
import org.stride.core.garmin.AccessToken
import org.stride.core.garmin.GarminClient
import org.stride.core.garmin.GarminError
import org.stride.core.garmin.GarminResult
import org.stride.core.model.Activity
import org.stride.core.model.DailyWellness

/** Answers token exchanges with [result]; records the keys it was asked to validate. */
class FakeGarminClient(var result: GarminResult<AccessToken> = StrideResult.Ok(AccessToken("token", Long.MAX_VALUE))) : GarminClient {
    val exchanged = mutableListOf<String?>()
    var tokenCleared = 0

    override suspend fun exchangeToken(apiKey: String?): GarminResult<AccessToken> {
        exchanged += apiKey
        return result
    }

    override suspend fun activities(start: LocalDate, end: LocalDate): GarminResult<List<Activity>> = StrideResult.Err(
        GarminError.Http(404)
    )

    override suspend fun activityDetail(activityId: Long): GarminResult<Activity> = StrideResult.Err(GarminError.Http(404))

    override suspend fun fitFile(activityId: Long): GarminResult<ByteArray> = StrideResult.Err(GarminError.Http(404))

    override suspend fun wellness(start: LocalDate, end: LocalDate): GarminResult<List<DailyWellness>> = StrideResult.Ok(emptyList())

    override suspend fun clearToken() {
        tokenCleared++
    }
}

class FakeSync : SyncScheduler {
    var syncNowCalls = 0
    var periodicCalls = 0
    var cancelCalls = 0
    override val progress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress.Idle)

    override fun schedulePeriodic() {
        periodicCalls++
    }

    override fun syncNow() {
        syncNowCalls++
    }

    override fun cancelAll() {
        cancelCalls++
    }
}

class FakeEraser : DataEraser {
    var cleared = 0
    var wiped = 0

    override suspend fun clearSyncedData() {
        cleared++
    }

    override suspend fun wipeEverything() {
        wiped++
    }
}

object TestCipher : SecretCipher {
    override fun encrypt(plaintext: String, associatedData: String) = "enc:" + plaintext.reversed()

    override fun decrypt(ciphertext: String, associatedData: String) = ciphertext.removePrefix("enc:").reversed()
}
