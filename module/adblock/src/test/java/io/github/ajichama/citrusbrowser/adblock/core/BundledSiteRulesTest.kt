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
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementContainer
import io.github.ajichama.citrusbrowser.adblock.filter.unified.whenever
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.net.URI

class BundledSiteRulesTest {
    private val decoded = bundledAsset().bufferedReader().use { AbpFilterDecoder().decode(it, null) }
    private val blocker = FilterContainer().also { container -> decoded.blackList.forEach(container::plusAssign) }

    @Test fun observedAdLoadersAreBlockedOnlyAsScriptsOnTheObservedSite() {
        val loaders = listOf("https://anymind360.com/js/20213/ats.js", "https://html-load.com/loader.min.js")
        for (loader in loaders) {
            assertNotNull(loader, blocker[request(loader, "https://blog.esuteru.com/", ContentRequest.TYPE_SCRIPT)])
            assertNotNull(loader, blocker[request(loader, "http://blog.esuteru.com/archives/10586518.html", ContentRequest.TYPE_SCRIPT)])
            assertNull(loader, blocker[request(loader, "https://unrelated.test/", ContentRequest.TYPE_SCRIPT)])
            assertNull(loader, blocker[request(loader, "https://notblog.esuteru.com/", ContentRequest.TYPE_SCRIPT)])
            assertNull(loader, blocker[request(loader, "https://blog.esuteru.com/", ContentRequest.TYPE_IMAGE)])
            assertNull(loader, blocker[request(loader, "https://blog.esuteru.com/", ContentRequest.TYPE_DOCUMENT)])
        }
    }

    @Test fun articleImagesCoreScriptsAndCommentsAreNotBlockedByTheSiteFix() {
        val page = "http://blog.esuteru.com/archives/10586518.html"
        for ((url, type) in listOf(page to ContentRequest.TYPE_DOCUMENT,
            "https://livedoor.sp.blogimg.jp/hatima/imgs/a/b/article.jpg" to ContentRequest.TYPE_IMAGE,
            "https://parts.blog.livedoor.jp/js/lite2/main.js" to ContentRequest.TYPE_SCRIPT,
            "https://parts.blog.livedoor.jp/js/jquery-3.7.1.min.js" to ContentRequest.TYPE_SCRIPT,
            "http://blog.esuteru.com/archives/10586518/comments" to ContentRequest.TYPE_XHR)) {
            assertNull(url, blocker[request(url, page, type)])
        }
    }

    @Test fun observedCosmeticRulesAreSiteScopedAndTheAssetHasANewRevision() {
        assertEquals("2", decoded.filterInfo.version)
        assertEquals(6, decoded.elementList.size)
        val elements = ElementContainer().also { container -> decoded.elementList.forEach(container::plusAssign) }
        val cosmetic = CosmeticFiltering(FilterContainer(), elements)
        val selectors = elements[uri("https://blog.esuteru.com/"), true].map { it.selector }
        assertTrue(selectors.containsAll(listOf("#ad", "[data-cptid]", ".blogroll_ads", ".ad-siteRanking", ".ads-articleTop")))
        assertNotNull(cosmetic.loadScript(uri("https://blog.esuteru.com/")))
        assertNotNull(cosmetic.loadScript(uri("http://blog.esuteru.com/archives/10586518.html")))
        assertNull(cosmetic.loadScript(uri("https://unrelated.test/")))
        assertNull(cosmetic.loadScript(uri("https://notblog.esuteru.com/")))
        assertNull(cosmetic.loadScript(uri("https://esuteru.com/")))
    }

    @Test fun nativeHasIsAcceptedWithoutTreatingProceduralRulesAsOrdinaryCssOrNetworkFilters() {
        val rules = AbpFilterDecoder().decode(listOf(
            "blog.esuteru.com##.article-body > blockquote:has(> .amz-card)",
            "blog.esuteru.com#?#blockquote:-abp-has(.amz-card)",
            "blog.esuteru.com##blockquote:has-text(広告)",
            "blog.esuteru.com##+js(remove-node-text,script,ads)",
        ).joinToString("\n").reader().buffered(), null)
        assertEquals(listOf(".article-body > blockquote:has(> .amz-card)"),
            rules.elementList.filterNot { Scriptlets.isStored(it.selector) }.map { it.selector })
        // scriptlet は CSS ではなく、正規化した呼び出しとして保存する（第3段階）。
        assertEquals(listOf(
            listOf(Scriptlets.PROCEDURAL_CALL, "blockquote:-abp-has(.amz-card)"),
            listOf(Scriptlets.PROCEDURAL_CALL, "blockquote:has-text(広告)"),
            listOf("remove-node-text", "script", "ads"),
        ), rules.elementList.filter { Scriptlets.isStored(it.selector) }.map { Scriptlets.decode(it.selector) })
        assertTrue(rules.blackList.isEmpty())
        assertTrue(rules.whiteList.isEmpty())
    }

    private fun request(url: String, page: String, type: Int) = ContentRequest(uri(url), uri(page), type, true)

    private fun uri(value: String): Uri {
        val parsed = URI(value)
        return Mockito.mock(Uri::class.java).also {
            whenever(it.toString()).thenReturn(value)
            whenever(it.host).thenReturn(parsed.host)
            whenever(it.schemeSpecificPart).thenReturn(parsed.rawSchemeSpecificPart)
        }
    }

    private fun bundledAsset() = listOf(File("src/main/assets/adblock/citrus_filter.txt"),
        File("module/adblock/src/main/assets/adblock/citrus_filter.txt")).firstOrNull(File::isFile)
        ?: error("内蔵フィルタの main asset が見つかりません。")
}
