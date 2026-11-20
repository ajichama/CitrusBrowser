package io.github.ajichama.citrusbrowser.search.repository

import com.squareup.moshi.Moshi
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSettings
import io.github.ajichama.citrusbrowser.search.model.provider.SearchUrl
import io.github.ajichama.citrusbrowser.ui.utils.SearchTemplates
import org.junit.Assert.*
import org.junit.Test

class SearchDefaultsTest {
    private fun seed(url: String = "https://www.google.co.jp/m?q=%s") =
        SearchUrl(1, "Google", url, 0xff2196f3.toInt(), true)

    @Test fun migratesOnlyOriginalSeedWithoutChangingSelectionOrderOrIdentity() {
        for (old in listOf("http://www.google.com/m?q=%s", "https://www.google.co.jp/m?q=%s")) {
            val custom = SearchUrl(7, "Custom", "https://example.org/?q=%s", 42, false)
            val original = SearchSettings(7, 12, listOf(custom, seed(old)))
            val actual = SearchDefaults.normalize(original)
            assertEquals(7, actual.selectedId)
            assertEquals(12, actual.idCount)
            assertEquals(listOf(custom, seed(SearchTemplates.DEFAULT_GOOGLE_URL)), actual.items)
            assertEquals(old, original.items[1].url)
            assertSame(actual, SearchDefaults.normalize(actual))
        }
    }

    @Test fun preservesEditedGoogleAndCustomTemplates() {
        val entries = listOf(seed().copy(id = 9), seed().copy(title = "My Google"),
            seed().copy(color = 123), seed().copy(isUseFavicon = false),
            seed().copy(url = "https://www.google.co.jp/m?q=%s&safe=active"))
        val original = SearchSettings(9, 9, entries)
        assertSame(original, SearchDefaults.normalize(original))
    }

    @Test fun migrationPreservesPersistedJsonKeysAndRoundTrips() {
        val adapter = Moshi.Builder().build().adapter(SearchSettings::class.java)
        val saved = """{"sel":1,"idc":3,"item":[{"3":1,"0":"Google","1":"http://www.google.com/m?q=%s","2":-14575885,"4":true}]}"""
        val normalized = SearchDefaults.normalize(adapter.fromJson(saved)!!)
        assertEquals(SearchTemplates.DEFAULT_GOOGLE_URL, normalized.items.single().url)
        val output = adapter.toJson(normalized)
        assertTrue(output.contains("\"sel\":1"))
        assertTrue(output.contains("\"idc\":3"))
        assertTrue(output.contains("\"4\":true"))
        assertEquals(normalized, adapter.fromJson(output))
    }
}
