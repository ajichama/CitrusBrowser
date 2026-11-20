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

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun stage(bytes: ByteArray) = ImportArchive.stage(ByteArrayInputStream(bytes), temp.newFolder())

    @Test fun settingsArchiveClassifiesOnlyKnownFilesWithoutExtractingUnknownDatabases() {
        val result = stage(archive(ImportArchive.PREFERENCES to "<map/>".toByteArray(), ImportArchive.BOOKMARK to "[]".toByteArray(),
            "app_action1_custombar/513.dat" to "[]".toByteArray(), "databases/unknown.db" to "private".toByteArray()))
        assertEquals(setOf(ImportArchive.PREFERENCES, ImportArchive.BOOKMARK, "app_action1_custombar/513.dat"), result.files.keys)
        assertEquals(1, result.ignored)
        assertEquals("[]", result.files.getValue(ImportArchive.BOOKMARK).readText())
    }
    @Test fun standaloneFormatsAndDialZipAreDetectedByContents() {
        assertEquals(setOf(ImportArchive.BOOKMARK), stage("[]".toByteArray()).files.keys)
        assertEquals(setOf(ImportArchive.PREFERENCES), stage("<map/>".toByteArray()).files.keys)
        assertEquals(setOf(ImportArchive.HTML), stage("<!DOCTYPE NETSCAPE-Bookmark-file-1><DL/>".toByteArray()).files.keys)
        assertEquals(setOf(ImportArchive.DIAL), stage("SQLite format 3\u0000".toByteArray()).files.keys)
        assertEquals(setOf(ImportArchive.DIAL), stage(archive("speeddial1.db" to "SQLite format 3\u0000".toByteArray())).files.keys)
    }
    @Test fun pathsAndAliasedDuplicatesAreRejectedWithoutChangingExistingFiles() {
        val original = temp.newFile("existing").apply { writeText("keep") }
        for (name in listOf("../existing", "/existing", "shared_prefs/../existing", "C:/existing", "app_bookmark1\\bookmark1.dat")) {
            assertThrows(IOException::class.java) { stage(archive(name to "changed".toByteArray())) }
            assertEquals("keep", original.readText())
        }
        assertThrows(IOException::class.java) { stage(archive("speeddial1.db" to byteArrayOf(1), ImportArchive.DIAL to byteArrayOf(2))) }
    }
    @Test fun duplicateCentralNamesAndTruncatedCentralDirectoriesAreRejected() {
        val zip = archive(ImportArchive.BOOKMARK to "[]".toByteArray(), "app_bookmark1/bookmark2.dat" to "[]".toByteArray())
        val duplicate = zip.toString(Charsets.ISO_8859_1).replace("bookmark2.dat", "bookmark1.dat").toByteArray(Charsets.ISO_8859_1)
        assertThrows(IOException::class.java) { stage(duplicate) }
        assertThrows(IOException::class.java) { stage(zip.copyOf(zip.size - 8)) }
    }
    @Test fun shortReadsDoNotPreventValidArchiveDetection() {
        val bytes = archive(ImportArchive.BOOKMARK to "[]".toByteArray())
        val input = object : ByteArrayInputStream(bytes) { override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(1, length)) }
        assertEquals("[]", ImportArchive.stage(input, temp.newFolder()).files.getValue(ImportArchive.BOOKMARK).readText())
    }
    @Test fun directoryPayloadIsRejectedAndCompressedUnknownPayloadIsStillBounded() {
        assertThrows(IOException::class.java) { stage(archive("cache/" to byteArrayOf(1))) }
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(ImportArchive.BOOKMARK)); zip.write("[]".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("cache/data")); repeat(129) { zip.write(ByteArray(8192)) }; zip.closeEntry()
        } }.toByteArray()
        // 取り出さない項目も、展開した合計は上限で止める。
        val limits = ImportArchive.Limits(skippedTotalBytes = 1024L * 1024)
        assertThrows(IOException::class.java) { ImportArchive.stage(ByteArrayInputStream(bytes), temp.newFolder(), limits) }
        assertEquals(setOf(ImportArchive.BOOKMARK), stage(bytes).files.keys)
    }
    @Test fun aFullYuzuDataBackupStagesOnlyTheKnownSmallFiles() {
        // 本物の Yuzu の書き出しは、データ領域をまるごと含む（多数のファイル、32MiB を超える取り出さない項目、名前に「:」）。
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(ImportArchive.PREFERENCES)); zip.write("<map></map>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(ImportArchive.BOOKMARK)); zip.write("[]".toByteArray()); zip.closeEntry()
            repeat(600) { zip.putNextEntry(ZipEntry("app_readItLater/$it")); zip.write(ByteArray(64)); zip.closeEntry() }
            zip.putNextEntry(ZipEntry("shared_prefs/com.example.app:profile.xml")); zip.write("<map/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("app_webview/cache/data")); repeat(4097) { zip.write(ByteArray(8192)) }; zip.closeEntry()
        } }.toByteArray()
        val result = stage(bytes)
        assertEquals(setOf(ImportArchive.PREFERENCES, ImportArchive.BOOKMARK), result.files.keys)
        assertEquals(602, result.ignored)
    }
    @Test fun centralEntryLimitIsAppliedBeforeStagingAnyPayload() {
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(ImportArchive.BOOKMARK)); zip.write("[]".toByteArray()); zip.closeEntry()
            repeat(512) { zip.putNextEntry(ZipEntry("unknown-$it")); zip.closeEntry() }
        } }.toByteArray()
        val directory = temp.newFolder()
        assertThrows(IOException::class.java) { ImportArchive.stage(ByteArrayInputStream(bytes), directory, ImportArchive.Limits(entries = 512)) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun crcMismatchAndJavaBinaryAreRejected() {
        val body = "unique-marker".toByteArray()
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            val entry = ZipEntry(ImportArchive.BOOKMARK).apply { method = ZipEntry.STORED; size = body.size.toLong(); compressedSize = size; crc = CRC32().apply { update(body) }.value }
            zip.putNextEntry(entry); zip.write(body); zip.closeEntry()
        } }.toByteArray().toString(Charsets.ISO_8859_1).replace("unique-marker", "unique-markes").toByteArray(Charsets.ISO_8859_1)
        assertThrows(IOException::class.java) { stage(bytes) }
        assertThrows(IOException::class.java) { stage(byteArrayOf(0xac.toByte(), 0xed.toByte(), 0, 5)) }
    }
}