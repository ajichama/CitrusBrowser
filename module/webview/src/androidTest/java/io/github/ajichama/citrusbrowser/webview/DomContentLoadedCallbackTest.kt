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
package io.github.ajichama.citrusbrowser.webview

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** DOMContentLoaded の通知を、JavaScript インターフェースではなく WebMessageListener で受け取れるか。 */
@RunWith(AndroidJUnit4::class)
class DomContentLoadedCallbackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<PageColorTestActivity>
    private lateinit var activity: PageColorTestActivity
    private lateinit var web: NormalWebView

    @Before fun launch() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        scenario = ActivityScenario.launch(PageColorTestActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After fun close() {
        if (::web.isInitialized) instrumentation.runOnMainSync {
            activity.container.removeView(web)
            web.destroy()
        }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun domContentLoadedIsDeliveredOnlyWithTheSecret() {
        val notified = AtomicInteger()
        val finished = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web = NormalWebView(activity)
            activity.container.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            web.settings.javaScriptEnabled = true
            web.setMyWebViewClient(object : CustomWebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    // 本体では読込の進み具合の通知から呼ぶ。ここでは文書の開始時に呼ぶ。
                    web.onPageDocumentStart()
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host != "slow.example") return null
                    // DOMContentLoaded を遅らせ、通知の登録が先に済むようにする。
                    Thread.sleep(1500)
                    return WebResourceResponse("application/javascript", "UTF-8", ByteArrayInputStream("window.slow = true;".toByteArray()))
                }

                override fun onDomContentLoaded(web: CustomWebView) {
                    notified.incrementAndGet()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    finished.countDown()
                }
            })
            web.loadDataWithBaseURL("https://callback.example/",
                "<html><body><p>test</p><script src=\"https://slow.example/a.js\"></script></body></html>", "text/html", "UTF-8", null)
        }
        assertTrue("テストページの読込が完了しません", finished.await(15, TimeUnit.SECONDS))
        // 窓口は文字列だけを送れる WebMessageListener で、JavaScript インターフェースのメソッドは持たない。
        assertEquals("\"function,undefined\"", evaluate("typeof _momo_browser_interface.postMessage + ',' + typeof _momo_browser_interface.onDomContentLoaded"))
        waitUntil { notified.get() >= 1 }
        assertEquals(1, notified.get())
        // ページのスクリプトが合言葉なしで送っても、通知として扱わない。
        evaluate("_momo_browser_interface.postMessage('wrong'); 'sent'")
        Thread.sleep(500)
        assertEquals(1, notified.get())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("通知が届きません", condition())
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("JavaScript の結果が返りません", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }
}
