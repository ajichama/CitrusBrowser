package io.github.ajichama.citrusbrowser.search.model.suggest

import com.squareup.moshi.JsonReader
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class SuggestGoogleTest {
    @Test fun queryUsesQAndUtf8WithoutInjectingExtraParameters() {
        val query = "日本語 🍊 & + # % ? /"
        val url = SuggestGoogle().getUrl(query)
        val parameters = url.query.split('&').associate {
            val pair = it.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("https", url.protocol)
        assertEquals("suggestqueries.google.com", url.host)
        assertEquals(query, parameters["q"])
        assertFalse(parameters.containsKey("qu"))
        assertEquals("firefox", parameters["client"])
        assertEquals(setOf("q", "client", "oe", "hl"), parameters.keys)
    }

    @Test fun readsFirefoxSuggestionsWithJapaneseAndEscapedSymbols() {
        val json = """["citrus",["柑橘ブラウザ","a & b","quote\""],[],{"ignored":true}]"""
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        reader.use {
            assertEquals(listOf("柑橘ブラウザ", "a & b", "quote\""), SuggestGoogle().getSuggestions(it).map { item -> item.suggest })
        }
    }
}
