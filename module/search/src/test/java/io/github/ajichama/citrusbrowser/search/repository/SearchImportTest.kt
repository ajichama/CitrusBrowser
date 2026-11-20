package io.github.ajichama.citrusbrowser.search.repository

import com.squareup.moshi.Moshi
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSettings
import io.github.ajichama.citrusbrowser.search.model.provider.SearchUrl
import io.github.ajichama.citrusbrowser.ui.utils.SearchTemplates
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SearchImportTest {
    private val moshi = Moshi.Builder().build()
    private fun item(id: Int, name: String) = SearchUrl(id, name, "https://example.org/$name?q=%s&hl=ja", 17, false)
    private fun encoded(settings: SearchSettings) = SearchImport.encode(settings, moshi)

    @Test fun readsOriginalYuzuJsonAndMissingOptionalFaviconWithoutChangingCustomUrl() {
        val old = """{"sel":8,"idc":10,"item":[{"3":8,"0":"日本語","1":"https://example.org/?q=%s&safe=active","2":17}]}"""
        val parsed = SearchImport.decode(("\uFEFF" + old).toByteArray(), moshi)
        assertEquals(8, parsed.selectedId)
        assertEquals(10, parsed.idCount)
        assertTrue(parsed.items.single().isUseFavicon)
        assertEquals("https://example.org/?q=%s&safe=active", parsed.items.single().url)
        assertEquals(parsed, SearchImport.decode(encoded(parsed), moshi))
    }

    @Test fun mergesCollidingIdsAndKeepsExistingDataOrderAndImportedSelection() {
        val current = SearchSettings(4, 12, listOf(item(4, "Citrus"), item(8, "Existing")))
        val incoming = SearchSettings(4, 9, listOf(item(4, "Imported"), item(7, "Other")))
        val currentBefore = current.copy(items = current.items.map { it.copy() })
        val incomingBefore = incoming.copy(items = incoming.items.map { it.copy() })
        val merged = SearchImport.merge(current, incoming)
        assertEquals(listOf(4, 8, 13, 7), merged.settings.items.map { it.id })
        assertEquals(13, merged.settings.selectedId)
        assertEquals(mapOf(4 to 13, 7 to 7), merged.idRemap)
        assertEquals(current.items, merged.settings.items.take(2))
        assertEquals(currentBefore, current)
        assertEquals(incomingBefore, incoming)
        assertEquals(merged.settings, SearchImport.decode(encoded(merged.settings), moshi))
    }

    @Test fun normalizesGoogleBeforeIdRemapAndReusesOnlyIdenticalEntries() {
        val google = SearchUrl(21, "Google", SearchTemplates.DEFAULT_GOOGLE_URL, 0xff2196f3.toInt(), true)
        val current = SearchSettings(8, 30, listOf(item(8, "Custom"), google))
        val oldGoogle = google.copy(id = 1, url = "https://www.google.co.jp/m?q=%s")
        val incoming = SearchSettings(1, 3, listOf(oldGoogle, item(8, "Custom"), google.copy(id = 2, color = 22)))
        val merged = SearchImport.merge(current, incoming)
        assertEquals(3, merged.settings.items.size)
        assertEquals(21, merged.settings.selectedId)
        assertEquals(mapOf(1 to 21, 8 to 8, 2 to 2), merged.idRemap)
        assertEquals(22, merged.settings.items.last().color)
        assertEquals(merged.settings, SearchImport.merge(merged.settings, incoming).settings)
    }

    @Test fun zeroSelectionUsesSourceFirstItemAndUnknownIdKeepsCurrentChoice() {
        val current = SearchSettings(40, 40, listOf(item(40, "Current")))
        val incoming = SearchSettings(0, 7, listOf(item(7, "Source")))
        assertEquals(7, SearchImport.merge(current, incoming).settings.selectedId)
        assertEquals(40, SearchImport.merge(current, incoming.copy(selectedId = 1)).settings.selectedId)
        assertEquals(40, SearchImport.merge(current, incoming.copy(items = emptyList())).settings.selectedId)
    }

    @Test fun realZeroIdTakesPriorityOverLegacyFirstItemFallback() {
        val current = SearchSettings(9, 9, listOf(item(9, "Current")))
        val incoming = SearchSettings(0, 7, listOf(item(7, "First"), item(0, "Zero")))
        val merged = SearchImport.merge(current, incoming)
        assertEquals(0, merged.settings.selectedId)
        assertEquals(0, merged.idRemap[0])
    }

    @Test fun rejectsAmbiguousMalformedOrOversizedInputWithoutChangingCurrent() {
        val malformed = listOf(
            "[]", "null", """{"sel":1,"sel":2,"idc":2,"item":[]}""",
            """{"sel":1,"idc":1,"item":[{"3":1,"0":null,"1":"%s","2":0}]}""",
            """{"sel":1,"idc":1,"item":[]} {}""",
            """{"sel":1,"idc":-1,"item":[]}""",
            """{"sel":1,"idc":1,"item":[],"deep":${"[".repeat(9)}0${"]".repeat(9)}}""",
        )
        malformed.forEach { text -> assertThrows(IOException::class.java) { SearchImport.decode(text.toByteArray(), moshi) } }
        assertThrows(IOException::class.java) { SearchImport.decode(byteArrayOf(0xc3.toByte(), 0x28), moshi) }
        assertThrows(IOException::class.java) { SearchImport.decode(ByteArray(2 * 1024 * 1024 + 1), moshi) }
        val repeated = SearchSettings(1, 2, listOf(item(1, "A"), item(1, "B")))
        assertThrows(IOException::class.java) { SearchImport.merge(SearchSettings(0, 0, emptyList()), repeated) }
    }

    @Test fun failsBeforeWritingWhenMergeExceedsCountOrIdCapacity() {
        val full = SearchSettings(1, 1024, (1..1024).map { item(it, "E$it") })
        assertThrows(IOException::class.java) { SearchImport.merge(full, SearchSettings(1, 1, listOf(item(1, "New")))) }
        val last = SearchSettings(1, Int.MAX_VALUE - 1, listOf(item(1, "Current")))
        assertThrows(IOException::class.java) { SearchImport.merge(last, SearchSettings(1, 1, listOf(item(1, "New")))) }
        assertEquals(1024, full.items.size)
        assertEquals("Current", last.items.single().title)
    }

    @Test fun decodesLegacyUiMinusOneIdsWithoutLosingCustomEntriesOrFirstSelection() {
        val raw = SearchSettings(-1, 2, listOf(item(7, "Original"), item(-1, "First"), item(-1, "Second")))
        val bytes = moshi.adapter(SearchSettings::class.java).toJson(raw).toByteArray()
        val decoded = SearchImport.decode(bytes, moshi)
        assertEquals(listOf(7, 8, 9), decoded.items.map { it.id })
        assertEquals(8, decoded.selectedId)
        assertEquals(9, decoded.idCount)
        assertEquals(raw.items.map { it.url }, decoded.items.map { it.url })
        assertEquals(listOf(7, -1, -1), raw.items.map { it.id })
        assertEquals(decoded, SearchImport.decode(encoded(decoded), moshi))
        assertSame(decoded, SearchImport.normalizeLegacyIds(decoded))
    }

    @Test fun legacyMinusOneSelectionRemapsToFirstSourceWhenItsNewIdAlsoCollides() {
        val current = SearchSettings(1, 20, listOf(item(1, "Current")))
        val incoming = SearchSettings(-1, 0, listOf(item(-1, "First"), item(-1, "Second")))
        val merged = SearchImport.merge(current, incoming)
        assertEquals(listOf("Current", "First", "Second"), merged.settings.items.map { it.title })
        assertEquals(listOf(1, 21, 2), merged.settings.items.map { it.id })
        assertEquals(mapOf(-1 to 21), merged.idRemap)
        assertEquals(21, merged.settings.selectedId)
    }
}
