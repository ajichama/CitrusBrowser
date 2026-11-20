package io.github.ajichama.citrusbrowser.core.utility.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSchemeCompatTest {
    @Test
    fun oldSchemeIsNormalizedWithoutChangingItsBody() {
        assertEquals("citrus://host/検索?q=yuzu%3Avalue#Yuzu:text", BrowserSchemeCompat.normalizeUrl("YuZu://host/検索?q=yuzu%3Avalue#Yuzu:text"))
        assertEquals("citrus:speeddial", BrowserSchemeCompat.normalizeUrl("yuzu:speeddial"))
    }

    @Test
    fun otherSchemesAndExistingCitrusUrlsAreKeptExactly() {
        listOf("CiTrUs://download", "https://example.com/yuzu:test", "data:text/plain,yuzu:test", "yuzufile:text", "yuzu").forEach {
            assertEquals(it, BrowserSchemeCompat.normalizeUrl(it))
        }
        assertNull(BrowserSchemeCompat.normalizeScheme(null))
        assertEquals("citrus", BrowserSchemeCompat.normalizeScheme("YUZU"))
        assertEquals("HTTPS", BrowserSchemeCompat.normalizeScheme("HTTPS"))
    }

    @Test
    fun sharedPatternMatchesBothSupportedInternalSchemes() {
        val pattern = Regex("${BrowserSchemeCompat.SCHEME_PATTERN}:.*", RegexOption.IGNORE_CASE)
        assertTrue(pattern.matches("yuzu:speeddial"))
        assertTrue(pattern.matches("CITRUS:speeddial"))
    }
}
