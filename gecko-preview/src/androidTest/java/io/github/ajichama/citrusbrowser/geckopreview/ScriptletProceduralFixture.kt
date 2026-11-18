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

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// 127.0.0.1 と localhost は同梱 EasyList の generichide 例外に当たる。既存計測で例外がないと確認済みの別 loopback を使う。
private const val SP_HOST = "127.0.0.2"
private const val MAX_HEADER = 8192
private const val MAX_BODY = 1024
private const val MAX_LOADS = 8
private const val MAX_READINESS = 400
private const val MAX_TICKS = 400

/**
 * 原本 uBO の `set-constant` scriptlet と動的 `:has-text` procedural を、ページ自身の通常 JS だけで測定する。
 * 端末内 loopback だけで待ち受け、外部への通信や DOM 本文・URL の収集は行わない。
 * 測定値は真偽値と有界な整数だけを受け取り、本文はログ・例外文へ出さない。
 *
 * - フィルタリスト: uBO が自身の購読処理で取得する。nonce 付きのプロパティ・class・照合文字列だけを対象にする。
 * - 準備確認ページ: 同梱 EasyList の `/microad.js$script`（初回 cold 起動の既定リスト準備）と、
 *   本リストの nonce 付き sentinel 規則（購読リストの読込完了）の通信到達数を、ページの load 完了後に数える。
 */
internal class ScriptletProceduralFixture : Closeable {
    data class Load(val key: String, val url: String)

    data class Report(
        val initialTrue: Boolean,
        val assignmentStayedTrue: Boolean,
        val initialProceduralVisible: Boolean,
        val proceduralHidden: Boolean,
        val controlVisible: Boolean,
        val normalJs: Boolean,
        val textChanged: Boolean,
        val ticks: Int,
        /** 所有 key・frame・ページ内乱数から作る文書識別。本文は含まない。 */
        val documentNonce: String,
    ) {
        /** 失敗時の診断用。真偽値と整数だけを出す。 */
        override fun toString() = "initialTrue=$initialTrue, assignmentStayedTrue=$assignmentStayedTrue, " +
            "initialProceduralVisible=$initialProceduralVisible, proceduralHidden=$proceduralHidden, " +
            "controlVisible=$controlVisible, normalJs=$normalJs, textChanged=$textChanged, ticks=$ticks"
    }

    data class Readiness(val key: String, val url: String)

    data class ReadinessResult(val control: Int, val easyListSentinel: Int, val fixtureSentinel: Int)

    private class BadRequest : IOException("fixture への要求が不正です。")

    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private val monitor = Object()
    private val loads = ConcurrentHashMap<String, Load>()
    private val readiness = ConcurrentHashMap<String, Readiness>()
    private val reports = ConcurrentHashMap<String, Report>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var closed = false
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(SP_HOST), 0), 16)
        soTimeout = 250
    }
    private val authority = "$SP_HOST:${server.localPort}"
    private val origin = "http://$authority"
    private val worker = Thread({ serve() }, "citrus-scriptlet-fixture").apply {
        isDaemon = true
        start()
    }

    val propertyName = "citrusFixtureFlag$nonce"
    val selectorClass = "citrus-fixture-$nonce"
    val matchingText = "CITRUS_MATCH_$nonce"
    private val sentinelFile = "citrus-sentinel-$nonce.js"
    val listUrl = "$origin/citrus-list/$nonce.txt"

    /** uBO が取得するテスト用リスト。trusted 専用構文は使わない（set-constant の true と :has-text は untrusted でも有効）。 */
    private val listText = """
        ! Title: Citrus disposable fixture
        ! Loopback measurement only. Rules affect nonce-specific targets only.
        /$sentinelFile${'$'}script
        $SP_HOST##+js(set-constant, $propertyName, true)
        $SP_HOST##.$selectorClass:has-text($matchingText)
    """.trimIndent() + "\n"

    fun listFetches(): Int = counts["list"]?.get() ?: 0

    @Synchronized
    fun page(label: String): Load {
        check(!closed) { "fixture は終了しています。" }
        check(loads.size < MAX_LOADS) { "fixture のページ数上限です。" }
        require(label.matches(Regex("[a-z]{1,8}")))
        // ラベルは所有ページの区別だけに使い、測定値の生成には使わない。
        val key = label + UUID.randomUUID().toString().replace("-", "")
        return Load(key, "$origin/citrus-page/$key/top").also { loads[key] = it }
    }

    @Synchronized
    fun readiness(): Readiness {
        check(!closed) { "fixture は終了しています。" }
        check(readiness.size < MAX_READINESS) { "準備確認ページ数の上限です。" }
        val key = "ready" + UUID.randomUUID().toString().replace("-", "")
        return Readiness(key, "$origin/citrus-ready/$key/top").also { readiness[key] = it }
    }

    fun pageRequests(load: Load, frame: String): Int = counts["${load.key}/$frame/page"]?.get() ?: 0

    fun lastReport(load: Load, frame: String): Report? = reports["${load.key}/$frame"]

    /** 期限付きで固定測定値を待つ。predicate は Report の比較だけに使う。 */
    fun awaitReport(load: Load, frame: String, timeoutMillis: Long, predicate: (Report) -> Boolean): Report {
        require(loads[load.key] === load && validFrame(frame) && timeoutMillis in 1..120_000)
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        synchronized(monitor) {
            while (true) {
                failure.get()?.let { throw AssertionError("専用 fixture が停止しました。", it) }
                if (closed) throw AssertionError("専用 fixture は終了しています。")
                val report = reports["${load.key}/$frame"]
                if (report != null && predicate(report)) return report
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) throw AssertionError("固定測定結果の待機が期限切れです: frame=$frame, last=$report")
                monitor.wait(minOf(left, 200))
            }
        }
    }

    /** ページの load 完了通知を待ち、その時点の通信到達数を返す。期限切れは null。 */
    fun awaitReadiness(ready: Readiness, timeoutMillis: Long): ReadinessResult? {
        require(readiness[ready.key] === ready && timeoutMillis in 1..120_000)
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        synchronized(monitor) {
            while (true) {
                failure.get()?.let { throw AssertionError("専用 fixture が停止しました。", it) }
                if (closed) throw AssertionError("専用 fixture は終了しています。")
                if (count("${ready.key}/done") > 0) {
                    return ReadinessResult(count("${ready.key}/control"), count("${ready.key}/microad"),
                        count("${ready.key}/sentinel"))
                }
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) return null
                monitor.wait(minOf(left, 200))
            }
        }
    }

    private fun count(key: String) = counts[key]?.get() ?: 0

    private fun increment(key: String) {
        counts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun serve() {
        while (!closed) {
            try {
                server.accept().use { socket ->
                    try {
                        handle(socket)
                    } catch (_: BadRequest) {
                        runCatching { respond(socket, 400, "text/plain", "fixture") }
                    } catch (_: IOException) {
                        // ナビゲーション取消・読込期限切れは、この接続だけを閉じる。
                    }
                }
            } catch (_: SocketTimeoutException) {
                // 終了状態を定期的に確認する。
            } catch (error: SocketException) {
                if (!closed) recordFailure()
            } catch (error: Exception) {
                // 要求内容を含み得る例外本文を診断へ流さない。
                if (!closed) recordFailure()
            }
        }
    }

    private fun recordFailure() {
        failure.compareAndSet(null, IOException("専用 fixture の内部処理が停止しました。"))
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun handle(socket: Socket) {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        val input = socket.getInputStream()
        val header = ByteArrayOutputStream()
        var tail = 0
        var complete = false
        while (header.size() < MAX_HEADER) {
            setReadTimeout(socket, deadline)
            val value = input.read()
            if (value < 0) return
            header.write(value)
            tail = (tail shl 8) or value
            if (tail == 0x0d0a0d0a) {
                complete = true
                break
            }
        }
        if (!complete) throw BadRequest()
        val lines = header.toString(Charsets.US_ASCII.name()).split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw BadRequest()
        val first = lines[0].split(' ')
        if (first.size != 3 || first[2] != "HTTP/1.1") throw BadRequest()
        val (method, target) = first
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw BadRequest()
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            // Cookie 等の他ヘッダーは保存・解釈しない。
            if (name in setOf("host", "origin", "content-length", "content-type", "transfer-encoding")) {
                if (headers.put(name, line.substring(colon + 1).trim()) != null) throw BadRequest()
            }
        }
        if (headers["host"] != authority || headers.containsKey("transfer-encoding")) throw BadRequest()
        val path = target.substringBefore('?')
        val parts = path.split('/')
        when {
            method == "GET" && path == "/citrus-list/$nonce.txt" -> {
                increment("list")
                respond(socket, 200, "text/plain; charset=utf-8", listText)
            }
            method == "GET" && parts.size == 4 && parts[1] == "citrus-page" && validFrame(parts[3]) -> {
                val load = loads[parts[2]] ?: return respond(socket, 404, "text/plain", "fixture")
                increment("${load.key}/${parts[3]}/page")
                respond(socket, 200, "text/html; charset=utf-8", pageHtml(load.key, parts[3]))
            }
            method == "POST" && parts.size == 4 && parts[1] == "citrus-result" && validFrame(parts[3]) -> {
                val load = loads[parts[2]] ?: return respond(socket, 404, "text/plain", "fixture")
                // 同一 origin のページからの送信だけを受け付ける（Origin 省略時は Host の一致で判定済み）。
                headers["origin"]?.let { if (it != origin) throw BadRequest() }
                if (!isFormMediaType(headers["content-type"])) throw BadRequest()
                val length = boundedNumber(headers["content-length"], 1, MAX_BODY) ?: throw BadRequest()
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    setReadTimeout(socket, deadline)
                    val read = input.read(body, offset, length - offset)
                    if (read < 0) throw IOException("固定測定結果が途中で終了しました。")
                    offset += read
                }
                val report = parseReport(body, load.key, parts[3]) ?: throw BadRequest()
                reports["${load.key}/${parts[3]}"] = report
                synchronized(monitor) { monitor.notifyAll() }
                respond(socket, 200, "text/plain", "ok")
            }
            method == "GET" && parts.size == 4 && parts[1] == "citrus-ready" -> {
                val ready = readiness[parts[2]] ?: return respond(socket, 404, "text/plain", "fixture")
                when (parts[3]) {
                    "top" -> respond(socket, 200, "text/html; charset=utf-8", readinessHtml(ready.key))
                    "control.js" -> { increment("${ready.key}/control"); respond(socket, 200, "application/javascript", "void 0;") }
                    "microad.js" -> { increment("${ready.key}/microad"); respond(socket, 200, "application/javascript", "void 0;") }
                    sentinelFile -> { increment("${ready.key}/sentinel"); respond(socket, 200, "application/javascript", "void 0;") }
                    "done" -> { increment("${ready.key}/done"); respond(socket, 200, "text/plain", "ok") }
                    else -> respond(socket, 404, "text/plain", "fixture")
                }
            }
            else -> respond(socket, 404, "text/plain", "fixture")
        }
    }

    private fun readinessHtml(key: String): String {
        val base = "/citrus-ready/$key"
        // 同期 script を head に置き、遮断された script も含めて処理が終わった後の load で完了を通知する。
        return """
            <!doctype html><html><head><meta charset="utf-8"><title>Citrus readiness</title>
            <script src="$base/control.js"></script>
            <script src="$base/microad.js"></script>
            <script src="$base/$sentinelFile"></script>
            </head><body><p>readiness</p>
            <script>addEventListener('load',()=>{fetch('$base/done',{cache:'no-store',credentials:'omit'}).catch(()=>{});});</script>
            </body></html>
        """.trimIndent()
    }

    private fun pageHtml(key: String, frame: String): String {
        val child = if (frame == "top") "<iframe title=\"fixture child\" src=\"/citrus-page/$key/child\"></iframe>" else ""
        // ページ自身は最初の読取より前に true を書かない。false の再代入後も scriptlet の効果だけを読む。
        val initial = "<script>window.__citrusProof={initialTrue:window['$propertyName']===true};" +
            "try{window['$propertyName']=false;}catch(e){}" +
            "window.__citrusProof.assignmentStayedTrue=window['$propertyName']===true;</script>"
        val script = """
            <script>(()=>{
              const state=window.__citrusProof, target=document.getElementById('target'), control=document.getElementById('control');
              const visible=(node)=>{const css=getComputedStyle(node),r=node.getBoundingClientRect();return css.display!=='none'&&css.visibility!=='hidden'&&r.width>0&&r.height>0;};
              const initialProceduralVisible=visible(target);
              const documentCounter=1+Math.floor(Math.random()*1000000000);
              let textChanged=false,ticks=0,pending=false,normalJs=false;
              (()=>{normalJs=true;})();
              // uBO の DOM 監視は要素ノードの追加・削除だけを再判定の契機にする（contentscript.js の safeObserverHandler）。
              // 実際の広告と同じく、一致する文字を持つ要素を後から追加する。文字だけの書換えは uBO の仕様上検知されない。
              setTimeout(()=>{const node=document.createElement('span');node.textContent='$matchingText';target.replaceChildren(node);textChanged=true;},350);
              const timer=setInterval(()=>{
                if(ticks>=$MAX_TICKS){clearInterval(timer);return;}
                ticks++;
                if(pending)return;
                const values={initialTrue:state.initialTrue,assignmentStayedTrue:state.assignmentStayedTrue,
                  initialProceduralVisible,proceduralHidden:textChanged&&!visible(target),controlVisible:visible(control),normalJs,textChanged};
                let body=Object.keys(values).map((key)=>key+'='+(values[key]?'1':'0')).join('&');
                body+='&ticks='+ticks+'&documentCounter='+documentCounter;
                pending=true;
                fetch('/citrus-result/$key/$frame',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body,cache:'no-store',credentials:'omit'})
                  .catch(()=>{}).finally(()=>{pending=false;});
              },150);
            })();</script>
        """.trimIndent()
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Citrus fixture</title>$initial</head><body>" +
            "<div id=\"target\" class=\"$selectorClass\" style=\"display:block;min-height:20px\">neutral fixture text</div>" +
            "<div id=\"control\" class=\"$selectorClass\" style=\"display:block;min-height:20px\">non-matching control</div>" +
            child + script + "</body></html>"
    }

    private fun parseReport(bytes: ByteArray, key: String, frame: String): Report? {
        if (bytes.any { it < 0x20 || it > 0x7e }) return null
        val values = HashMap<String, String>()
        for (field in String(bytes, Charsets.US_ASCII).split('&')) {
            val pair = field.split('=')
            if (pair.size != 2 || pair[0] !in REPORT_FIELDS || values.put(pair[0], pair[1]) != null) return null
        }
        if (values.keys != REPORT_FIELDS) return null
        fun flag(name: String): Boolean? = when (values[name]) { "1" -> true; "0" -> false; else -> null }
        val ticks = boundedNumber(values["ticks"], 1, MAX_TICKS) ?: return null
        val counter = boundedNumber(values["documentCounter"], 1, 1_000_000_000) ?: return null
        return Report(
            flag("initialTrue") ?: return null,
            flag("assignmentStayedTrue") ?: return null,
            flag("initialProceduralVisible") ?: return null,
            flag("proceduralHidden") ?: return null,
            flag("controlVisible") ?: return null,
            flag("normalJs") ?: return null,
            flag("textChanged") ?: return null,
            ticks,
            "$key:$frame:$counter",
        )
    }

    private fun respond(socket: Socket, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        server.close()
        synchronized(monitor) { monitor.notifyAll() }
        worker.join(4_000)
        check(!worker.isAlive) { "fixture の停止が期限内に完了しませんでした。" }
    }

    private companion object {
        val REPORT_FIELDS = setOf(
            "initialTrue", "assignmentStayedTrue", "initialProceduralVisible", "proceduralHidden",
            "controlVisible", "normalJs", "textChanged", "ticks", "documentCounter",
        )

        fun validFrame(frame: String) = frame == "top" || frame == "child"

        fun boundedNumber(value: String?, minimum: Int, maximum: Int): Int? {
            if (value == null || value.isEmpty() || value.length > 10 || !value.all { it in '0'..'9' }) return null
            return value.toLong().takeIf { it in minimum..maximum }?.toInt()
        }

        /** `;` 以降の charset 等を除き、メディアタイプだけを大文字小文字無視で比較する。 */
        fun isFormMediaType(value: String?): Boolean =
            value?.substringBefore(';')?.trim()?.equals("application/x-www-form-urlencoded", ignoreCase = true) == true

        fun setReadTimeout(socket: Socket, deadline: Long) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) throw IOException("接続の読込期限を超えました。")
            socket.soTimeout = minOf(left, 1_000).toInt()
        }
    }
}
