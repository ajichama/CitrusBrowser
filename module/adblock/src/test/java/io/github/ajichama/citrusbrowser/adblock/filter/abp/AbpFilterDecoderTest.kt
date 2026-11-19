package io.github.ajichama.citrusbrowser.adblock.filter.abp

import android.net.Uri
import io.github.ajichama.citrusbrowser.adblock.core.Blocker
import io.github.ajichama.citrusbrowser.adblock.core.ContentRequest
import io.github.ajichama.citrusbrowser.adblock.core.CosmeticFiltering
import io.github.ajichama.citrusbrowser.adblock.core.FilterContainer
import io.github.ajichama.citrusbrowser.adblock.core.Scriptlets
import io.github.ajichama.citrusbrowser.adblock.filter.unified.UnifiedFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementContainer
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.PlaneElementFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterReader
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterWriter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.whenever
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI

class AbpFilterDecoderTest {
    @Test
    fun blankLinesAndBomDoNotInterruptDecoding() {
        val reader = "\uFEFF\n \t \n[Adblock Plus 2.0]\n! Title: テスト\n\t\n||ads.example^\n".reader().buffered()
        val decoder = AbpFilterDecoder()
        assertTrue(decoder.checkHeader(reader, Charsets.UTF_8))
        val filters = decoder.decode(reader, null)
        assertEquals("テスト", filters.filterInfo.title)
        assertEquals(1, filters.blackList.size)
    }

    @Test
    fun matchingIsCaseInsensitiveUnlessMatchCaseIsSpecified() {
        val request = request("https://cdn.example/AD/BANNER.js")
        assertTrue(decode("/ad/banner.js").blackList.single().isMatch(request))
        assertFalse(decode("/ad/banner.js\$match-case").blackList.single().isMatch(request))
        assertTrue(decode("/ad/banner.js^\$~match-case").blackList.single().isMatch(request))
    }

    @Test
    fun domainIncludesSubdomainsButNotUnrelatedSuffixes() {
        val filter = decode("||ads.example^\$domain=Example.COM").blackList.single()
        assertTrue(filter.isMatch(request("https://ads.example/a.js", "https://news.example.com/")))
        assertFalse(filter.isMatch(request("https://ads.example/a.js", "https://notexample.com/")))
    }

    @Test
    fun domainExclusionIncludesItsSubdomains() {
        val filter = decode("||ads.example^\$domain=~example.com").blackList.single()
        assertFalse(filter.isMatch(request("https://ads.example/a.js", "https://news.example.com/")))
        assertTrue(filter.isMatch(request("https://ads.example/a.js", "https://other.example/")))
    }

    @Test
    fun mostSpecificDomainRestrictionWins() {
        val filter = decode("||ads.example^\$domain=example.com|~private.example.com|public.private.example.com")
            .blackList.single()
        assertTrue(filter.isMatch(request("https://ads.example/a.js", "https://news.example.com/")))
        assertFalse(filter.isMatch(request("https://ads.example/a.js", "https://news.private.example.com/")))
        assertTrue(filter.isMatch(request("https://ads.example/a.js", "https://news.public.private.example.com/")))
    }

    @Test
    fun thirdPartyAndExceptionRestrictionsArePreserved() {
        val set = decode(
            "||ads.example^\$third-party",
            "@@||ads.example/allowed.js\$script,domain=trusted.example",
        )
        val deny = FilterContainer().also { container -> set.blackList.forEach(container::plusAssign) }
        val allow = FilterContainer().also { container -> set.whiteList.forEach(container::plusAssign) }
        val blocker = Blocker(FilterContainer(), allow, deny)
        assertNotNull(blocker.isBlock(request("https://ads.example/blocked.js")))
        assertNull(blocker.isBlock(request("https://ads.example/blocked.js", thirdParty = false)))
        assertNull(blocker.isBlock(request("https://ads.example/allowed.js", "https://sub.trusted.example/")))
        assertNotNull(blocker.isBlock(request("https://ads.example/allowed.js", "https://other.example/")))
    }

    @Test
    fun unsupportedModifiersAndSitekeysCannotWidenFilters() {
        val set = decode(
            "||ads.example^\$sitekey=ABC",
            "@@||ads.example^\$sitekey=ABC",
            "||ads.example^\$unknown-option",
            "||ads.example^\$redirect=unknown-resource",
            "||ads.example^\$domain=",
            // 除外だけの entity（~example.*）は対応した（NetworkConstraintsTest）。ここでは壊れた指定だけを扱う。
            "||ads.example^\$domain=~ex*ample.*",
            "||ads.example^\$domain=~ ",
        )
        assertTrue(set.blackList.isEmpty())
        assertTrue(set.whiteList.isEmpty())
    }

    @Test
    fun regexMatchesUrlSubstringWithoutKeepingDelimiters() {
        val filter = decode("/track(er|ing)\\.js/").blackList.single()
        assertTrue(filter.isMatch(request("https://cdn.example/TRACKING.js?cache=1")))
        assertFalse(filter.isMatch(request("https://cdn.example/application.js")))
    }

    @Test
    fun regexEndAnchorIsNotAnOptionDelimiter() {
        val filter = decode("/tracking\\.js$/").blackList.single()
        assertTrue(filter.isMatch(request("https://cdn.example/tracking.js")))
        assertFalse(filter.isMatch(request("https://cdn.example/tracking.js?cache=1")))
        val typed = decode("/tracking\\.js$/\$script,third-party").blackList.single()
        assertTrue(typed.isMatch(request("https://cdn.example/tracking.js")))
        assertFalse(typed.isMatch(request("https://cdn.example/tracking.js", type = ContentRequest.TYPE_IMAGE)))
    }

    @Test
    fun unsupportedOrInvalidRegexIsSkipped() {
        assertTrue(decode("/ad(?=script)/", "/[/").blackList.isEmpty())
    }

    @Test(timeout = 1000)
    fun cosmeticWildcardTerminatesAndProducesGenericFilter() {
        val filter = decode("*##.ad").elementList.single()
        assertEquals("", filter.domain)
        assertTrue(filter.isMatch("example.com", "example."))
    }

    @Test
    fun cosmeticDomainHasLabelBoundaryAndTldWildcardWorks() {
        val filter = decode("Example.COM##.ad").elementList.single()
        assertTrue(filter.isMatch("news.example.com", "news.example."))
        assertFalse(filter.isMatch("notexample.com", "notexample."))
        val wildcard = decode("example.*##.ad").elementList.single()
        assertTrue(wildcard.isMatch("news.example.co.jp", "news.example."))
        assertFalse(wildcard.isMatch("notexample.com", "notexample."))
    }

    @Test
    fun negatedCosmeticTldWildcardKeepsItsExclusionAndRemovesTheWildcard() {
        val filter = decode("~example.*##.ad").elementList.single()
        assertTrue(filter.isNot)
        assertEquals("example.", filter.domain)
        assertTrue(filter.isMatch("news.example.co.jp", "news.example."))
        assertFalse(filter.isMatch("notexample.com", "notexample."))
    }

    @Test
    fun scriptletsAndProceduralOperatorsAreNotCssFilters() {
        val set = decode(
            "example.com##+js(set, ads, false)",
            "example.com## +js(set, ads, false)",
            "example.com##div:has-text(advert)",
            "example.com##div:style(display: none)",
            "example.com##^script",
            "example.com#?#div:-abp-contains(advert)",
            "example.com##.normal-ad",
        )
        // scriptlet は CSS に混ぜず、正規化した呼び出しとして別の形式で保存する（第3段階）。
        assertEquals(listOf(".normal-ad"), set.elementList.filterNot { Scriptlets.isStored(it.selector) }.map { it.selector })
        // procedural の要素フィルタも CSS に混ぜず、文書開始時のスクリプトへ渡す形で保存する。##^ は破棄する。
        assertEquals(listOf(
            listOf("set-constant", "ads", "false"),
            listOf(Scriptlets.PROCEDURAL_CALL, "div:has-text(advert)"),
            listOf(Scriptlets.PROCEDURAL_CALL, "div:style(display: none)"),
            listOf(Scriptlets.PROCEDURAL_CALL, "div:-abp-contains(advert)"),
        ), set.elementList.filter { Scriptlets.isStored(it.selector) }.map { Scriptlets.decode(it.selector) }.distinct())
    }

    @Test
    fun cosmeticScriptProtectsQuotesInNewAndLegacyCachedSelectors() {
        val parsed = decode("example.com##div[title='ad']").elementList.single()
        assertEquals("div[title=\\'ad\\']", parsed.selector)
        val elements = ElementContainer().also {
            it += parsed
            it += PlaneElementFilter("example.com", true, false, "div[title='legacy']")
        }
        val script = CosmeticFiltering(FilterContainer(), elements).loadScript(uri("https://example.com/"))!!
        assertTrue(script.contains("div[title=\\'ad\\']"))
        assertTrue(script.contains("div[title=\\'legacy\\']"))
        assertFalse(script.contains("div[title=\\\\'ad"))
        assertTrue(script.contains("syntax.querySelector(a)"))
        assertTrue(script.contains("catch(e){}"))
    }

    @Test
    fun cosmeticExceptionsMatchAcrossNewAndLegacyQuoteFormats() {
        val newHide = decode("example.com##div[title='ad']").elementList.single()
        val newException = decode("example.com#@#div[title='ad']").elementList.single()
        val oldHide = PlaneElementFilter("example.com", true, false, "div[title='ad']")
        val oldException = PlaneElementFilter("example.com", false, false, "div[title='ad']")
        listOf(newHide to oldException, oldHide to newException).forEach { (hide, exception) ->
            val elements = ElementContainer().also {
                it += hide
                it += exception
                it += PlaneElementFilter("example.com", true, false, ".normal-ad")
            }
            val script = CosmeticFiltering(FilterContainer(), elements).loadScript(uri("https://example.com/"))!!
            assertFalse(script.contains("title="))
            assertTrue(script.contains(".normal-ad"))
        }
    }

    @Test
    fun serializedFiltersKeepCaseRegexAndDomainSemantics() {
        val originals = decode(
            "/ad/banner.js",
            "/ad/banner.js^",
            "/tracking\\.js$/\$script,domain=example.com|~private.example.com",
            "||ads.example^\$match-case,third-party,domain=~private.example.com",
        ).blackList
        val restored = roundTrip(originals)
        assertEquals(originals, restored)
        assertTrue(restored[0].isMatch(request("https://cdn.example/AD/BANNER.js")))
        assertTrue(restored[1].isMatch(request("https://cdn.example/AD/BANNER.js")))
        assertTrue(restored[2].isMatch(request("https://cdn.example/TRACKING.js", "https://news.example.com/")))
        assertFalse(restored[2].isMatch(request("https://cdn.example/tracking.js", "https://news.private.example.com/")))
    }

    @Test
    fun untypedPathRulesDoNotBlockTheMainDocumentButPureHostRulesAndExplicitDocumentDo() {
        val (pathRule, hostRule, explicitDocument, subresourceOnly) = decode(
            "/tracking/",
            "||ads.example^",
            "/landing/\$document",
            "||cdn.example/banner/",
        ).blackList
        val page = "https://news.example/"
        // 汎用パス規則が記事ページそのものを遮断画面へ置き換えない（ABP の既定種別と同じ）。
        assertFalse(pathRule.isMatch(request("https://news.example/tracking/article", page, ContentRequest.TYPE_DOCUMENT)))
        assertFalse(subresourceOnly.isMatch(request("https://cdn.example/banner/x", page, ContentRequest.TYPE_POPUP)))
        // 通常の副リソースには従来どおり効く。
        assertTrue(pathRule.isMatch(request("https://cdn.example/tracking/pixel.gif", page, ContentRequest.TYPE_IMAGE)))
        // パスを持たないホスト規則と $document 明示は本文ページにも効く（uBO と同じ）。
        assertTrue(hostRule.isMatch(request("https://ads.example/", page, ContentRequest.TYPE_DOCUMENT)))
        assertTrue(explicitDocument.isMatch(request("https://news.example/landing/x", page, ContentRequest.TYPE_DOCUMENT)))
    }

    private fun decode(vararg lines: String) =
        AbpFilterDecoder().decode(lines.joinToString("\n").reader().buffered(), null)

    private fun request(
        url: String,
        pageUrl: String = "https://page.example/",
        type: Int = ContentRequest.TYPE_SCRIPT,
        thirdParty: Boolean = true,
    ) = ContentRequest(uri(url), uri(pageUrl), type, thirdParty)

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
