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

package io.github.ajichama.citrusbrowser.adblock.core

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementContainer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 外部通信なしの実 WebView で、文書開始時の注入と同期ブリッジ、各 scriptlet の効果を確かめる。
 * 本体と同じ固定スクリプト（assets/adblock/scriptlets.js）とドメイン照合を使う。
 */
@RunWith(AndroidJUnit4::class)
class ScriptletWebViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<CosmeticTestActivity>
    private lateinit var web: WebView

    @Before fun launch() {
        assumeTrue("この WebView は文書開始時の注入に未対応です", WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        scenario = ActivityScenario.launch(CosmeticTestActivity::class.java)
        scenario.onActivity { web = it.web }
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun scriptletsRunBeforePageScriptsAndLeaveUnrelatedScriptsWorking() {
        install(
            "blog.esuteru.com##+js(set, citrusAdFlag, false)",
            "blog.esuteru.com##+js(aopr, adProbe)",
            "blog.esuteru.com##+js(acs, document.title, adMarkerScript)",
            "blog.esuteru.com##+js(nostif, adTimer)",
            "blog.esuteru.com##+js(nosiif, adInterval)",
            "blog.esuteru.com##+js(rmnt, script, adInline)",
            "blog.esuteru.com##+js(set-local-storage-item, citrusConsent, accepted)",
            "blog.esuteru.com##+js(aeld, click, adHandler)",
            "other.test##+js(set, wrongHost, true)",
            "blog.esuteru.com##+js(acs, citrusInlineOnly)",
        )
        load("""
            <script>
              window.r = {};
              r.bridge = typeof window['${ScriptletInjection.interfaceName}'];
              r.set = window.citrusAdFlag === false;
              window.citrusAdFlag = true;
              r.setKept = window.citrusAdFlag === false;
              r.wrongHost = typeof window.wrongHost;
              try { r.aopr = 'before'; adProbe; r.aopr = 'not aborted'; } catch (e) { r.aopr = e instanceof ReferenceError ? 'aborted' : 'other'; }
              r.storage = localStorage.getItem('citrusConsent');
            </script>
            <script>r.acsA = 'start'; /* adMarkerScript */ var t = document.title; r.acsA = 'finished';</script>
            <script>var t2 = document.title; r.acsB = 'finished';</script>
            <script>r.inline = 'adInline executed';</script>
            <script src="data:text/javascript,r.ext=1;citrusInlineOnly;r.ext=2"></script>
            <script>r.inl = 1; citrusInlineOnly; r.inl = 2;</script>
            <script>r.after = 'ran';
              setTimeout(function () { r.timer1 = 'adTimer ran'; }, 10);
              setTimeout(function () { r.timer2 = 'ok'; }, 10);
              var id = setInterval(function () { r.interval = 'adInterval ran'; }, 10);
              setTimeout(function () { clearInterval(id); r.done = true; }, 200);
              document.addEventListener('click', function () { /* adHandler */ r.click1 = 1; });
              document.addEventListener('click', function () { r.click2 = 1; });
              document.dispatchEvent(new Event('click'));
            </script>
            <article id="article">記事本文</article>""")
        awaitTrue("window.r && r.done === true")
        assertEquals("\"undefined\"", evaluate("r.bridge"))
        assertEquals("true", evaluate("r.set && r.setKept"))
        assertEquals("\"undefined\"", evaluate("r.wrongHost"))
        assertEquals("\"aborted\"", evaluate("r.aopr"))
        assertEquals("\"start|finished\"", evaluate("r.acsA + '|' + r.acsB"))
        assertEquals("\"undefined|ran\"", evaluate("String(r.inline) + '|' + r.after"))
        assertEquals("\"undefined|ok|undefined\"", evaluate("String(r.timer1) + '|' + r.timer2 + '|' + String(r.interval)"))
        assertEquals("\"accepted\"", evaluate("r.storage"))
        // 条件なしの acs は、外部のスクリプトを止めず、インラインのスクリプトだけを止める。
        assertEquals("\"2|1\"", evaluate("r.ext + '|' + r.inl"))
        assertEquals("\"undefined|1\"", evaluate("String(r.click1) + '|' + r.click2"))
        assertEquals("\"記事本文\"", evaluate("document.querySelector('#article').textContent"))
        // 本来のアクセサ（document.title）は、条件に合わないスクリプトから引き続き使える。
        assertEquals("true", evaluate("typeof document.title === 'string'"))
    }

    @Test fun exceptionAndEmptyResultsInjectNothing() {
        install("blog.esuteru.com##+js(set, citrusAdFlag, false)", "blog.esuteru.com#@#+js()")
        load("<script>window.r = { flag: typeof window.citrusAdFlag, done: true };</script>")
        awaitTrue("window.r && r.done === true")
        assertEquals("\"undefined\"", evaluate("r.flag"))
    }

    private fun install(vararg lines: String) {
        val filters = AbpFilterDecoder().decode(lines.joinToString("\n").reader().buffered(), null).elementList
        val cosmetic = CosmeticFiltering(FilterContainer(), ElementContainer().also { container -> filters.forEach(container::plusAssign) })
        val script = ScriptletInjection.script(instrumentation.targetContext)
        instrumentation.runOnMainSync {
            web.settings.domStorageEnabled = true
            web.addJavascriptInterface(ScriptletInjection.createBridge { href ->
                Scriptlets.toJson(cosmetic.scriptlets(Uri.parse(href)))
            }, ScriptletInjection.interfaceName)
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
        }
    }

    private fun load(body: String) {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
            web.loadDataWithBaseURL("http://blog.esuteru.com/", "<html><head><meta charset=utf-8><title>題名</title></head><body>$body</body></html>", "text/html", "UTF-8", null)
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

    private fun awaitTrue(script: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        do {
            if (evaluate(script) == "true") return
            Thread.sleep(40)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertEquals("条件を確認できません: $script", "true", evaluate(script))
    }
}
