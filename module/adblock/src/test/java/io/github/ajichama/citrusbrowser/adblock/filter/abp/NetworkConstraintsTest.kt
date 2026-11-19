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

package io.github.ajichama.citrusbrowser.adblock.filter.abp

import android.net.Uri
import io.github.ajichama.citrusbrowser.adblock.core.Blocker
import io.github.ajichama.citrusbrowser.adblock.core.ContentRequest
import io.github.ajichama.citrusbrowser.adblock.core.FilterContainer
import io.github.ajichama.citrusbrowser.adblock.core.NetworkFilterRules
import io.github.ajichama.citrusbrowser.adblock.filter.unified.NetworkRuleFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.UnifiedFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterReader
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterWriter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.whenever
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI

/** entity の $domain、denyallow、to、method、redirect-rule と、redirect の優先度・別名。 */
class NetworkConstraintsTest {
    @Test
    fun entityDomainsDenyallowToAndMethodNarrowTheRuleAndSurviveTheCache() {
        val blocker = blocker(roundTrip = true,
            "||ads.example^\$script,domain=example.*|~sub.example.com",
            "*\$script,3p,denyallow=googleapis.com,domain=site.test",
            "*\$image,domain=site.test,to=tracker.example|~good.tracker.example",
            "||api.example^\$xhr,method=post|put",
        )
        // entity: 公開サフィックスが違っても同じ名前のサイトに当たる。除外したサブドメインには当たらない。
        assertNotNull(blocker.isBlock(request("https://ads.example/a.js", "https://www.example.co.jp/")))
        assertNull(blocker.isBlock(request("https://ads.example/a.js", "https://sub.example.com/")))
        assertNull(blocker.isBlock(request("https://ads.example/a.js", "https://other.test/")))
        // denyallow: 許可したホストへの通信だけは遮断しない。
        assertNotNull(blocker.isBlock(request("https://cdn.ads.test/x.js", "https://site.test/")))
        assertNull(blocker.isBlock(request("https://ajax.googleapis.com/x.js", "https://site.test/")))
        // to: 要求先が条件に当たるときだけ。
        assertNotNull(blocker.isBlock(request("https://px.tracker.example/p.gif", "https://site.test/", ContentRequest.TYPE_IMAGE)))
        assertNull(blocker.isBlock(request("https://good.tracker.example/p.gif", "https://site.test/", ContentRequest.TYPE_IMAGE)))
        assertNull(blocker.isBlock(request("https://cdn.site.test/p.gif", "https://site.test/", ContentRequest.TYPE_IMAGE)))
        // method: 指定したメソッドだけ。
        assertNotNull(blocker.isBlock(request("https://api.example/v1", "https://page.test/", ContentRequest.TYPE_XHR, method = "POST")))
        assertNull(blocker.isBlock(request("https://api.example/v1", "https://page.test/", ContentRequest.TYPE_XHR, method = "GET")))
    }

    @Test
    fun invalidConstraintValuesDropTheWholeLine() {
        val set = decode(
            "||a.example^\$domain=ex*ample.com",
            "||a.example^\$denyallow=~b.com",
            "||a.example^\$denyallow=b.*",
            "||a.example^\$to=",
            "||a.example^\$method=get/post",
            "@@||a.example^\$elemhide,to=b.com",
        )
        assertTrue(set.blackList.isEmpty() && set.whiteList.isEmpty() && set.elementDisableFilter.isEmpty())
    }

    @Test
    fun redirectPriorityAliasesAndRedirectRuleOnlyChooseTheResourceOfAnotherBlock() {
        val blocker = blocker(roundTrip = true,
            "||x.example/a.js\$script,redirect=noopjs:5",
            "||y.example^\$image,redirect-rule=32x32.png",
            "||y.example/ads/\$image",
            "||z.example^\$script,redirect-rule=noopjs",
        )
        val redirected = requireNotNull(blocker.isBlock(request("https://x.example/a.js", "https://page.test/")))
        assertEquals(ContentRequest.REDIRECT_SCRIPT, redirected.contentType and ContentRequest.REDIRECT_MASK)
        // redirect-rule 自体は遮断しない。
        assertNull(blocker.isBlock(request("https://y.example/logo.png", "https://page.test/", ContentRequest.TYPE_IMAGE)))
        assertNull(blocker.isBlock(request("https://z.example/app.js", "https://page.test/")))
        // 別のルールで遮断したときは、redirect-rule の資源を返す。
        val image = requireNotNull(blocker.isBlock(request("https://y.example/ads/1.png", "https://page.test/", ContentRequest.TYPE_IMAGE)))
        assertEquals(ContentRequest.REDIRECT_IMAGE, image.contentType and ContentRequest.REDIRECT_MASK)
        // 自作の無音の音声。ファイルは MPEG-1 Layer III の無音フレーム（417バイト）を4つ並べたもの。
        val audio = decode("||a.example^\$media,redirect=noopmp3-0.1s").blackList.single()
        assertEquals(ContentRequest.REDIRECT_AUDIO, audio.contentType and ContentRequest.REDIRECT_MASK)
        val mp3 = listOf(java.io.File("src/main/assets/adblock/noop-0.1s.mp3"), java.io.File("module/adblock/src/main/assets/adblock/noop-0.1s.mp3"))
            .first { it.isFile }.readBytes()
        assertEquals(4 * 417, mp3.size)
        for (frame in 0 until 4) {
            assertEquals(listOf(0xFF, 0xFB, 0x90, 0xC0), (0 until 4).map { mp3[frame * 417 + it].toInt() and 0xFF })
        }
        // 未知の資源（代替スクリプト）は行ごと破棄する。
        assertTrue(decode("||g.example^\$script,redirect=google-ima.js").blackList.isEmpty())
    }

    private fun blocker(roundTrip: Boolean, vararg lines: String): Blocker {
        val set = decode(*lines)
        val deny = if (roundTrip) roundTrip(set.blackList) else set.blackList
        if (roundTrip) assertTrue(deny.filterIsInstance<NetworkRuleFilter>().size == set.blackList.size)
        val rules = NetworkFilterRules(deny, set.whiteList, set.elementDisableFilter)
        return Blocker(FilterContainer(), rules.allow, rules.deny, rules.important, rules.redirectRules)
    }

    private fun decode(vararg lines: String) = AbpFilterDecoder().decode(lines.joinToString("\n").reader().buffered(), null)

    private fun request(url: String, page: String, type: Int = ContentRequest.TYPE_SCRIPT, method: String = "GET") =
        ContentRequest(uri(url), uri(page), type, true, method)

    private fun uri(value: String): Uri {
        val parsed = URI(value)
        return Mockito.mock(Uri::class.java).also {
            whenever(it.toString()).thenReturn(value)
            whenever(it.host).thenReturn(parsed.host)
            whenever(it.schemeSpecificPart).thenReturn(parsed.rawSchemeSpecificPart)
        }
    }

    private fun roundTrip(filters: List<UnifiedFilter>): List<UnifiedFilter> {
        val output = ByteArrayOutputStream()
        FilterWriter().write(output, filters)
        val reader = FilterReader(ByteArrayInputStream(output.toByteArray()))
        assertTrue(reader.checkHeader())
        return reader.readAll().toList()
    }
}
