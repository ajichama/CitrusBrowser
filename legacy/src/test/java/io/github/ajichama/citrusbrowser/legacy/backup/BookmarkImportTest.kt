/*
 * Copyright (C) 2026 CitrusBrowser contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.ajichama.citrusbrowser.legacy.backup

import com.squareup.moshi.JsonReader
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkFolder
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkSite
import io.github.ajichama.citrusbrowser.bookmark.repository.BookmarkImport
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BookmarkImportTest {
    @Test fun importedSourceIdsAreMappedToFreshIdsAndNeverToAnExistingCollision() {
        val root = BookmarkFolder(null, null, -1)
        root.add(BookmarkFolder("existing", root, 10))
        val incoming = listOf(BookmarkImport.Item("folder", children = listOf(BookmarkImport.Item("site", "https://new.invalid/", sourceId = 11)), sourceId = 10))
        val result = BookmarkImport.mergedWithIds(root, incoming, "imported")
        assertEquals(setOf(10L, 11L), result.idRemap.keys)
        assertNotEquals(10L, result.idRemap.getValue(10))
        assertEquals(2, result.idRemap.values.toSet().size)
        assertEquals(1, root.size())
        val decoded = BookmarkImport.json(result.bytes).last().children!!.single()
        assertEquals(result.idRemap[10], decoded.sourceId)
        assertEquals(result.idRemap[11], decoded.children!!.single().sourceId)
    }
    private val source = """[{"0":1,"1":"旧フォルダ","3":10,"2":[{"0":2,"1":"記事","3":11,"2":"https://fixture.invalid/"}]}]"""
    @Test fun nativeNumericKeyFormatPreservesNestedTitlesUrlsAndOrder() {
        val items = BookmarkImport.json(source.toByteArray()); assertEquals("旧フォルダ", items.single().title)
        assertEquals("https://fixture.invalid/", items.single().children!!.single().url); assertEquals(2, BookmarkImport.count(items))
    }
    @Test fun keyOrderAndNullTitlesRemainReadableAndUtf8BomIsAccepted() {
        val items = BookmarkImport.json(("\uFEFF" + """[{"2":"https://example.invalid/","1":null,"0":2,"3":-1}]""").toByteArray())
        assertEquals("", items.single().title); assertEquals("https://example.invalid/", items.single().url)
    }
    @Test fun malformedTypesDuplicatesTrailingDataAndMissingUrlsAreRejected() {
        for (json in listOf("{}", """[{"0":2,"1":"bad"}]""", """[{"0":2,"0":1,"1":"bad","2":[]}]""", source + "false", """[{"0":99,"1":"bad","2":[]}]"""))
            assertThrows(Exception::class.java) { BookmarkImport.json(json.toByteArray()) }
    }
    @Test fun mergeAddsANewFolderWithFreshIdsWithoutChangingTheLiveRoot() {
        val root = BookmarkFolder(null, null, -1); root.add(BookmarkSite("keep", "https://keep.invalid/", 10))
        val merged = BookmarkImport.merged(root, BookmarkImport.json(source.toByteArray()), "imported")
        assertEquals(1, root.size()); assertEquals("keep", root[0].title)
        val decoded = BookmarkImport.json(merged); assertEquals(listOf("keep", "imported"), decoded.map { it.title })
        val ids = arrayListOf<Long>()
        JsonReader.of(Buffer().write(merged)).use { reader ->
            fun walk() { when(reader.peek()) {
                JsonReader.Token.BEGIN_ARRAY -> { reader.beginArray(); while(reader.hasNext()) walk(); reader.endArray() }
                JsonReader.Token.BEGIN_OBJECT -> { reader.beginObject(); while(reader.hasNext()) { if(reader.nextName()=="3") ids += reader.nextLong() else walk() }; reader.endObject() }
                else -> reader.skipValue()
            } }; walk()
        }
        assertEquals(4, ids.size); assertEquals(4, ids.toSet().size); assertEquals(10L, ids.first()); assertTrue(ids.drop(1).none { it == 10L || it == 11L })
    }
    @Test fun netscapeHtmlUsesTheSameIndependentImportTreeWithoutFetchingFavicons() {
        val html = """<!DOCTYPE NETSCAPE-Bookmark-file-1><TITLE>Bookmarks</TITLE><H1>Bookmarks</H1><DL><p><DT><H3>Folder</H3><DL><p><DT><A HREF="https://fixture.invalid/">日本語</A></DL><p></DL><p>"""
        val items = BookmarkImport.html(html.toByteArray()); assertEquals("Folder", items.single().title)
        assertEquals("日本語", items.single().children!!.single().title)
        assertThrows(IOException::class.java) { BookmarkImport.html("<html>ordinary page</html>".toByteArray()) }
    }
    @Test fun excessiveDepthAndBrokenUtf8AreRejectedBeforeAnyLiveMutation() {
        var json = "[]"; repeat(35) { json = """[{"0":1,"1":"deep","2":$json}]""" }
        assertThrows(IOException::class.java) { BookmarkImport.json(json.toByteArray()) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { BookmarkImport.json(byteArrayOf(0xff.toByte())) }
    }
}
