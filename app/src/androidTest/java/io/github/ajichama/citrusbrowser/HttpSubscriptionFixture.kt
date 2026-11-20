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

package io.github.ajichama.citrusbrowser

import android.content.Context
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDao
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import io.github.ajichama.citrusbrowser.adblock.service.AbpUpdateService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 端末内だけで応答し、自分の購読・要求・キャッシュ以外には触れない。 */
internal class HttpSubscriptionFixture(
    val context: Context,
    val dao: AbpDao,
    val entity: AbpEntity,
    val server: LoopbackFilterServer,
) {
    private val manager = WorkManager.getInstance(context)
    private val workIds = mutableListOf<UUID>()
    private val directory = context.getDir("adblock_filter", Context.MODE_PRIVATE)
    val bundle get() = File(directory, "abp_${entity.entityId}.zip")

    fun enqueueManual(): UUID = AbpUpdateService.update(context, entity).also { workIds += it }

    suspend fun awaitFinished(id: UUID): WorkInfo = withTimeout(30_000) {
        manager.getWorkInfoByIdFlow(id).first { it?.state?.isFinished == true }!!
    }

    suspend fun awaitRetry(id: UUID): WorkInfo = withTimeout(20_000) {
        manager.getWorkInfoByIdFlow(id).first {
            it != null && it.runAttemptCount >= 1 && (it.state == WorkInfo.State.ENQUEUED || it.state.isFinished)
        }!!
    }

    /** 公開manual入口はforce指定のため、条件付要求だけ既存Hilt生成updaterを直接通す。 */
    @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
    suspend fun conditionalUpdate(): String = withTimeout(20_000) {
        // 過去の成功要求の重複排除を避け、非forceのHTTP分岐そのものを検証する。
        AbpUpdateService.createInstance(context).updateEntity(entity.entityId, false, Long.MAX_VALUE).name
    }

    suspend fun saved(): AbpEntity = requireNotNull(dao.getById(entity.entityId))

    suspend fun close() {
        val current = dao.getById(entity.entityId)
        if (current != null) {
            assertEquals("固有購読の URL が変更されたため削除を中止します。", server.url, current.url)
            dao.setEnabled(entity.entityId, false)
        }
        var cancellationFailure: Throwable? = null
        workIds.forEach { id ->
            try { withTimeout(10_000) { manager.cancelWorkById(id).await(); awaitFinished(id) } }
            catch (error: Throwable) { cancellationFailure?.addSuppressed(error) ?: run { cancellationFailure = error } }
        }
        // CANCELLED の通知だけで公開中の処理終了を推測せず、同じ更新Mutexを通過させる。
        assertEquals("Skipped", conditionalUpdate())
        dao.getById(entity.entityId)?.let {
            assertEquals(server.url, it.url)
            dao.delete(it)
        }
        AtomicFile(bundle).delete()
        val legacy = listOf("b_", "w_", "wp_", "e_").map { File(directory, "$it${entity.entityId}") }
        val temporary = directory.listFiles().orEmpty().filter { it.name.startsWith("abp-${entity.entityId}-") && it.extension in setOf("tmp", "rollback") }
        (legacy + temporary).forEach { assertTrue(!it.exists() || it.delete()) }
        assertFalse(bundle.exists())
        assertFalse(File(bundle.path + ".bak").exists())
        assertFalse(File(bundle.path + ".new").exists())
        cancellationFailure?.let { throw it }
    }
}

internal suspend fun withHttpSubscription(test: suspend (HttpSubscriptionFixture) -> Unit) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    assertTrue(context is CitrusBrowserApplication)
    val dao = (context as CitrusBrowserApplication).abpDatabase.abpDao()
    val server = LoopbackFilterServer()
    var fixture: HttpSubscriptionFixture? = null
    var row: AbpEntity? = null
    var failure: Throwable? = null
    try {
        val entity = AbpEntity(url = server.url, title = "http-fixture-before", version = "before", enabled = true)
        entity.entityId = dao.inset(entity).toInt()
        row = entity
        val prepared = HttpSubscriptionFixture(context, dao, entity, server)
        fixture = prepared
        test(prepared)
        server.assertHealthy()
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        withContext(NonCancellable) {
            try {
                val prepared = fixture
                if (prepared != null) prepared.close()
                else row?.let { dao.delete(it) }
            } catch (cleanup: Throwable) {
                failure?.addSuppressed(cleanup) ?: run { failure = cleanup }
            } finally {
                try { server.close() }
                catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: run { failure = cleanup } }
            }
        }
        // テスト本体が成功していても後始末の失敗を成功として扱わない。
        failure?.let { throw it }
    }
}

/** Socketと受信行・要求数・終了待ちを制限した、テスト専用HTTP/1.1サーバー。 */
internal class LoopbackFilterServer : AutoCloseable {
    data class Exchange(val path: String, val headers: Map<String, String>, val status: Int)
    private val token = UUID.randomUUID().toString()
    val host = "http-fixture-$token.invalid"
    val title = "Citrus HTTP $token"
    val version = "http-$token"
    val modified = "Wed, 01 Jan 2025 00:00:00 GMT"
    private val path = "/filters/$token.txt"
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    val url = "http://127.0.0.1:${socket.localPort}$path"
    val exchanges = CopyOnWriteArrayList<Exchange>()
    @Volatile var unavailable = false
    private val stopped = AtomicBoolean(false)
    private val active = AtomicReference<Socket?>()
    private val failure = AtomicReference<Throwable?>()
    private val thread = Thread({ serve() }, "citrus-http-fixture").apply { isDaemon = true; start() }

    private fun serve() {
        try {
            while (!stopped.get()) {
                val client = try { socket.accept() } catch (_: SocketTimeoutException) { continue }
                active.set(client)
                if (stopped.get()) { client.close(); active.set(null); break }
                try {
                    client.use { connection ->
                        connection.soTimeout = 3000
                        val reader = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val first = line(reader) ?: throw IOException("HTTP 要求行がありません。")
                        val parts = first.split(' ')
                        if (parts.size != 3 || parts[0] != "GET") throw IOException("未対応の HTTP 要求です。")
                        val headers = linkedMapOf<String, String>()
                        var count = 0
                        while (true) {
                            val header = line(reader) ?: throw IOException("HTTP ヘッダーが途中で終了しました。")
                            if (header.isEmpty()) break
                            if (++count > 128 || ':' !in header) throw IOException("HTTP ヘッダーの構造が不正です。")
                            headers[header.substringBefore(':').lowercase(Locale.ROOT)] = header.substringAfter(':').trim()
                        }
                        if (exchanges.size >= 32) throw IOException("テストの HTTP 要求数が上限を超えました。")
                        val status = when {
                            parts[1] != path -> 404
                            unavailable -> 503
                            headers["if-modified-since"] == modified -> 304
                            else -> 200
                        }
                        val body = if (status == 200) {
                            "[Adblock Plus 2.0]\n! Title: $title\n! Version: $version\n! Expires: 1 day\n||$host^\n$host##.citrus-http\n".toByteArray(Charsets.UTF_8)
                        } else ByteArray(0)
                        val reason = when (status) { 200 -> "OK"; 304 -> "Not Modified"; 503 -> "Service Unavailable"; else -> "Not Found" }
                        val response = "HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nLast-Modified: $modified\r\nConnection: close\r\n\r\n"
                        connection.getOutputStream().apply { write(response.toByteArray(Charsets.US_ASCII)); write(body); flush() }
                        exchanges += Exchange(parts[1], headers, status)
                    }
                } finally { active.set(null) }
            }
        } catch (error: Throwable) {
            if (!stopped.get()) failure.set(error)
        }
    }

    private fun line(reader: BufferedReader): String? {
        val result = StringBuilder()
        repeat(8192) {
            val character = reader.read()
            if (character == -1) return if (result.isEmpty()) null else throw IOException("HTTP 行が途中で終了しました。")
            if (character == '\n'.code) return result.toString().removeSuffix("\r")
            result.append(character.toChar())
        }
        throw IOException("HTTP 行が長すぎます。")
    }

    fun assertHealthy() { failure.get()?.let { throw AssertionError("端末内 HTTP サーバーが失敗しました。", it) } }

    override fun close() {
        stopped.set(true)
        try { active.get()?.close() }
        finally {
            try { socket.close() }
            finally { thread.join(2000) }
        }
        assertFalse("HTTP サーバーが終了しません。", thread.isAlive)
        assertHealthy()
    }
}
