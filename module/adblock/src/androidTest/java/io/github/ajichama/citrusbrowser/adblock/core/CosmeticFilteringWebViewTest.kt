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

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementContainer
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 外部通信なしの実WebViewで、後挿入広告・本文保持・CSS優先順位を確認する。 */
@RunWith(AndroidJUnit4::class)
class CosmeticFilteringWebViewTest {
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

    @Test fun bundledMobileSlotsAndLateInlineImportantAdsAreHiddenWithoutRemovingArticleDom() {
        // 2026-11-17 の公開 mobile/PC HTML で観測した広告属性だけを使う独自の最小 fixture。
        load("""<div id="ad" style="display:block!important">広告枠</div>
            <div data-cptid="1436140" style="display:block">広告枠</div><ul class="blogroll_ads"><li>広告</li></ul>
            <div class="google-user-ad-728 ad-center">広告枠</div><div class="ad-siteRanking">広告枠</div>
            <article id="article">記事本文</article><input id="comment" value="入力を保持">""")
        evaluate("window.pageMarker=42")
        val bundled = instrumentation.targetContext.assets.open("adblock/citrus_filter.txt").bufferedReader().use {
            AbpFilterDecoder().decode(it, null)
        }
        inject(cosmetic(bundled.elementList))
        awaitTrue("""Array.from(document.querySelectorAll('#ad,[data-cptid],.blogroll_ads,.google-user-ad-728,.ad-siteRanking'))
            .every(n=>getComputedStyle(n).display==='none')""")
        evaluate("""const late=document.createElement('div');late.id='ad';late.style.setProperty('display','block','important');
            late.textContent='後挿入広告';document.body.appendChild(late);""")
        awaitTrue("Array.from(document.querySelectorAll('#ad')).length===2 && Array.from(document.querySelectorAll('#ad')).every(n=>getComputedStyle(n).display==='none')")
        assertEquals("true", evaluate("document.querySelector('#article').textContent==='記事本文' && getComputedStyle(document.querySelector('#article')).display!=='none' && document.querySelector('#comment').value==='入力を保持' && window.pageMarker===42"))
    }

    @Test fun classChangesReleaseInlineOverridesExceptionsRemainVisibleAndReinjectionDoesNotAccumulateStyles() {
        load("""<div id="switch" class="ordinary">通常要素</div><div id="forced" class="advert" style="display:block!important">広告</div>
            <div id="exception" class="generic">例外</div><input id="comment" value="保持">""")
        val filter = rules("##.generic", "blog.esuteru.com#@#.generic", "blog.esuteru.com##.advert")
        inject(filter)
        awaitTrue("getComputedStyle(document.querySelector('#forced')).display==='none'")
        evaluate("document.querySelector('#switch').className='advert'")
        awaitTrue("getComputedStyle(document.querySelector('#switch')).display==='none'")
        evaluate("document.querySelector('#forced').className='ordinary';document.querySelector('#switch').className='ordinary'")
        awaitTrue("getComputedStyle(document.querySelector('#forced')).display==='block' && getComputedStyle(document.querySelector('#switch')).display!=='none'")
        assertEquals("true", evaluate("document.querySelector('#forced').style.getPropertyPriority('display')==='important' && getComputedStyle(document.querySelector('#exception')).display!=='none'"))
        repeat(3) { inject(filter) }
        assertEquals("1", evaluate("document.querySelectorAll('style[data-citrus-cosmetic]').length"))
        assertEquals("\"保持\"", evaluate("document.querySelector('#comment').value"))
    }

    @Test fun nativeHasHidesLateAffiliateCardsAndPreservesUnrelatedArticleQuotes() {
        load("""<div class="article-body"><blockquote id="quote">通常引用</blockquote>
            <blockquote id="affiliate">カードの挿入先</blockquote></div><input id="comment" value="保持">""")
        assumeTrue("この WebView は標準 CSS :has 未対応です", evaluate("CSS.supports('selector(:has(*))')") == "true")
        inject(rules("blog.esuteru.com##.article-body > blockquote:has(> .amz-card)"))
        evaluate("const card=document.createElement('div');card.className='amz-card';document.querySelector('#affiliate').appendChild(card)")
        awaitTrue("getComputedStyle(document.querySelector('#affiliate')).display==='none'")
        assertEquals("true", evaluate("document.querySelectorAll('blockquote').length===2 && getComputedStyle(document.querySelector('#quote')).display!=='none'"))
        evaluate("document.querySelector('.amz-card').remove()")
        awaitTrue("getComputedStyle(document.querySelector('#affiliate')).display!=='none'")
        assertEquals("\"保持\"", evaluate("document.querySelector('#comment').value"))
    }

    @Test fun invalidSelectorsCannotInjectCssOrDisableTheValidSelectors() {
        load("""<div class="valid">広告</div><div title="広告'引用">広告</div><article id="article">記事本文</article>""")
        inject(rules("blog.esuteru.com##[invalid", "blog.esuteru.com##body{display:none}/*",
            "blog.esuteru.com##.valid", "blog.esuteru.com##div[title=\"広告'引用\"]"))
        awaitTrue("getComputedStyle(document.querySelector('.valid')).display==='none' && getComputedStyle(document.querySelector('[title]')).display==='none'")
        assertEquals("true", evaluate("getComputedStyle(document.body).display!=='none' && getComputedStyle(document.querySelector('#article')).display!=='none'"))
    }

    @Test fun cspBlockingStyleTagsDoesNotMakeMatchingInitialAndLateAdsVisible() {
        load("""<div class="advert">広告</div><article id="article">本文</article>""",
            head = """<meta http-equiv="Content-Security-Policy" content="style-src 'none'">""")
        inject(rules("blog.esuteru.com##.advert"))
        awaitTrue("getComputedStyle(document.querySelector('.advert')).display==='none'")
        evaluate("const late=document.createElement('div');late.className='advert';document.body.appendChild(late)")
        awaitTrue("document.querySelectorAll('.advert').length===2 && Array.from(document.querySelectorAll('.advert')).every(n=>getComputedStyle(n).display==='none')")
        assertEquals("true", evaluate("getComputedStyle(document.querySelector('#article')).display!=='none'"))
    }

    @Test fun overflowingMutationBatchesStillReachTheLastInlineImportantAd() {
        val unrelated = (0 until 600).joinToString("") { "<div style='display:block!important'>通常要素$it</div>" }
        load("$unrelated<div id='initial-ad' class='advert' style='display:block!important'>広告</div><article id='article'>本文</article>")
        inject(rules("blog.esuteru.com##.advert"))
        awaitTrue("getComputedStyle(document.querySelector('#initial-ad')).display==='none'")
        // 一回の JS 実行で 128 record/512 candidate を超え、最後の候補が直前の候補を押し流すケース。
        evaluate("""for(let i=0;i<700;i++){
            const item=document.createElement('div');item.textContent='通常要素';item.style.setProperty('display','block','important');
            document.body.appendChild(item);
        }
        const last=document.createElement('div');last.id='last-ad';last.className='advert';
        last.style.setProperty('display','block','important');document.body.appendChild(last);""")
        awaitTrue("getComputedStyle(document.querySelector('#last-ad')).display==='none'")
        assertEquals("true", evaluate("getComputedStyle(document.querySelector('#article')).display!=='none' && document.querySelectorAll('.advert').length===2"))
    }

    @Test fun descendantSiblingAndTextChangesRecheckContextDependentInlineOverrides() {
        load("""<section id="ancestor" style="display:block!important"><div><span id="descendant">通常要素</span></div></section>
            <div id="lead">通常要素</div><div id="sibling" class="slot" style="display:block!important">枠</div>
            <div id="empty" style="display:block!important">本文</div>""")
        assumeTrue("この WebView は標準 CSS :has 未対応です", evaluate("CSS.supports('selector(:has(*))')") == "true")
        inject(rules("blog.esuteru.com##section:has(.ad)", "blog.esuteru.com##.flag + .slot", "blog.esuteru.com###empty:empty"))
        evaluate("document.querySelector('#descendant').className='ad';document.querySelector('#lead').className='flag';document.querySelector('#empty').firstChild.data=''")
        awaitTrue("['ancestor','sibling','empty'].every(id=>getComputedStyle(document.getElementById(id)).display==='none')")
        evaluate("document.querySelector('#descendant').className='ordinary';document.querySelector('#lead').className='ordinary';document.querySelector('#empty').firstChild.data='本文'")
        awaitTrue("['ancestor','sibling','empty'].every(id=>getComputedStyle(document.getElementById(id)).display==='block')")
        assertEquals("\"本文\"", evaluate("document.querySelector('#empty').textContent"))
    }

    private fun load(body: String, head: String = "") {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
            web.loadDataWithBaseURL("http://blog.esuteru.com/", "<html><head><meta charset=utf-8>$head</head><body>$body</body></html>", "text/html", "UTF-8", null)
        }
        assertTrue("fixture の読込が完了しません", loaded.await(10, TimeUnit.SECONDS))
    }

    private fun inject(filter: CosmeticFiltering) {
        val script = requireNotNull(filter.loadScript(Uri.parse("http://blog.esuteru.com/")))
        evaluate(script)
    }

    private fun rules(vararg lines: String) = cosmetic(AbpFilterDecoder().decode(lines.joinToString("\n").reader().buffered(), null).elementList)

    private fun cosmetic(filters: List<io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementFilter>) = CosmeticFiltering(
        FilterContainer(), ElementContainer().also { container -> filters.forEach(container::plusAssign) })

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
        assertEquals("表示条件を確認できません: $script", "true", evaluate(script))
    }
}

/** テスト APK 内にだけ存在し、本体 Activity やユーザー設定を変更しない。 */
class CosmeticTestActivity : Activity() {
    lateinit var web: WebView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        setContentView(web)
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
