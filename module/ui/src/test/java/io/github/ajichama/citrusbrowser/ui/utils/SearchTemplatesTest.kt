package io.github.ajichama.citrusbrowser.ui.utils

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class SearchTemplatesTest {
    @Test fun knownDefaultsNormalizeAndCustomTemplatesRemainByteIdentical() {
        for (old in listOf("http://www.google.com/m?q=%s", "https://www.google.co.jp/m?q=%s")) {
            assertEquals(SearchTemplates.DEFAULT_GOOGLE_URL, SearchTemplates.normalizeLegacyDefault(old))
        }
        for (custom in listOf("https://www.google.co.jp/m?q=%s&hl=ja", "https://example.org/?term=%s", "https://www.google.com/search?q=%s&safe=active")) {
            assertEquals(custom, SearchTemplates.normalizeLegacyDefault(custom))
        }
    }

    @Test fun japaneseAndSymbolsRoundTripAsOneQueryValue() {
        val input = "日本語 🍊 &safe=off + # % ? /"
        val actual = SearchTemplates.compose("  $input  ", SearchTemplates.DEFAULT_GOOGLE_URL, "%s")
        assertEquals("https://www.google.com/search", actual.substringBefore('?'))
        val query = actual.substringAfter("?q=")
        assertFalse(query.contains('&'))
        assertFalse(query.contains('#'))
        assertEquals(input, URLDecoder.decode(query, "UTF-8"))
    }

    @Test fun customPlaceholderAndSuffixKeepExistingFirstReplacementSemantics() {
        val template = "https://example.org/?term={query}&literal={query}&lang=ja"
        assertEquals("https://example.org/?term=a%2Bb&literal={query}&lang=ja",
            SearchTemplates.compose("a+b", template, "{query}"))
        assertEquals(template, SearchTemplates.compose("a+b", template, "%s"))
    }

    @Test fun explicitUrlCanStillBeUsedAsForcedSearchText() {
        val url = "https://example.org/?x=a+b&y=%23#fragment"
        val result = SearchTemplates.compose(url, SearchTemplates.DEFAULT_GOOGLE_URL, "%s")
        assertEquals(url, URLDecoder.decode(result.substringAfter("?q="), "UTF-8"))
    }
}
