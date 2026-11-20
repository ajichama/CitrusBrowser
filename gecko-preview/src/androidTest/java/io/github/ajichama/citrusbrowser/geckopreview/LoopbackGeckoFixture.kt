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

package io.github.ajichama.citrusbrowser.geckopreview

import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// 127.0.0.1 は同梱 EasyList の意図的な generichide 例外。例外のない別の loopback を使う。
private const val FIXTURE_HOST = "127.0.0.2"

/**
 * 端末内だけで待ち受け、通常のページ JS が送る測定結果と実 HTTP 到達数を突き合わせる。
 * 拡張注入・eval・uBO 内部 storage の書換えは使わない。
 */
internal class LoopbackGeckoFixture : Closeable {
    data class Load(val token: String, val url: String)
    data class Report(
        val documentNonce: String,
        val input: String,
        val ticks: Int,
        val controlLoaded: Boolean,
        val controlVisible: Boolean,
        val adLoaded: Boolean,
        val cosmeticHidden: Boolean,
    )

    private val monitor = Object()
    private val reports = ConcurrentHashMap<String, Report>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var closed = false
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(FIXTURE_HOST), 0), 16)
        soTimeout = 500
    }
    private val worker = Thread({ serve() }, "gecko-loopback-fixture").apply {
        isDaemon = true
        start()
    }

    fun page(advertising: Boolean): Load {
        val token = UUID.randomUUID().toString()
        val url = Uri.Builder().scheme("http").encodedAuthority("$FIXTURE_HOST:${server.localPort}")
            .path("/page").appendQueryParameter("token", token)
            .appendQueryParameter("mode", if (advertising) "ads" else "retention").build().toString()
        return Load(token, url)
    }

    fun count(load: Load, path: String, frame: String = "top"): Int =
        counts[key(load.token, frame, path)]?.get() ?: 0

    fun awaitReport(
        load: Load,
        frame: String = "top",
        timeoutMillis: Long = 30_000,
        predicate: (Report) -> Boolean,
    ): Report {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        synchronized(monitor) {
            while (true) {
                failure.get()?.let { throw AssertionError("ローカル fixture が停止しました。", it) }
                reports[key(load.token, frame, "report")]?.let { if (predicate(it)) return it }
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) throw AssertionError(
                    "fixture の結果待機が期限切れです: frame=$frame, last=${reports[key(load.token, frame, "report")]}")
                monitor.wait(minOf(left, 200))
            }
        }
    }

    private fun serve() {
        while (!closed) {
            try {
                server.accept().use { socket ->
                    try {
                        handle(socket)
                    } catch (_: IOException) {
                        // ナビゲーション取消時の切断は正常。各 socket の読み込み自体にも上限がある。
                    }
                }
            } catch (_: SocketTimeoutException) {
                // 停止状態を定期的に確認する。
            } catch (error: SocketException) {
                if (!closed) recordFailure(error)
            } catch (error: Exception) {
                recordFailure(error)
            }
        }
    }

    private fun recordFailure(error: Throwable) {
        failure.compareAndSet(null, error)
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 2_000
        val input = socket.getInputStream()
        val header = StringBuilder()
        while (header.length < 16_384) {
            val value = input.read()
            if (value < 0) return
            header.append(value.toChar())
            if (header.endsWith("\r\n\r\n")) break
        }
        if (!header.endsWith("\r\n\r\n")) throw IOException("要求ヘッダーが大きすぎます。")
        val first = header.toString().substringBefore("\r\n").split(' ')
        if (first.size < 2 || first[0] != "GET") return
        val uri = Uri.parse(first[1])
        val path = uri.path.orEmpty()
        val token = uri.getQueryParameter("token").orEmpty()
        if (!token.matches(Regex("[A-Za-z0-9-]{1,80}"))) {
            respond(socket, "text/plain", "fixture", 404)
            return
        }
        val frame = if (path == "/frame" || uri.getQueryParameter("frame") == "child") "child" else "top"
        counts.computeIfAbsent(key(token, frame, path)) { AtomicInteger() }.incrementAndGet()
        when (path) {
            "/page", "/frame" -> respond(socket, "text/html; charset=utf-8", html(token, frame, uri.getQueryParameter("mode") == "ads"))
            "/control.js" -> respond(socket, "application/javascript", "window.citrusControlLoaded=true;")
            "/microad.js" -> respond(socket, "application/javascript", "window.citrusAdLoaded=true;")
            "/citrus-result" -> {
                val result = JSONObject(uri.getQueryParameter("result") ?: throw IOException("結果がありません。"))
                reports[key(token, frame, "report")] = Report(
                    result.getString("documentNonce"), result.getString("input"), result.getInt("ticks"),
                    result.getBoolean("controlLoaded"), result.getBoolean("controlVisible"),
                    result.getBoolean("adLoaded"), result.getBoolean("cosmeticHidden"),
                )
                synchronized(monitor) { monitor.notifyAll() }
                respond(socket, "text/plain", "ok")
            }
            else -> respond(socket, "text/plain", "not found", 404)
        }
    }

    private fun html(token: String, frame: String, advertising: Boolean): String {
        val mode = if (advertising) "ads" else "retention"
        val advertisingScript = if (advertising) "<script src=\"/microad.js?token=$token&amp;frame=$frame\"></script>" else ""
        val child = if (frame == "top") "<iframe title=\"fixture child\" src=\"/frame?token=$token&amp;mode=$mode\"></iframe>" else ""
        // EasyList の一般規則 /microad.js${'$'}script と ##.adsbygoogle-box を既定リストの実証に使う。
        return """
            <!doctype html><html><head><meta charset="utf-8"><title>Citrus fixture</title>
            <script src="/control.js?token=$token&amp;frame=$frame"></script>$advertisingScript
            </head><body><div id="normal" style="display:block!important">normal content</div>
            <input id="fixture-input" value="original">
            <div id="cosmetic" class="adsbygoogle-box" style="display:block!important;width:120px;height:30px">fixture cosmetic</div>
            $child
            <script>
            (() => {
              const token = '$token', frame = '$frame';
              const documentNonce = token + ':' + Math.random().toString(36).slice(2);
              const input = document.getElementById('fixture-input');
              input.value = 'edited-' + token + '-' + frame;
              let ticks = 0, pending = false;
              setInterval(() => {
                ticks++;
                if (pending) return;
                const ad = document.getElementById('cosmetic');
                const css = ad && getComputedStyle(ad);
                const normal = document.getElementById('normal');
                const result = {
                  documentNonce, input: input.value, ticks,
                  controlLoaded: window.citrusControlLoaded === true,
                  controlVisible: getComputedStyle(normal).display !== 'none' && normal.getBoundingClientRect().height > 0,
                  adLoaded: window.citrusAdLoaded === true,
                  cosmeticHidden: !ad || css.display === 'none' || css.visibility === 'hidden'
                };
                pending = true;
                fetch('/citrus-result?token=' + token + '&frame=' + frame + '&result=' + encodeURIComponent(JSON.stringify(result)), {cache:'no-store'})
                  .catch(() => {}).finally(() => { pending = false; });
              }, 150);
            })();
            </script></body></html>
        """.trimIndent()
    }

    private fun respond(socket: Socket, type: String, body: String, status: Int = 200) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun key(token: String, frame: String, path: String) = "$token/$frame/$path"

    override fun close() {
        closed = true
        server.close()
        worker.join(3_000)
        check(!worker.isAlive) { "fixture の停止が期限内に完了しませんでした。" }
    }
}
