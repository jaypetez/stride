package org.stride.feature.settings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Writing the export through the Storage Access Framework (AUD-038). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun writesUtf8TextToTheDocument() = runTest {
        val uri = Uri.parse("content://org.stride.test/export.json")
        val sink = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, sink)
        DocumentWriter(context, StandardTestDispatcher(testScheduler)).write(uri, "{\"name\":\"Zoë\"}")
        assertEquals("{\"name\":\"Zoë\"}", sink.toString(Charsets.UTF_8))
    }

    @Test
    fun truncatesAnExistingFile() = runTest {
        val file = tmp.newFile("export.json").apply { writeText("an older, much longer export") }
        DocumentWriter(context, StandardTestDispatcher(testScheduler)).write(Uri.fromFile(file), "new")
        assertEquals("new", file.readText())
    }

    @Test
    fun failsWhenTheDocumentCannotBeOpened() = runTest {
        val missing = Uri.fromFile(tmp.root.resolve("no-such-dir/export.json"))
        assertFailsWith<Exception> { DocumentWriter(context, StandardTestDispatcher(testScheduler)).write(missing, "x") }
    }
}
