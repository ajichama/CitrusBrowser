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
package io.github.ajichama.citrusbrowser.adblock.core

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 遮断した IMA SDK を、独自の代替スクリプト（google-ima.js）に差し替え、ページの公開 API どおりの手順が本編の再開まで進むか。 */
@RunWith(AndroidJUnit4::class)
class SurrogateWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<CosmeticTestActivity>
    private lateinit var web: WebView

    @Before fun launch() {
        scenario = ActivityScenario.launch(CosmeticTestActivity::class.java)
        scenario.onActivity { web = it.web }
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun blockedImaSdkIsReplacedAndThePlayerResumesContent() {
        val set = AbpFilterDecoder().decode(
            "||imasdk.googleapis.com/js/sdkloader/ima3.js\$script,redirect=google-ima.js,domain=blog.esuteru.com".reader().buffered(),
            "https://ublockorigin.github.io/uAssets/filters/filters.txt",
        )
        val rules = NetworkFilterRules(set.blackList, set.whiteList, set.elementDisableFilter)
        val blocker = Blocker(FilterContainer(), rules.allow, rules.deny, rules.important, rules.redirectRules)
        val context = instrumentation.targetContext
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val page = Uri.parse("http://blog.esuteru.com/")
                    val filter = blocker.isBlock(ContentRequest(request.url, page, ContentRequest.TYPE_SCRIPT, true)) ?: return null
                    val script = Surrogates.script(context, filter.contentType) ?: return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    return WebResourceResponse("application/javascript", "UTF-8", ByteArrayInputStream(script))
                }

                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
            web.loadDataWithBaseURL("http://blog.esuteru.com/", """<html><body><div id="ad"></div><video id="v"></video>
                <script src="https://imasdk.googleapis.com/js/sdkloader/ima3.js"></script>
                <script>
                window.r = { events: [] };
                var ima = google.ima;
                var container = new ima.AdDisplayContainer(document.getElementById('ad'), document.getElementById('v'));
                var loader = new ima.AdsLoader(container);
                loader.addEventListener(ima.AdErrorEvent.Type.AD_ERROR, function () { r.events.push('error'); }, false);
                loader.addEventListener(ima.AdsManagerLoadedEvent.Type.ADS_MANAGER_LOADED, function (e) {
                  var manager = e.getAdsManager(document.getElementById('v'), new ima.AdsRenderingSettings());
                  manager.addEventListener(ima.AdEvent.Type.CONTENT_RESUME_REQUESTED, function () { r.events.push('resume'); });
                  manager.addEventListener(ima.AdEvent.Type.ALL_ADS_COMPLETED, function () { r.events.push('all'); r.done = true; });
                  container.initialize();
                  manager.init(640, 360, ima.ViewMode.NORMAL);
                  manager.start();
                }, false);
                var request = new ima.AdsRequest();
                request.adTagUrl = 'https://example.test/vast';
                loader.requestAds(request);
                </script></body></html>""", "text/html", "UTF-8", null)
        }
        assertTrue("fixture の読込が完了しません", loaded.await(10, TimeUnit.SECONDS))
        awaitTrue("window.r && r.done === true")
        assertEquals("\"resume,all\"", evaluate("r.events.join(',')"))
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("JavaScript の結果が返りません", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun awaitTrue(script: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        do {
            if (evaluate(script) == "true") return
            Thread.sleep(40)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertEquals("条件を確認できません: $script", "true", evaluate(script))
    }
}
