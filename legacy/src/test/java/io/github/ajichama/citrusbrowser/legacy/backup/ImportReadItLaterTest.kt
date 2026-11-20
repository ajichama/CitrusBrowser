/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.github.ajichama.citrusbrowser.legacy.backup

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportReadItLaterTest {
    @get:Rule val temp = TemporaryFolder()

    private val index = """[{"time":100,"url":"https://a.test/","title":"A"},{"time":200,"url":"https://b.test/","title":"B"},""" +
        """{"time":300,"url":"https://c.test/","title":"C"}]"""

    @Test fun pagesAreStagedAndOnlyIndexedPagesWithFilesAreImported() {
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(ImportReadItLater.INDEX)); zip.write(index.toByteArray()); zip.closeEntry()
            // 300 はページがない。999 は索引にない。
            for (name in listOf("100", "200", "999")) { zip.putNextEntry(ZipEntry("app_readItLater/$name")); zip.write("mht-$name".toByteArray()); zip.closeEntry() }
        } }.toByteArray()
        val staged = ImportArchive.stage(ByteArrayInputStream(bytes), temp.newFolder())
        val prepared = ImportReadItLater.prepare(staged.files.getValue(ImportReadItLater.INDEX).readBytes(), staged.files)
        assertEquals(listOf(100L, 200L), prepared.items.map { it.first.time })
        assertEquals("mht-200", prepared.items[1].second.readText())
        assertEquals(2, prepared.ignored)
    }

    @Test fun mergeKeepsExistingItemsSkipsSameTimeAndUrlAndRenumbersCollisions() {
        val dir = temp.newFolder()
        val files = listOf(100L, 200L, 300L).associate { ImportReadItLater.pagePath(it) to java.io.File(dir, "$it").apply { writeText("p$it") } }
        val prepared = ImportReadItLater.prepare(index.toByteArray(), files)
        val current = listOf(ImportReadItLater.Item(100, "https://a.test/", "既存A"), ImportReadItLater.Item(200, "https://other.test/", "別"))
        // 201 は既存の索引にないが、ファイルが残っている。
        val merged = ImportReadItLater.merge(current, prepared) { it == 201L }
        assertEquals(1, merged.duplicates)
        assertEquals(listOf(202L, 300L), merged.pages.map { it.second })
        val written = ImportReadItLater.read(merged.index)
        assertEquals(listOf(100L, 200L, 202L, 300L), written.map { it.time })
        assertEquals("既存A", written[0].title)
        assertEquals("https://b.test/", written[2].url)
    }

    @Test fun malformedIndexesAreRejected() {
        for (json in listOf("{}", """[{"time":"1","url":"u","title":"t"}]""", """[{"time":1,"url":"u"}]""", """[{"time":-1,"url":"u","title":"t"}]""", "[] []")) {
            assertThrows(json, IOException::class.java) { ImportReadItLater.read(json.toByteArray()) }
        }
        assertEquals(1, ImportReadItLater.read("""[{"time":1,"url":"u","title":"t","extra":[1,2]}]""".toByteArray()).size)
    }
}
