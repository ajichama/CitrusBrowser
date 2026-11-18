package io.github.ajichama.citrusbrowser.adblock.service

import android.content.Context
import android.content.SharedPreferences
import io.github.ajichama.citrusbrowser.adblock.core.AbpLoader
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_DENY
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterPreprocessor
import io.github.ajichama.citrusbrowser.adblock.repository.abp.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

class AbpUpdateServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://filters.example/list.txt"
    private val context = Mockito.mock(Context::class.java)

    @Test
    fun httpErrorsDoNotReplaceCacheOrRecordSuccessAndCloseTheResponse() = runBlocking {
        val dir = temporary.newFolder()
        val store = AbpFilterStore(dir, TestPublication())
        store.write(1, url, decode("||old.example^"))
        val entity = AbpEntity(entityId = 1, url = url, version = "old", lastLocalUpdate = 123)
        val dao = FakeDao(entity)
        var bodyClosed = false
        val bytes = "[Adblock Plus 2.0]\n||error.example^".toByteArray()
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() { bodyClosed = true; super.close() }
        }.source().buffer()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = bytes.size.toLong()
            override fun source() = input
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("failed").body(body).build()
        }.build()
        assertEquals(AbpUpdateOutcome.RetryableFailure, AbpUpdateService(context, client, dao, store).updateInternal(entity))
        assertTrue(bodyClosed)
        assertEquals(0, dao.metadataWrites)
        assertEquals(123L, entity.lastLocalUpdate)
        AbpLoader(dir, listOf(entity), store).use { assertEquals("old.example", it.loadAll(ABP_PREFIX_DENY).single().pattern) }
    }

    @Test
    fun storageFailureDoesNotChangeDatabaseOrCallerMetadata() = runBlocking {
        val dir = temporary.newFolder()
        val publication = TestPublication()
        val store = AbpFilterStore(dir, publication)
        store.write(1, url, decode("||old.example^"))
        publication.failNext = true
        val entity = AbpEntity(entityId = 1, url = url, title = "old", version = "old", lastLocalUpdate = 123)
        val dao = FakeDao(entity)
        val updater = AbpUpdateService(context, client(), dao, store)
        assertEquals(AbpUpdateOutcome.RetryableFailure, updater.updateInternal(entity))
        assertEquals(0, dao.metadataWrites)
        assertEquals("old", entity.title)
        assertEquals("old", entity.version)
        assertEquals(123L, entity.lastLocalUpdate)
        AbpLoader(dir, listOf(entity), store).use { assertEquals("old.example", it.loadAll(ABP_PREFIX_DENY).single().pattern) }
    }

    @Test
    fun legacyCacheDoesNotSendConditionalRequestOrAccept304AsARebuild() = runBlocking {
        val store = AbpFilterStore(temporary.newFolder(), TestPublication())
        val entity = AbpEntity(entityId = 1, url = url, lastModified = "old-date", lastLocalUpdate = 123)
        val dao = FakeDao(entity)
        var conditional: String? = "未確認"
        val noChange = OkHttpClient.Builder().addInterceptor { chain ->
            conditional = chain.request().header("If-Modified-Since")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(304).message("not modified").body("".toResponseBody()).build()
        }.build()
        assertEquals(AbpUpdateOutcome.RetryableFailure, AbpUpdateService(context, noChange, dao, store).updateInternal(entity))
        assertNull(conditional)
        assertEquals(0, dao.checkedWrites)
        assertEquals(123L, entity.lastLocalUpdate)
        assertFalse(store.isCurrent(1, url))
    }

    @Test
    fun successfulRefreshDoesNotReenableASubscriptionDisabledDuringDownload() = runBlocking {
        val store = AbpFilterStore(temporary.newFolder(), TestPublication())
        val persisted = AbpEntity(entityId = 1, url = url)
        val caller = AbpEntity(entityId = 1, url = url)
        val dao = FakeDao(persisted).apply { beforeMetadata = { persisted.enabled = false } }
        assertEquals(AbpUpdateOutcome.Skipped, AbpUpdateService(context, client(), dao, store).updateInternal(caller))
        assertFalse(persisted.enabled)
        assertFalse(caller.enabled)
        assertEquals(0, dao.metadataWrites)
        assertNull(persisted.version)
    }

    @Test
    fun changedOrDeletedSubscriptionIsNeverRestoredByAStaleRefresh() = runBlocking {
        for (deleted in listOf(false, true)) {
            val dir = temporary.newFolder()
            val store = AbpFilterStore(dir, TestPublication())
            val persisted = AbpEntity(entityId = 1, url = url)
            val caller = AbpEntity(entityId = 1, url = url)
            val dao = FakeDao(persisted)
            dao.beforeMetadata = {
                if (deleted) dao.entry = null else persisted.url = "https://other.example/list.txt"
            }
            assertEquals(AbpUpdateOutcome.Skipped, AbpUpdateService(context, client(), dao, store).updateInternal(caller))
            assertEquals(0, dao.metadataWrites)
            assertEquals(0L, caller.lastLocalUpdate)
            if (deleted) assertNull(dao.entry) else {
                assertEquals("https://other.example/list.txt", dao.entry!!.url)
                AbpLoader(dir, listOf(persisted), store).use { assertFalse(it.loadAll(ABP_PREFIX_DENY).any()) }
            }
        }
    }

    @Test
    fun assetVersionComesFromItsHeaderAndEmptyCategoriesDoNotTriggerAnotherDecode() = runBlocking {
        val assetUrl = "citrus://adblock/filter"
        val store = AbpFilterStore(temporary.newFolder(), TestPublication())
        val entity = AbpEntity(entityId = 1, url = assetUrl)
        val dao = FakeDao(entity)
        var version = "1"
        val updater = AbpUpdateService(context, client(), dao, store) {
            "[Adblock Plus 2.0]\n! Version: $version\n! Title: 内蔵\n".reader().buffered()
        }
        assertEquals(AbpUpdateOutcome.Changed, updater.updateInternal(entity))
        assertEquals(AbpUpdateOutcome.NotModified, updater.updateInternal(entity))
        assertEquals(1, dao.metadataWrites)
        version = "2"
        assertEquals(AbpUpdateOutcome.Changed, updater.updateInternal(entity))
        assertTrue(store.isCurrent(1, assetUrl, "2"))
    }

    @Test
    fun futureScheduleDoesNotPreventTheFirstParserRebuild() = runBlocking {
        val prefs = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java)
        Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
        Mockito.`when`(prefs.getLong(Mockito.anyString(), Mockito.anyLong())).thenReturn(Long.MAX_VALUE)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(editor.putLong(Mockito.anyString(), Mockito.anyLong())).thenReturn(editor)
        val store = AbpFilterStore(temporary.newFolder(), TestPublication())
        val entity = AbpEntity(entityId = 1, url = url, lastLocalUpdate = System.currentTimeMillis(), expires = 48)
        val dao = FakeDao(entity)
        val updater = AbpUpdateService(context, client(), dao, store)
        assertEquals(listOf(entity), updater.dueEntities())
        updater.updateEntity(entity.entityId, false, Long.MAX_VALUE)
        assertEquals(1, dao.metadataWrites)
        assertTrue(store.isCurrent(1, url))
    }

    @Test
    fun anIncludedListIsFetchedAgainEvenWhenOnlyTheChildChanges() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        val entity = AbpEntity(entityId = 1, url = url)
        val dao = FakeDao(entity)
        var childRule = "||first.example^"
        var rootRequests = 0
        var childRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val root = chain.request().url.toString() == url
            assertNull(chain.request().header("If-Modified-Since"))
            if (root) rootRequests++ else childRequests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .header("Last-Modified", "Wed, 18 Nov 2026 00:00:00 GMT")
                .body((if (root) "[Adblock Plus 2.0]\n! Title: 親\n!#include child.txt" else "! Title: 断片\n$childRule").toResponseBody()).build()
        }.build()
        val updater = AbpUpdateService(context, client, dao, store)
        assertEquals(AbpUpdateOutcome.Changed, updater.updateInternal(entity))
        assertNull(entity.lastModified)
        assertEquals("親", entity.title)
        childRule = "||second.example^"
        assertEquals(AbpUpdateOutcome.Changed, updater.updateInternal(entity))
        assertEquals(2, rootRequests)
        assertEquals(2, childRequests)
        AbpLoader(directory, listOf(entity), store).use {
            assertEquals("second.example", it.loadAll(ABP_PREFIX_DENY).single().pattern)
        }
    }

    @Test
    fun includesUseTheFinalRootResponseUrlWithoutChangingTheSubscriptionIdentity() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        val entity = AbpEntity(entityId = 1, url = url)
        val dao = FakeDao(entity)
        val finalRoot = "https://cdn.example/published/filters/main.txt"
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val requestedUrl = chain.request().url.toString()
            requests += requestedUrl
            val root = requestedUrl == url
            // OkHttp が転送を処理した後に返す、最終取得先を持った応答を再現する。
            val responseRequest = if (root) chain.request().newBuilder().url(finalRoot).build() else chain.request()
            Response.Builder().request(responseRequest).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body((if (root) "[Adblock Plus 2.0]\n!#include child.txt" else "||cdn-child.example^").toResponseBody()).build()
        }.build()
        assertEquals(AbpUpdateOutcome.Changed, AbpUpdateService(context, client, dao, store).updateInternal(entity))
        assertEquals(listOf(url, "https://cdn.example/published/filters/child.txt"), requests)
        assertEquals(url, entity.url)
        assertTrue(store.isCurrent(1, url))
        assertFalse(store.isCurrent(1, finalRoot))
        AbpLoader(directory, listOf(entity), store).use { assertEquals("cdn-child.example", it.loadAll(ABP_PREFIX_DENY).single().pattern) }
    }

    @Test
    fun childFailureLeavesThePublishedBundleAndMetadataUntouched() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        store.write(1, url, decode("||old.example^"))
        val entity = AbpEntity(entityId = 1, url = url, version = "old", lastLocalUpdate = 123)
        val dao = FakeDao(entity)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val root = chain.request().url.toString() == url
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (root) 200 else 503).message("response")
                .body((if (root) "[Adblock Plus 2.0]\n! Version: new\n||partial.example^\n!#include child.txt" else "unavailable").toResponseBody()).build()
        }.build()
        assertEquals(AbpUpdateOutcome.RetryableFailure, AbpUpdateService(context, client, dao, store).updateInternal(entity))
        assertEquals(0, dao.metadataWrites)
        assertEquals("old", entity.version)
        assertEquals(123L, entity.lastLocalUpdate)
        AbpLoader(directory, listOf(entity), store).use { assertEquals("old.example", it.loadAll(ABP_PREFIX_DENY).single().pattern) }
    }

    @Test
    fun anOversizedFirstHeaderLineCannotBypassTheInputLimit() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        store.write(1, url, decode("||old.example^"))
        val entity = AbpEntity(entityId = 1, url = url, lastLocalUpdate = 123)
        val dao = FakeDao(entity)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            // ヘッダーを先にreadLineすると、この長い行だけを消費して後続の小さい本文が通ってしまう。
            val text = "[Adblock Plus 2.0 " + "a".repeat(AbpFilterPreprocessor.MAX_CHARACTERS) + "]\n||partial.example^"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(text.toResponseBody()).build()
        }.build()
        assertEquals(AbpUpdateOutcome.RetryableFailure, AbpUpdateService(context, client, dao, store).updateInternal(entity))
        assertEquals(0, dao.metadataWrites)
        assertEquals(123L, entity.lastLocalUpdate)
        AbpLoader(directory, listOf(entity), store).use { assertEquals("old.example", it.loadAll(ABP_PREFIX_DENY).single().pattern) }
    }

    @Test
    fun aChildRedirectOutsideTheDirectoryIsRejectedBeforeRequestingItsDestination() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        val source = "https://filters.example/lists/main.txt"
        val entity = AbpEntity(entityId = 1, url = source)
        val dao = FakeDao(entity)
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            val root = requests.size == 1
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (root) 200 else 302).message("response")
                .header("Location", "../outside.txt")
                .body((if (root) "[Adblock Plus 2.0]\n!#include child.txt" else "").toResponseBody()).build()
        }.build()
        assertEquals(AbpUpdateOutcome.RetryableFailure, AbpUpdateService(context, client, dao, store).updateInternal(entity))
        assertEquals(listOf(source, "https://filters.example/lists/child.txt"), requests)
        assertEquals(0, dao.metadataWrites)
        assertNull(store.open(1))
    }

    @Test
    fun cancellationDuringTheChildRequestDoesNotPublishPartialRules() = runBlocking {
        val directory = temporary.newFolder()
        val store = AbpFilterStore(directory, TestPublication())
        store.write(1, url, decode("||old.example^"))
        val entity = AbpEntity(entityId = 1, url = url, lastLocalUpdate = 123)
        val dao = FakeDao(entity)
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val root = chain.request().url.toString() == url
            if (!root) { started.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body((if (root) "[Adblock Plus 2.0]\n||partial.example^\n!#include child.txt" else "||child.example^").toResponseBody()).build()
        }.build()
        val job = launch(Dispatchers.IO) { AbpUpdateService(context, client, dao, store).updateInternal(entity) }
        try {
            withTimeout(5000) { started.await() }
            job.cancel()
        } finally {
            release.countDown()
        }
        withTimeout(5000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(0, dao.metadataWrites)
        assertEquals(123L, entity.lastLocalUpdate)
        AbpLoader(directory, listOf(entity), store).use { assertEquals("old.example", it.loadAll(ABP_PREFIX_DENY).single().pattern) }
    }

    private fun client() = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body("[Adblock Plus 2.0]\n! Version: 2\n! Title: 新しいフィルタ\n||new.example^".toResponseBody()).build()
    }.build()

    private fun decode(rules: String) = AbpFilterDecoder().decode(rules.reader().buffered(), url)

    private class FakeDao(var entry: AbpEntity?) : AbpDao {
        var metadataWrites = 0
        var checkedWrites = 0
        var beforeMetadata: () -> Unit = {}
        override suspend fun getAll() = listOfNotNull(entry)
        override suspend fun getById(id: Int) = entry?.takeIf { it.entityId == id }
        override suspend fun inset(abpEntity: AbpEntity): Long = throw AssertionError("購読を再挿入してはいけません。")
        override suspend fun inset(entities: List<AbpEntity>) = throw AssertionError("購読を再挿入してはいけません。")
        override suspend fun delete(abpEntity: AbpEntity) { entry = null }
        override suspend fun update(abpEntity: AbpEntity) = throw AssertionError("古いentityの全項目更新は禁止です。")
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
            checkedWrites++
            return 1
        }
        override suspend fun updateMetadata(id: Int, expectedUrl: String, title: String?, lastUpdate: String?,
                                            lastLocalUpdate: Long, expires: Int, version: String?, homePage: String?, lastModified: String?): Int {
            beforeMetadata()
            val current = getById(id)?.takeIf { it.url == expectedUrl && it.enabled } ?: return 0
            current.title = title
            current.lastUpdate = lastUpdate
            current.lastLocalUpdate = lastLocalUpdate
            current.expires = expires
            current.version = version
            current.homePage = homePage
            current.lastModified = lastModified
            metadataWrites++
            return 1
        }
    }
}
