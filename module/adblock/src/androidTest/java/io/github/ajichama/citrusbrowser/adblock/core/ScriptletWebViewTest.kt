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

    @Test fun secondBatchScriptletsDefuseNetworkPopupTimerJsonAndAttributes() {
        install(
            "blog.esuteru.com##+js(nowoif, popunder)",
            "blog.esuteru.com##+js(nowoif, decoyAd, 5)",
            "blog.esuteru.com##+js(no-fetch-if, adserver.example, emptyObj)",
            "blog.esuteru.com##+js(no-xhr-if, method:POST beacon.example)",
            "blog.esuteru.com##+js(nano-stb, slowAd, 2000, 0.05)",
            "blog.esuteru.com##+js(json-prune, ads data.[].tracking, ads)",
            "blog.esuteru.com##+js(ra, onclick, #link)",
            "blog.esuteru.com##+js(set-cookie, citrus_consent, accepted)",
            "blog.esuteru.com##+js(noeval-if, adEval)",
            "blog.esuteru.com##+js(aost, citrusStackProbe, inlineScript)",
        )
        load("""
            <a id="link" href="#" onclick="r.clicked=1">リンク</a>
            <script>
              window.r = {};
              r.popup = window.open('https://popunder.example/x') === null;
              r.decoy = (function () { var w = window.open('https://decoyAd.example/'); return !!w && w !== window; })();
              r.json = JSON.stringify(JSON.parse('{"ads":[1],"keep":2,"data":[{"tracking":1,"v":1}]}'));
              r.jsonUntouched = JSON.stringify(JSON.parse('{"keep":2}'));
              r.evalAd = eval('"adEval"');
              r.evalOk = eval('1+1');
              try { citrusStackProbe; r.aost = 'not aborted'; } catch (e) { r.aost = e instanceof ReferenceError ? 'aborted' : 'other'; }
              r.cookie = document.cookie.indexOf('citrus_consent=accepted') !== -1;
              var started = Date.now();
              setTimeout(function () { /* slowAd */ r.slow = Date.now() - started; }, 2000);
              fetch('https://adserver.example/ad.json').then(function (res) { return res.text(); }).then(function (t) { r.fetch = t; });
              var x = new XMLHttpRequest();
              x.open('POST', 'https://beacon.example/collect');
              x.onload = function () { r.xhr = x.status + ':' + x.readyState; };
              x.send('data');
            </script>""")
        awaitTrue("window.r && r.fetch !== undefined && r.xhr !== undefined && r.slow !== undefined")
        assertEquals("true", evaluate("r.popup && r.decoy"))
        assertEquals("true", evaluate("r.json === '{\"keep\":2,\"data\":[{\"v\":1}]}' && r.jsonUntouched === '{\"keep\":2}'"))
        assertEquals("true", evaluate("r.evalAd === undefined && r.evalOk === 2"))
        assertEquals("\"aborted\"", evaluate("r.aost"))
        assertEquals("true", evaluate("r.cookie"))
        assertEquals("\"{}|200:4\"", evaluate("r.fetch + '|' + r.xhr"))
        assertEquals("true", evaluate("r.slow < 1000"))
        // remove-attr は解析完了後に属性を外す。
        awaitTrue("!document.querySelector('#link').hasAttribute('onclick')")
    }

    @Test fun proceduralFiltersHideRemoveAndStyleWithoutTouchingTheArticle() {
        install(
            "blog.esuteru.com##.card:has-text(スポンサー)",
            "blog.esuteru.com##.label:has-text(/^PR$/):upward(.box)",
            "blog.esuteru.com##body:style(overflow: auto !important)",
            "blog.esuteru.com##.gone:remove()",
            "blog.esuteru.com##a.attr-target:remove-attr(onclick)",
            "blog.esuteru.com##:xpath(//div[@id='xp'])",
            "blog.esuteru.com##div:matches-attr(data-ad=/^slot/)",
            "blog.esuteru.com##.late:has-text(後から)",
            "blog.esuteru.com##.card:has-text(除外)",
            "blog.esuteru.com#@#.card:has-text(除外)",
            "blog.esuteru.com##.box2:has(.inner:has-text(広告))",
            "blog.esuteru.com##.wrap:has-text(枠) :is(.x, .y)",
            "blog.esuteru.com##.card:has-text(普通):not(#c2)",
        )
        load("""
            <div class="card" id="c1">スポンサー記事</div><div class="card" id="c2">普通の記事</div>
            <div class="box" id="b1"><div><span class="label">PR</span></div></div>
            <div class="box" id="b2"><span class="label">PRではない</span></div>
            <div class="gone" id="g1">消える</div>
            <a class="attr-target" id="a1" href="#" onclick="return false">リンク</a>
            <div id="xp">xpath</div><div data-ad="slot-1" id="m1">枠</div><div data-ad="article" id="m2">本文の枠</div>
            <div class="card" id="c3">除外する広告</div>
            <div class="box2" id="n1"><p class="inner">広告です</p></div><div class="box2" id="n2"><p class="inner">記事</p></div>
            <div class="wrap" id="w1"><span class="x" id="wx">枠</span><span class="z" id="wz">本文</span></div>
            <article id="article">記事本文</article>""")
        val hidden = "['c1','b1','xp','m1','n1','wx'].every(id=>getComputedStyle(document.getElementById(id)).display==='none')"
        awaitTrue(hidden)
        assertEquals("true", evaluate("['c2','b2','m2','c3','n2','w1','wz','article'].every(id=>getComputedStyle(document.getElementById(id)).display!=='none')"))
        assertEquals("true", evaluate("document.getElementById('g1')===null && !document.getElementById('a1').hasAttribute('onclick')"))
        assertEquals("\"auto\"", evaluate("getComputedStyle(document.body).overflow"))
        // 後から追加した要素も、要素の追加をきっかけに再評価して隠す。
        evaluate("const late=document.createElement('div');late.className='late';late.id='late';late.textContent='後から来た広告';document.body.appendChild(late)")
        awaitTrue("getComputedStyle(document.getElementById('late')).display==='none'")
        assertEquals("\"記事本文\"", evaluate("document.getElementById('article').textContent"))
    }

    @Test fun trustedScriptletsFromATrustedListReplaceTextAndStoreArbitraryValues() {
        install(
            "blog.esuteru.com##+js(rpnt, script, adblockDetected = true, adblockDetected = false)",
            "blog.esuteru.com##+js(trusted-set-local-storage-item, citrusPrefs, {\"ads\":false})",
            "blog.esuteru.com##+js(trusted-set-local-storage-item, citrusSeen, \$now\$)",
            source = "https://ublockorigin.github.io/uAssets/filters/filters.txt",
        )
        load("""<script>var adblockDetected = true; window.r = { detected: adblockDetected,
            prefs: localStorage.getItem('citrusPrefs'), seen: Number(localStorage.getItem('citrusSeen')) > 0, done: true };</script>""")
        awaitTrue("window.r && r.done === true")
        assertEquals("true", evaluate("r.detected === false && r.prefs === '{\"ads\":false}' && r.seen"))
    }

    @Test fun silentAudioResourceIsPlayableByTheWebView() {
        val bytes = instrumentation.targetContext.assets.open("adblock/noop-0.1s.mp3").use { it.readBytes() }
        val data = "data:audio/mpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        load("""<script>window.r = {}; var a = new Audio();
            a.onloadedmetadata = function () { r.duration = a.duration; r.done = true; };
            a.onerror = function () { r.error = true; r.done = true; };
            a.src = '$data';</script>""")
        awaitTrue("window.r && r.done === true")
        assertEquals("true", evaluate("!r.error && r.duration > 0.05 && r.duration < 0.5"))
    }

    @Test fun thirdBatchPrunesFetchAndXhrJsonAndReplacesArguments() {
        install(
            "blog.esuteru.com##+js(json-prune-fetch-response, ads, , propsToMatch, data:application/json)",
            "blog.esuteru.com##+js(json-prune-xhr-response, ads, , propsToMatch, data:application/json)",
            "blog.esuteru.com##+js(trusted-replace-argument, citrusTarget, 0, json:\"replaced\")",
            "blog.esuteru.com##+js(trusted-replace-argument, String.prototype.split, this, repl:/ads=1/ads=0/)",
            source = "https://ublockorigin.github.io/uAssets/filters/filters.txt",
        )
        load("""<script>
            window.r = {};
            window.citrusTarget = function (a) { return a; };
            r.arg = citrusTarget('original');
            r.split = 'x&ads=1'.split('&').join('&');
            var body = 'data:application/json,' + encodeURIComponent('{"ads":1,"keep":2}');
            fetch(body).then(function (res) { return res.json(); }).then(function (j) { r.fetch = JSON.stringify(j); });
            var x = new XMLHttpRequest();
            x.open('GET', body);
            x.onload = function () { r.xhr = x.responseText; };
            x.send();
            </script>""")
        awaitTrue("window.r && r.fetch !== undefined && r.xhr !== undefined")
        assertEquals("true", evaluate("r.arg === 'replaced' && r.split === 'x&ads=0'"))
        assertEquals("true", evaluate("r.fetch === '{\"keep\":2}' && r.xhr === '{\"keep\":2}'"))
    }

    @Test fun exceptionAndEmptyResultsInjectNothing() {
        install("blog.esuteru.com##+js(set, citrusAdFlag, false)", "blog.esuteru.com#@#+js()")
        load("<script>window.r = { flag: typeof window.citrusAdFlag, done: true };</script>")
        awaitTrue("window.r && r.done === true")
        assertEquals("\"undefined\"", evaluate("r.flag"))
    }

    private fun install(vararg lines: String, source: String? = null) {
        val filters = AbpFilterDecoder().decode(lines.joinToString("\n").reader().buffered(), source).elementList
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
