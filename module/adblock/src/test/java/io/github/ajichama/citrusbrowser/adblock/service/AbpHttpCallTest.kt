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

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class AbpHttpCallTest {
    @Test
    fun workerCancellationCancelsTheCallBeforeHeadersArrive() = runBlocking {
        val call = ManualCall()
        val job = launch { call.awaitResponse().close() }
        call.queued.await()
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        call.callback.onFailure(call, IOException("キャンセル済み"))
    }

    @Test
    fun responseArrivingAfterCancellationIsClosed() = runBlocking {
        val call = ManualCall()
        val job = launch { call.awaitResponse().close() }
        call.queued.await()
        job.cancelAndJoin()
        val closed = AtomicBoolean()
        call.callback.onResponse(call, response(call, closingBody(closed)))
        assertTrue(closed.get())
    }

    @Test
    fun successfulResponseRemainsOpenUntilItsOwnerClosesIt() = runBlocking {
        val call = ManualCall()
        val result = async { call.awaitResponse() }
        call.queued.await()
        val closed = AtomicBoolean()
        call.callback.onResponse(call, response(call, closingBody(closed)))
        val delivered = result.await()
        assertFalse(closed.get())
        delivered.close()
        assertTrue(closed.get())
    }

    @Test
    fun transportIOExceptionRemainsAvailableForRetryClassification() = runBlocking {
        val call = ManualCall()
        val result = async { runCatching { call.awaitResponse() } }
        call.queued.await()
        val transportCause = SocketTimeoutException("読み取りタイムアウト")
        val failure = IOException("一時的な通信失敗", transportCause)
        call.callback.onFailure(call, failure)
        val delivered = result.await().exceptionOrNull()
        assertTrue(delivered is IOException)
        assertEquals(failure.javaClass, delivered!!.javaClass)
        assertEquals(failure.message, delivered.message)
        // Coroutine の stacktrace recovery は元例外を cause に持つコピーを生成できる。
        // 型と内容に加え、元の通信例外とその原因の同一性が因果列に残ることを検証する。
        val causes = generateSequence(delivered) { it.cause }.take(8).toList()
        assertTrue(causes.any { it === failure })
        assertSame(transportCause, causes.last())
    }

    private fun closingBody(closed: AtomicBoolean) = object : ResponseBody() {
        private val data = Buffer().writeUtf8("内容")
        override fun contentType(): MediaType? = null
        override fun contentLength() = data.size
        override fun source() = data
        override fun close() { closed.set(true); super.close() }
    }

    private fun response(call: Call, body: ResponseBody) = Response.Builder().request(call.request())
        .protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body).build()

    private class ManualCall : Call {
        val queued = CompletableDeferred<Unit>()
        lateinit var callback: Callback
        private val cancelled = AtomicBoolean()
        override fun request() = Request.Builder().url("https://filters.example/list").build()
        override fun enqueue(responseCallback: Callback) { callback = responseCallback; queued.complete(Unit) }
        override fun execute(): Response = throw AssertionError("同期 HTTP は使用しません。")
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted() = queued.isCompleted
        override fun isCanceled() = cancelled.get()
        override fun timeout() = Timeout()
        override fun clone(): Call = ManualCall()
    }
}
