package org.stride.feature.settings

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.stride.core.common.Dispatcher
import org.stride.core.common.StrideDispatchers

/** Writes text to a document the user picked with the Storage Access Framework. */
class DocumentWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(StrideDispatchers.IO) private val io: CoroutineDispatcher
) {
    suspend fun write(uri: Uri, text: String) = withContext(io) {
        val stream = checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Could not open the export file" }
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
