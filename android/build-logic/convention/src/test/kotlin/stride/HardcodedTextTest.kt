package stride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardcodedTextTest {
    private fun findings(source: String) = CheckHardcodedTextTask.findingsIn(source.trimIndent().lines())

    @Test
    fun `flags a literal passed on the same line`() {
        assertEquals("\"Hello world\"", CheckHardcodedTextTask.hardcodedTextIn("""Text("Hello world")"""))
        assertEquals("\"Save\"", CheckHardcodedTextTask.hardcodedTextIn("""    title = "Save","""))
        assertEquals("\"Pace\"", CheckHardcodedTextTask.hardcodedTextIn("""    "Pace" to value,"""))
    }

    @Test
    fun `ignores resources, templates, comments and opted-out lines`() {
        assertNull(CheckHardcodedTextTask.hardcodedTextIn("""Text(stringResource(R.string.save))"""))
        assertNull(CheckHardcodedTextTask.hardcodedTextIn("""Text("${'$'}{km} · ${'$'}pace")"""))
        assertNull(CheckHardcodedTextTask.hardcodedTextIn("""// Text("Hello world")"""))
        assertNull(CheckHardcodedTextTask.hardcodedTextIn("""Text("Garmin") // i18n-ignore"""))
        assertNull(CheckHardcodedTextTask.hardcodedTextIn("""Text(" · ")"""))
    }

    @Test
    fun `flags a literal wrapped onto the line after the call`() {
        val result = findings(
            """
            Text(
                "Your key is stored encrypted on this device.",
                style = MaterialTheme.typography.bodySmall
            )
            """
        )
        assertEquals(listOf(2 to "\"Your key is stored encrypted on this device.\""), result)
    }

    @Test
    fun `flags a literal wrapped onto the line after a text parameter`() {
        val result = findings(
            """
            EmptyState(
                message =
                    "Setup takes about two minutes."
            )
            """
        )
        assertEquals(listOf(3 to "\"Setup takes about two minutes.\""), result)
    }

    @Test
    fun `flags wrapped literals for every text composable`() {
        val result = findings(
            """
            StrideButton(
                "Connect",
                onClick = {}
            )
            Triple(
                "Easy",
                1,
                2
            )
            """
        )
        assertEquals(listOf(2 to "\"Connect\"", 6 to "\"Easy\""), result)
    }

    @Test
    fun `wrapped literals honour i18n-ignore and skip non-text calls`() {
        val result = findings(
            """
            Text(
                "Strava", // i18n-ignore
            )
            Log.d(
                "Tag",
            )
            Text(
                stringResource(R.string.save)
            )
            """
        )
        assertEquals(emptyList<Pair<Int, String>>(), result)
    }
}
