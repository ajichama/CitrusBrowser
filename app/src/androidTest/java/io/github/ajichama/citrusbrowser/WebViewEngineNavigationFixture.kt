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

package io.github.ajichama.citrusbrowser

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.ajichama.citrusbrowser.browser.ActionExecutor
import io.github.ajichama.citrusbrowser.browser.BrowserActivity
import io.github.ajichama.citrusbrowser.legacy.action.SingleAction
import io.github.ajichama.citrusbrowser.legacy.action.manager.ActionController
import io.github.ajichama.citrusbrowser.legacy.tab.manager.MainTabData
import io.github.ajichama.citrusbrowser.legacy.webkit.TabType
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal fun withEngineNavigationFixture(test: (WebViewEngineNavigationFixture) -> Unit) {
    val fixture = WebViewEngineNavigationFixture()
    var failure: Throwable? = null
    try {
        fixture.start()
        test(fixture)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try { fixture.close() } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: throw error
        }
    }
}

/** 設定値・保存履歴を変更せず、実 Activity が既に所有する executor を使う。 */
internal class WebViewEngineNavigationFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
    private val browser = AtomicReference<BrowserActivity>()
    private val createdBrowser = AtomicReference<BrowserActivity>()
    private val owned = linkedSetOf<Long>()
    private var initialIds = emptyList<Long>()
    private var initialCurrent: Long? = null
    private var initialPreferences: Map<String, *>? = null
    private var monitoring = false
    private var baselineReady = false
    private lateinit var activity: BrowserActivity
    private lateinit var executor: ActionExecutor
    val server = EngineNavigationServer()
    private val callback = ActivityLifecycleCallback { found, stage ->
        if (found is BrowserActivity && stage != Stage.DESTROYED) {
            browser.set(found)
            if (stage == Stage.CREATED) createdBrowser.compareAndSet(null, found)
        }
    }

    fun start() {
        ui {
            lifecycle.addLifecycleCallback(callback)
            monitoring = true
            for (stage in listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)) {
                val existing = lifecycle.getActivitiesInStage(stage).filterIsInstance<BrowserActivity>()
                    .firstOrNull { !it.isDestroyed && !it.isFinishing }
                if (existing != null) { browser.set(existing); break }
            }
            // Browser が起動 Intent を消費するため、Intent 照合に依存する Scenario は使わない。
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, BrowserActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        await("本体が RESUMED") {
            ui { browser.get()?.let { !it.isDestroyed && !it.isFinishing && lifecycle.getLifecycleStageOf(it) == Stage.RESUMED } == true }
        }
        activity = requireNotNull(browser.get())
        ui {
            initialIds = ids()
            initialCurrent = activity.tabManager.currentTabData.id
            initialPreferences = AppPrefs.getPreference(activity).all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            }
            baselineReady = true
            // 既存インスタンスを読むだけで、DI・cache・公開製品APIをテスト用に変更しない。
            executor = BrowserActivity::class.java.getDeclaredField("actionController").apply { isAccessible = true }
                .get(activity) as ActionExecutor
        }
    }

    fun newTab(): Long = ui {
        val before = ids().toSet()
        try {
            activity.openInNewTab("about:blank", TabType.WINDOW, true)
        } finally {
            // 追加後の例外でも、生成された専用タブを後始末の対象へ先に登録する。
            owned.addAll(ids().filterNot { it in before })
        }
        val additions = ids().filterNot { it in before }
        assertEquals("作成した専用タブは一件です", 1, additions.size)
        additions.single().also { id ->
            tab(id).isNavLock = false
            tab(id).isPinning = false
            // 専用Viewだけでfixtureを有効化する。全体prefsや他タブには書き込まない。
            tab(id).mWebView.webSettings.javaScriptEnabled = true
        }
    }

    fun tab(id: Long): MainTabData {
        check(id in owned) { "既存タブを操作対象にできません。" }
        val index = activity.indexOf(id)
        check(index >= 0) { "専用タブがありません。" }
        return activity.getTab(index)
    }

    fun ids(): List<Long> = activity.tabManager.indexDataList.map { it.id }

    fun load(id: Long, page: EngineNavigationServer.Page) {
        ui {
            activity.setCurrentTab(activity.indexOf(id))
            activity.loadUrl(tab(id), page.url, false)
        }
        awaitPage(id, page)
    }

    fun loadUntilGated(id: Long, page: EngineNavigationServer.Page) {
        ui {
            activity.setCurrentTab(activity.indexOf(id))
            activity.loadUrl(tab(id), page.url, false)
        }
        await("未完の fixture 応答を読込中") {
            server.requests(page) > 0 && ui { tab(id).isInPageLoad && tab(id).title == page.title }
        }
        await("停止前の DOM marker") { marker(id).optString("nonce") == page.nonce }
    }

    fun run(id: Long, action: SingleAction): List<Long> = ui {
        val target = activity.indexOf(id)
        check(target >= 0 && id in owned)
        activity.setCurrentTab(target)
        val before = ids().toSet()
        try {
            assertTrue("公開 ActionExecutor が操作を受け付けません", executor.run(action, ActionController.TargetInfo(target), null))
        } finally {
            // false/例外を返す前にタブが追加された場合も、所有登録を欠かさない。
            owned.addAll(ids().filterNot { it in before })
        }
        ids().filterNot { it in before }
    }

    fun runAndExpectOneNewTab(id: Long, action: SingleAction): Long = run(id, action).also {
        assertEquals("履歴ロックで新規タブが一件開きます", 1, it.size)
    }.single()

    fun reloadAndAwaitOneRequest(id: Long, page: EngineNavigationServer.Page, action: Int) {
        val before = server.requests(page)
        assertTrue("対象は読込完了後です", ui { !tab(id).isInPageLoad })
        run(id, SingleAction.makeInstance(action))
        awaitPage(id, page, before + 1)
        assertEquals("更新で送った要求は一回です", before + 1, server.requests(page))
    }

    fun awaitPage(id: Long, page: EngineNavigationServer.Page, expectedRequests: Int? = null) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var loaded = false
        var missingCallbacks = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val hits = server.requests(page)
            if (expectedRequests != null) {
                assertTrue("fixture=${page.name}: 更新要求が予定回数を超えています", hits <= expectedRequests)
            }
            // reload直後は旧titleと旧loading=falseが残る。まず新しい実要求の到達を待つ。
            if (expectedRequests == null || hits >= expectedRequests) {
                loaded = ui(remaining(deadline)) { tab(id).title == page.title && !tab(id).isInPageLoad }
                if (loaded) {
                    val result = try {
                        readMarker(id, deadline, callbackLimit = 500)
                    } catch (_: TimeoutException) {
                        // navigationで旧contextのcallbackが破棄されることがある。成功扱いにはしない。
                        missingCallbacks++
                        null
                    }
                    if (result != null && result.optString("nonce") == page.nonce && result.optBoolean("finished") &&
                        result.optInt("scriptRuns") == 1 && (expectedRequests == null ||
                        result.optString("marker") == "${page.name}:$expectedRequests")) {
                        Log.i("EngineNavigationFixture", "fixture=${page.name}, hits=${server.requests(page)}, loaded=$loaded, missingCallbacks=$missingCallbacks")
                        return
                    }
                }
            }
            val wait = (deadline - SystemClock.elapsedRealtime()).coerceIn(0, 50)
            if (wait > 0) Thread.sleep(wait)
        }
        throw AssertionError("文書待機期限: fixture=${page.name}, hits=${server.requests(page)}, loaded=$loaded, missingCallbacks=$missingCallbacks")
    }

    fun marker(id: Long): JSONObject = readMarker(id, SystemClock.elapsedRealtime() + 5_000, callbackLimit = 5_000)

    private fun readMarker(id: Long, deadline: Long, callbackLimit: Long): JSONObject {
        val result = java.util.concurrent.CompletableFuture<String>()
        ui(remaining(deadline)) {
            tab(id).mWebView.evaluateJavascript("JSON.stringify(window.__engineFixture || null)") { value ->
                result.complete(value ?: "null")
            }
        }
        return try {
            val value = JSONTokener(result.get(minOf(callbackLimit, remaining(deadline)), TimeUnit.MILLISECONDS)).nextValue()
            if (value is String && value != "null") JSONObject(value) else JSONObject()
        } catch (error: TimeoutException) {
            result.cancel(false)
            throw error
        }
    }

    private fun remaining(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).let {
        if (it <= 0) throw TimeoutException("文書確認の全体待機期限を超えました。")
        it.coerceAtMost(5_000)
    }

    fun await(label: String, timeout: Long = 10_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        do {
            if (predicate()) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < end)
        throw AssertionError("待機期限を超えました: $label")
    }

    fun <T> ui(timeout: Long = 5_000, action: () -> T): T {
        require(timeout in 1..5_000)
        val task = FutureTask(Callable { action() })
        check(Handler(Looper.getMainLooper()).post(task))
        return try { task.get(timeout, TimeUnit.MILLISECONDS) }
        catch (error: TimeoutException) { task.cancel(false); throw AssertionError("main の操作が${timeout}msで完了しません。", error) }
        catch (error: InterruptedException) { task.cancel(false); throw error }
        catch (error: ExecutionException) { throw requireNotNull(error.cause) }
    }

    override fun close() {
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        if (baselineReady) {
            cleanup { ui { owned.forEach { id -> if (activity.indexOf(id) >= 0) tab(id).mWebView.stopLoading() } } }
            cleanup { server.close() }
            cleanup {
                // executor の50/500ms後の処理を、専用タブを表示したまま消化する。
                val drained = FutureTask(Callable { Unit })
                check(Handler(Looper.getMainLooper()).postDelayed(drained, 650))
                try { drained.get(3, TimeUnit.SECONDS) } catch (error: Exception) { drained.cancel(false); throw error }
            }
            cleanup { ui {
                if (!activity.isDestroyed) {
                    initialCurrent?.let { id -> activity.indexOf(id).takeIf { it >= 0 }?.let(activity::setCurrentTab) }
                    // removeTab は閉じたタブ履歴を増やすため、非currentの所有タブだけを公開managerから除く。
                    owned.toList().asReversed().forEach { id ->
                        val index = activity.indexOf(id)
                        if (index >= 0) {
                            check(activity.tabManager.currentTabData.id != id)
                            val web = tab(id).mWebView
                            web.onPause()
                            web.setEmbeddedTitleBarMethod(null)
                            activity.tabManager.remove(index)
                            web.destroy()
                        }
                    }
                    initialCurrent?.let { id -> activity.indexOf(id).takeIf { it >= 0 }?.let(activity::setCurrentTab) }
                    assertEquals("既存のタブID・順序が変わりました", initialIds, ids())
                    assertEquals("既存の選択タブを復旧できません", initialCurrent, activity.tabManager.currentTabData.id)
                    // 値を失敗メッセージへ出さず、後続writerを全prefs復元で巻き戻すこともしない。
                    assertTrue("本テストは永続設定を変更していません", initialPreferences == AppPrefs.getPreference(activity).all)
                    activity.tabManager.saveData()
                }
            } }
        } else cleanup { server.close() }
        cleanup { ui { createdBrowser.get()?.takeUnless { it.isDestroyed }?.finish() } }
        if (monitoring) cleanup { ui { lifecycle.removeLifecycleCallback(callback) } }
        failure?.let { throw it }
    }
}

/** data:文書は既存history managerの対象外。要求計数用scriptだけをloopbackで返す。 */
internal class EngineNavigationServer : AutoCloseable {
    class Page(val name: String, val nonce: String, val title: String, val url: String, val path: String, val gated: Boolean) {
        internal val gate = CountDownLatch(if (gated) 1 else 0)
        internal val responded = CountDownLatch(1)
        internal val hits = AtomicInteger()
    }

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val pages = ConcurrentHashMap<String, Page>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean()
    private val executor = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "engine-fixture-response").apply { isDaemon = true } }
    private val listener = Thread({
        while (!closed.get()) {
            try {
                val socket = server.accept()
                sockets.add(socket)
                executor.execute { respond(socket) }
            } catch (error: IOException) { if (!closed.get()) throw error }
        }
    }, "engine-fixture-listen").apply { isDaemon = true; start() }

    fun page(name: String, gated: Boolean = false): Page {
        require(name.matches(Regex("[a-z_]+")))
        val nonce = UUID.randomUUID().toString()
        val path = "/$nonce/$name.js"
        val title = "engine-$name-$nonce"
        val html = """<!doctype html><html><head><title>$title</title></head><body>
            <p>エンジン操作専用文書</p><script>window.__engineFixture={nonce:'$nonce',finished:false,scriptRuns:0};</script>
            <script src="http://127.0.0.1:${server.localPort}$path"></script>
            <script>window.__engineFixture.finished=true;</script></body></html>""".trimIndent()
        val url = "data:text/html;charset=utf-8;base64," + Base64.encodeToString(html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return Page(name, nonce, title, url, path, gated).also { pages[path] = it }
    }

    fun requests(page: Page): Int = page.hits.get()

    fun releaseAndAwait(page: Page) {
        page.gate.countDown()
        assertTrue("fixture 応答の解放が完了しません", page.responded.await(5, TimeUnit.SECONDS))
    }

    private fun respond(socket: Socket) {
        var page: Page? = null
        try {
            socket.soTimeout = 5_000
            socket.use {
                val request = ByteArrayOutputStream()
                val input = socket.getInputStream()
                var tail = 0
                var complete = false
                while (request.size() < 8192) {
                    val next = input.read()
                    if (next < 0) return
                    request.write(next)
                    tail = (tail shl 8) or next
                    if (tail == 0x0d0a0d0a) { complete = true; break }
                }
                if (!complete) return
                val path = request.toString("US-ASCII").lineSequence().first().split(' ').getOrNull(1)
                page = path?.let(pages::get)
                val selected = page
                if (selected == null) {
                    socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val hit = selected.hits.incrementAndGet()
                if (!selected.gate.await(20, TimeUnit.SECONDS)) return
                val body = "window.__engineFixture.scriptRuns++;window.__engineFixture.marker='${selected.name}:$hit';".toByteArray()
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\nCache-Control: no-store\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    write(body)
                    flush()
                }
            }
        } catch (_: IOException) {
            // stopLoadingで閉じた専用socketへの書込み失敗は、fixtureの正常な取消経路。
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            page?.responded?.countDown()
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pages.values.forEach { it.gate.countDown() }
        server.close()
        sockets.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        listener.join(2_000)
        assertFalse("fixture listener が終了しません", listener.isAlive)
        assertTrue("fixture 応答処理が終了しません", executor.awaitTermination(3, TimeUnit.SECONDS))
    }
}
