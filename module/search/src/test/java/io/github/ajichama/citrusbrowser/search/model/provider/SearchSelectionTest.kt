package io.github.ajichama.citrusbrowser.search.model.provider

import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

class SearchSelectionTest {
    @Test fun spinnerPositionPersistsStableIdAcrossReloadAndReorder() {
        val providers = SearchSuggestProviders(SearchSettings(40, 40, listOf(
            SearchUrl(40, "A", "https://example.org/a?q=%s", 1),
            SearchUrl(7, "B", "https://example.org/b?q=%s", 2))))
        providers.selectIndex(1)
        val adapter = Moshi.Builder().build().adapter(SearchSettings::class.java)
        val reloaded = SearchSuggestProviders(adapter.fromJson(adapter.toJson(providers.toSettings()))!!)
        assertEquals(7, reloaded.selectedId)
        assertEquals(1, reloaded.getSelectedIndex())
        reloaded.items.reverse()
        assertEquals(0, reloaded.getSelectedIndex())
        assertEquals("B", reloaded[reloaded.getSelectedIndex()].title)
    }

    @Test fun transientInvalidSpinnerPositionsDoNotReplaceSavedSelection() {
        val providers = SearchSuggestProviders(SearchSettings(40, 40, listOf(SearchUrl(40, "A", "%s", 0))))
        providers.selectIndex(-1)
        providers.selectIndex(1)
        assertEquals(40, providers.selectedId)
        assertEquals(0, providers.getSelectedIndex())
    }
}
