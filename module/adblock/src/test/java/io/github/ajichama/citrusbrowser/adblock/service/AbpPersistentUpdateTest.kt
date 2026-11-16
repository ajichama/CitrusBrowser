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

package io.github.ajichama.citrusbrowser.adblock.service

import android.content.Context
import io.github.ajichama.citrusbrowser.adblock.core.AbpLoader
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_DENY
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.repository.abp.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AbpPersistentUpdateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://filters.example/list.txt"
    private val context = Mockito.mock(Context::class.java)

    @Test
    fun metadataFailureRestoresThePreviousBundleAndRemovesRollbackFiles() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        store.write(1, url, decode("||old.example^"))
        val dao = MemoryDao(AbpEntity(entityId = 1, url = url, version = "old", lastLocalUpdate = 123))
        dao.beforeMetadata = { throw IOException("DB 保存失敗") }
        assertEquals(AbpUpdateOutcome.RetryableFailure, AbpUpdateService(context, client(), dao, store).updateInternal(dao.entry!!))
        assertEquals("old", dao.entry!!.version)
        assertEquals(123L, dao.entry!!.lastLocalUpdate)
        assertEquals("old.example", pattern(directory, store, dao.entry!!))
        assertClean(directory)
    }

    @Test
    fun failedFirstMetadataWriteDoesNotLeaveAnUncommittedBundle() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        val dao = MemoryDao(AbpEntity(entityId = 1, url = url))
        dao.beforeMetadata = { throw IOException("DB 保存失敗") }
        assertEquals(AbpUpdateOutcome.RetryableFailure, AbpUpdateService(context, client(), dao, store).updateInternal(dao.entry!!))
        assertNull(store.open(1))
        assertClean(directory)
    }

    @Test
    fun cancellationAfterPublicationFinishesTheSmallMetadataCommitAndCleansItsBackup() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        store.write(1, url, decode("||old.example^"))
        val dao = MemoryDao(AbpEntity(entityId = 1, url = url))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.beforeMetadata = { entered.complete(Unit); release.await() }
        val job = launch(Dispatchers.IO) { AbpUpdateService(context, client(), dao, store).updateInternal(dao.entry!!) }
        withTimeout(5_000) { entered.await() }
        job.cancel()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals("2", dao.entry!!.version)
        assertTrue(dao.entry!!.lastLocalUpdate > 0)
        assertEquals("new.example", pattern(directory, store, dao.entry!!))
        assertClean(directory)
    }

    @Test
    fun retryOfAnAlreadyCompletedManualRequestDoesNotDownloadAgain() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        val dao = MemoryDao(AbpEntity(entityId = 1, url = url))
        val count = AtomicInteger()
        val updater = AbpUpdateService(context, client(onRequest = { count.incrementAndGet() }), dao, store)
        val requestedAt = System.currentTimeMillis() - 1000
        assertEquals(AbpUpdateOutcome.Changed, updater.updateEntity(1, true, requestedAt))
        assertEquals(AbpUpdateOutcome.NotModified, updater.updateEntity(1, true, requestedAt))
        assertEquals(1, count.get())
    }

    @Test
    fun aQueuedIdLoadsItsEditedUrlAndNeverRestoresADeletedOrDisabledRow() = runBlocking {
        val store = AbpFilterStore(temporary.newFolder(), TestPublication())
        val editedUrl = "https://edited.example/list.txt"
        val dao = MemoryDao(AbpEntity(entityId = 1, url = editedUrl))
        val count = AtomicInteger()
        val updater = AbpUpdateService(context, client(onRequest = { assertEquals(editedUrl, it); count.incrementAndGet() }), dao, store)
        assertEquals(AbpUpdateOutcome.Changed, updater.updateEntity(1, true, Long.MAX_VALUE))
        assertTrue(store.isCurrent(1, editedUrl))
        dao.entry!!.enabled = false
        assertEquals(AbpUpdateOutcome.Skipped, updater.updateEntity(1, true, Long.MAX_VALUE))
        dao.entry = null
        assertEquals(AbpUpdateOutcome.Skipped, updater.updateEntity(1, true, Long.MAX_VALUE))
        assertEquals(1, count.get())
    }

    @Test
    fun aCurrent304IsSuccessfulAndPreservesItsCache() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        store.write(1, url, decode("||old.example^"))
        val dao = MemoryDao(AbpEntity(entityId = 1, url = url, lastModified = "old-date"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("old-date", chain.request().header("If-Modified-Since"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(304).message("not modified")
                .body("".toResponseBody()).build()
        }.build()
        assertEquals(AbpUpdateOutcome.NotModified, AbpUpdateService(context, client, dao, store).updateInternal(dao.entry!!))
        assertTrue(dao.entry!!.lastLocalUpdate > 0)
        assertEquals("old.example", pattern(directory, store, dao.entry!!))
    }

    @Test
    fun invalidFilterHeaderIsPermanentAndKeepsThePreviousBundle() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        store.write(1, url, decode("||old.example^"))
        val dao = MemoryDao(AbpEntity(entityId = 1, url = url))
        assertEquals(AbpUpdateOutcome.PermanentFailure,
            AbpUpdateService(context, client(body = "<html>error</html>"), dao, store).updateInternal(dao.entry!!))
        assertEquals(0L, dao.entry!!.lastLocalUpdate)
        assertEquals("old.example", pattern(directory, store, dao.entry!!))
    }

    @Test
    fun rollbackAndDiscardNeverTouchASuccessorPublication() {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        store.write(1, url, decode("||old.example^"))
        store.beginUpdate(1, url, decode("||middle.example^")).use { own ->
            store.write(1, url, decode("||successor.example^"))
            own.rollback()
            own.discard()
        }
        assertEquals("successor.example", pattern(directory, store, AbpEntity(entityId = 1, url = url)))
        assertClean(directory)
    }

    @Test
    fun silentRollbackFailureRetainsItsBackupAndASubsequentRecoveryVerifiesTheOldCommit() {
        val directory = temporary.newFolder()
        val publication = TestPublication()
        val store = AbpFilterStore(directory, publication)
        store.write(1, url, decode("||old.example^"))
        val own = store.beginUpdate(1, url, decode("||middle.example^"))
        publication.discardNext = true
        assertThrows(IOException::class.java) { own.rollback() }
        own.close()
        assertTrue(directory.listFiles()!!.any { it.extension == "rollback" })
        own.rollback()
        own.close()
        assertEquals("old.example", pattern(directory, store, AbpEntity(entityId = 1, url = url)))
        assertClean(directory)
    }

    @Test
    fun urlEditCleanupRemovesLegacyRootsButPreservesANewUrlPublishedByAnotherWorker() {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        File(directory, "b_1").writeText("旧 root cache")
        val editedUrl = "https://edited.example/list.txt"
        store.write(1, editedUrl, decode("||new.example^"))
        store.invalidatePreviousSource(1, url)
        assertFalse(File(directory, "b_1").exists())
        assertTrue(store.isCurrent(1, editedUrl))
        assertEquals("new.example", pattern(directory, store, AbpEntity(entityId = 1, url = editedUrl)))
    }

    private fun pattern(directory: File, store: AbpFilterStore, entity: AbpEntity) =
        AbpLoader(directory, listOf(entity), store).use { it.loadAll(ABP_PREFIX_DENY).single().pattern }

    private fun assertClean(directory: File) = assertFalse(directory.listFiles()!!.any { it.extension in setOf("tmp", "rollback") })
    private fun decode(rules: String) = AbpFilterDecoder().decode(rules.reader().buffered(), url)
    private fun client(body: String = "[Adblock Plus 2.0]\n! Version: 2\n||new.example^", onRequest: (String) -> Unit = {}) =
        OkHttpClient.Builder().addInterceptor { chain ->
            onRequest(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body.toResponseBody()).build()
        }.build()

    private class MemoryDao(var entry: AbpEntity?) : AbpDao {
        var beforeMetadata: suspend () -> Unit = {}
        override suspend fun getAll() = listOfNotNull(entry)
        override suspend fun getById(id: Int) = entry?.takeIf { it.entityId == id }
        override suspend fun inset(abpEntity: AbpEntity): Long = error("再挿入は禁止です。")
        override suspend fun inset(entities: List<AbpEntity>) = error("再挿入は禁止です。")
        override suspend fun delete(abpEntity: AbpEntity) { entry = null }
        override suspend fun update(abpEntity: AbpEntity) = error("全列上書きは禁止です。")
        override suspend fun setEnabled(id: Int, enabled: Boolean): Int {
            val current = getById(id) ?: return 0
            current.enabled = enabled
            return 1
        }
        override suspend fun updateUrl(id: Int, url: String): Int {
            val current = getById(id) ?: return 0
            current.url = url
            return 1
        }
        override suspend fun markChecked(id: Int, expectedUrl: String, time: Long): Int {
            val current = getById(id)?.takeIf { it.url == expectedUrl && it.enabled } ?: return 0
            current.lastLocalUpdate = time
            return 1
        }
        override suspend fun updateMetadata(id: Int, expectedUrl: String, title: String?, lastUpdate: String?, lastLocalUpdate: Long,
                                            expires: Int, version: String?, homePage: String?, lastModified: String?): Int {
            beforeMetadata()
            val current = getById(id)?.takeIf { it.url == expectedUrl && it.enabled } ?: return 0
            current.title = title
            current.lastUpdate = lastUpdate
            current.lastLocalUpdate = lastLocalUpdate
            current.expires = expires
            current.version = version
            current.homePage = homePage
            current.lastModified = lastModified
            return 1
        }
    }
}
