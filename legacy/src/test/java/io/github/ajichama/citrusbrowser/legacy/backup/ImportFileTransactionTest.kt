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
import java.io.File
import java.io.IOException

class ImportFileTransactionTest {
    @get:Rule val temp = TemporaryFolder()
    private class Publication : ImportFileTransaction.Publication {
        var writes = 0
        var failAt = -1
        var discardAt = -1
        override fun write(target: File, bytes: ByteArray) {
            writes++
            if (writes == failAt) throw IOException("保存失敗")
            if (writes != discardAt) target.writeBytes(bytes)
        }
        override fun delete(target: File) { target.delete() }
    }
    @Test fun failureAfterApplyingPreferencesRestoresFilesAndRemovesNewFiles() {
        val old = temp.newFile().apply { writeText("original") }; val fresh = File(temp.root, "fresh")
        val tx = ImportFileTransaction(temp.newFolder(), Publication())
        assertThrows(IOException::class.java) { tx.execute(mapOf(old to "changed".toByteArray(), fresh to "added".toByteArray())) { throw IOException("後続処理失敗") } }
        assertEquals("original", old.readText()); assertFalse(fresh.exists()); assertFalse(tx.recoveryRequired)
    }
    @Test fun failureInTheSecondPublicationRestoresTheFirstOne() {
        val a = temp.newFile().apply { writeText("a") }; val b = temp.newFile().apply { writeText("b") }
        val tx = ImportFileTransaction(temp.newFolder(), Publication().apply { failAt = 2 })
        assertThrows(IOException::class.java) { tx.execute(mapOf(a to byteArrayOf(1), b to byteArrayOf(2))) {} }
        assertEquals("a", a.readText()); assertEquals("b", b.readText()); assertFalse(tx.recoveryRequired)
    }
    @Test fun anotherWriterIsPreservedAndRecoveryCopiesAreRetained() {
        val target = temp.newFile().apply { writeText("original") }; val recovery = temp.newFolder()
        val tx = ImportFileTransaction(recovery, Publication())
        assertThrows(IOException::class.java) { tx.execute(mapOf(target to "import".toByteArray())) { target.writeText("other writer"); throw IOException("後続失敗") } }
        assertEquals("other writer", target.readText()); assertTrue(tx.recoveryRequired)
        assertEquals("original", File(recovery, "before-0.bin").readText())
    }
    @Test fun silentRollbackFailureIsDetectedAndKeepsTheOriginalCopy() {
        val target = temp.newFile().apply { writeText("original") }; val recovery = temp.newFolder()
        val tx = ImportFileTransaction(recovery, Publication().apply { discardAt = 2 })
        assertThrows(IOException::class.java) { tx.execute(mapOf(target to "import".toByteArray())) { throw IOException("後続失敗") } }
        assertTrue(tx.recoveryRequired); assertEquals("original", File(recovery, "before-0.bin").readText())
    }
    @Test fun successfulTransactionReturnsItsResultAndCommitsAllFiles() {
        val target = temp.newFile().apply { writeText("old") }; val tx = ImportFileTransaction(temp.newFolder(), Publication())
        assertEquals(42, tx.execute(mapOf(target to "new".toByteArray())) { 42 })
        assertEquals("new", target.readText()); assertFalse(tx.recoveryRequired)
    }
    @Test fun changeAfterMergeReadIsPreservedWithoutAnyPublication() {
        val target = temp.newFile().apply { writeText("other writer") }
        val publication = Publication()
        val tx = ImportFileTransaction(temp.newFolder(), publication)
        assertThrows(IOException::class.java) { tx.execute(mapOf(target to "merged".toByteArray()), mapOf(target to "read earlier".toByteArray())) {} }
        assertEquals("other writer", target.readText()); assertEquals(0, publication.writes); assertFalse(tx.recoveryRequired)
    }
    @Test fun changeBetweenSnapshotAndPublishIsNotAddedToRollbackTargets() {
        val target = temp.newFile().apply { writeText("original") }; val later = temp.newFile()
        var writes = 0
        val publication = object : ImportFileTransaction.Publication {
            override fun recover(file: File): File? { if (file == later) target.writeText("other writer"); return file }
            override fun write(file: File, bytes: ByteArray) { writes++; file.writeBytes(bytes) }
            override fun delete(file: File) { fail("他者のファイルを消してはいけません。") }
        }
        val tx = ImportFileTransaction(temp.newFolder(), publication)
        assertThrows(IOException::class.java) { tx.execute(linkedMapOf(target to "import".toByteArray(), later to byteArrayOf(1))) {} }
        assertEquals("other writer", target.readText()); assertEquals(0, writes); assertFalse(tx.recoveryRequired)
    }
}
