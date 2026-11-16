package io.github.ajichama.citrusbrowser.legacy.pattern.action

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.github.ajichama.citrusbrowser.core.utility.utils.PageColorMode
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class WebSettingPageColorTest {
    private fun read(json: String) = JsonReader.of(Buffer().writeUtf8(json)).use { WebSettingPatternAction(it) }

    @Test fun legacyForceDarkReadsAndWritesAsAutomaticWithoutChangingJsonKey() {
        val action = read("""{"7":3}""")
        assertEquals(PageColorMode.AUTO_DARK, action.webTheme)
        val output = Buffer()
        JsonWriter.of(output).use { writer ->
            writer.beginArray()
            action.write(writer)
            writer.endArray()
        }
        assertTrue(output.readUtf8().contains("\"7\":2"))
    }

    @Test fun absentOrUndefinedSiteColorContinuesToInherit() {
        assertEquals(PageColorMode.INHERIT, read("{}").webTheme)
        assertEquals(PageColorMode.INHERIT, read("""{"7":-1}""").webTheme)
    }

    @Test fun invalidSiteValueDoesNotCreateAnOverrideOrInvalidSpinnerIndex() {
        assertEquals(PageColorMode.INHERIT, read("""{"7":4}""").webTheme)
        val action = read("""{"7":0}""")
        action.webTheme = 3
        assertEquals(2, action.webTheme)
        action.webTheme = -1
        assertEquals(-1, action.webTheme)
    }
}
