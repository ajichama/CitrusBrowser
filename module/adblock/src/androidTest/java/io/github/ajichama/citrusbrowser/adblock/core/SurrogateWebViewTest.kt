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

/** 遮断した広告の資源を独自の代替資源に差し替え、ページの公開 API どおりの手順が先へ進むか。 */
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
        load("||imasdk.googleapis.com/js/sdkloader/ima3.js\$script,redirect=google-ima.js,domain=blog.esuteru.com", """
            <div id="ad"></div><video id="v"></video>
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
            </script>""")
        awaitTrue("window.r && r.done === true")
        assertEquals("\"resume,all\"", evaluate("r.events.join(',')"))
    }

    @Test fun adsenseSlotsQueuedBeforeTheShimAreMarkedUnfilled() {
        load("||googlesyndication.com^\$script,redirect=googlesyndication_adsbygoogle.js,domain=blog.esuteru.com", """
            <ins class="adsbygoogle" id="a"></ins><ins class="adsbygoogle" id="b"></ins><ins class="adsbygoogle" id="c"></ins>
            <script>
            (adsbygoogle = window.adsbygoogle || []).push({ google_ad_client: 'ca-pub-0', enable_page_level_ads: true });
            (adsbygoogle = window.adsbygoogle || []).push({});
            </script>
            <script src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"></script>
            <script>
            (adsbygoogle = window.adsbygoogle || []).push({});
            window.done = true;
            </script>""")
        awaitTrue("window.done === true")
        // ページ単位の設定は枠を消費せず、枠の push 2回で2枠に印が付く。3つ目は印なし。
        assertEquals("true", evaluate("window.adsbygoogle.loaded === true"))
        assertEquals("\"unfilled,unfilled,none\"", evaluate("['a','b','c'].map(function (id) { return document.getElementById(id).getAttribute('data-ad-status') || 'none'; }).join(',')"))
    }

    @Test fun adBlockDetectorsAlwaysReportNotDetected() {
        load("||cdn.example/fuckadblock.js\$script,redirect=fuckadblock.js-3.2.0,domain=blog.esuteru.com\n||cdn.example/blockadblock.js\$script,redirect=nofab.js,domain=blog.esuteru.com", """
            <script src="https://cdn.example/fuckadblock.js"></script>
            <script>
            window.r = [];
            fuckAdBlock.onDetected(function () { r.push('fab-detected'); }).onNotDetected(function () { r.push('fab-clean'); });
            var custom = new BlockAdBlock({ checkOnLoad: false });
            custom.on(true, function () { r.push('custom-detected'); });
            custom.on(false, function () { r.push('custom-clean'); });
            window.started = custom.check();
            window.again = custom.check();
            </script>""")
        awaitTrue("window.r && r.length === 2")
        assertEquals("true", evaluate("window.started === true && window.again === false"))
        assertEquals("\"custom-clean,fab-clean\"", evaluate("r.slice().sort().join(',')"))
    }

    @Test fun emptyVastAndVmapResponsesParseWithoutAds() {
        load("||ads.example/vast\$xhr,redirect=noopvast-3.0,domain=blog.esuteru.com\n||ads.example/vmap\$xhr,redirect=noopvmap-1.0,domain=blog.esuteru.com", """
            <script>
            function parse(url) {
              return fetch(url).then(function (r) { return r.text(); }).then(function (text) {
                var doc = new DOMParser().parseFromString(text, 'text/xml');
                if (doc.getElementsByTagName('parsererror').length) return 'error';
                var root = doc.documentElement;
                return root.localName + '@' + root.getAttribute('version') + ':' + root.children.length;
              });
            }
            Promise.all([parse('https://ads.example/vast'), parse('https://ads.example/vmap')]).then(function (v) { window.result = v.join(','); });
            </script>""", type = ContentRequest.TYPE_XHR)
        awaitTrue("typeof window.result === 'string'")
        assertEquals("\"VAST@3.0:0,VMAP@1.0:0\"", evaluate("window.result"))
    }

    @Test fun silentMp4PlaysToTheEnd() {
        instrumentation.runOnMainSync { web.settings.mediaPlaybackRequiresUserGesture = false }
        load("||ads.example/preroll\$media,redirect=noop-1s.mp4,domain=blog.esuteru.com", """
            <video id="v" muted playsinline></video>
            <script>
            window.r = {};
            var v = document.getElementById('v');
            v.addEventListener('loadedmetadata', function () { r.duration = v.duration; });
            v.addEventListener('ended', function () { r.ended = true; });
            v.addEventListener('error', function () { r.error = v.error && v.error.code; });
            v.src = 'https://ads.example/preroll.mp4';
            v.play().catch(function (e) { r.playError = String(e); });
            </script>""", type = ContentRequest.TYPE_MEDIA)
        awaitTrue("window.r && (r.ended === true || r.error !== undefined || r.playError !== undefined)", 8000)
        assertEquals("true", evaluate("r.ended === true && r.duration > 0.9 && r.duration < 1.1"))
    }

    private fun load(rules: String, body: String, type: Int = ContentRequest.TYPE_SCRIPT) {
        val set = AbpFilterDecoder().decode(rules.reader().buffered(), "https://ublockorigin.github.io/uAssets/filters/filters.txt")
        val network = NetworkFilterRules(set.blackList, set.whiteList, set.elementDisableFilter)
        val blocker = Blocker(FilterContainer(), network.allow, network.deny, network.important, network.redirectRules)
        val context = instrumentation.targetContext
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val page = Uri.parse("http://blog.esuteru.com/")
                    val filter = blocker.isBlock(ContentRequest(request.url, page, type, true)) ?: return null
                    val resource = Surrogates.resource(filter.contentType)
                        ?: return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    // 本体（AdBlockController.createDummy）と同じく、要求元のオリジンから読めるようにする。
                    return Surrogates.response(context, resource).allowingOrigin(requestOrigin(request.requestHeaders, page))
                }

                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
            web.loadDataWithBaseURL("http://blog.esuteru.com/", "<html><body>$body</body></html>", "text/html", "UTF-8", null)
        }
        assertTrue("fixture の読込が完了しません", loaded.await(10, TimeUnit.SECONDS))
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("JavaScript の結果が返りません", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun awaitTrue(script: String, timeoutMs: Long = 5000) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (evaluate(script) == "true") return
            Thread.sleep(40)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertEquals("条件を確認できません: $script", "true", evaluate(script))
    }
}
