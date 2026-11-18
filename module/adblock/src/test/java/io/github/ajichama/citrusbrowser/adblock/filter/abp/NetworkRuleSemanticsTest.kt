/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.ajichama.citrusbrowser.adblock.filter.abp

import android.net.Uri
import io.github.ajichama.citrusbrowser.adblock.core.Blocker
import io.github.ajichama.citrusbrowser.adblock.core.ContentRequest
import io.github.ajichama.citrusbrowser.adblock.core.FilterContainer
import io.github.ajichama.citrusbrowser.adblock.core.NetworkFilterRules
import io.github.ajichama.citrusbrowser.adblock.filter.unified.UnifiedFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.UnifiedFilterSet
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterReader
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterWriter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.whenever
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI

class NetworkRuleSemanticsTest {
    @Test
    fun resourceTypeAliasesKeepTheirRestriction() {
        mapOf(
            "xhr" to ContentRequest.TYPE_XHR,
            "css" to ContentRequest.TYPE_STYLE_SHEET,
            "frame" to ContentRequest.TYPE_SUB_DOCUMENT,
            "doc" to ContentRequest.TYPE_DOCUMENT,
        ).forEach { (alias, type) ->
            val filter = decode("||ads.example^\$$alias").blackList.single()
            assertTrue(alias, filter.isMatch(request(type = type)))
            assertFalse(alias, filter.isMatch(request(type = ContentRequest.TYPE_IMAGE)))
        }
        val all = decode("||ads.example^\$all").blackList.single()
        assertTrue(all.isMatch(request(type = ContentRequest.TYPE_DOCUMENT)))
        assertTrue(all.isMatch(request(type = ContentRequest.TYPE_XHR)))
        assertTrue(decode("||ads.example^\$~all").blackList.isEmpty())
    }

    @Test
    fun partyAliasesAndFromKeepPositiveAndNegativeConstraints() {
        listOf("3p" to true, "~3p" to false, "1p" to false, "~1p" to true,
            "first-party" to false, "~first-party" to true).forEach { (alias, third) ->
            val filter = decode("||ads.example^\$$alias,from=trusted.example|~private.trusted.example").blackList.single()
            assertTrue(alias, filter.isMatch(request(page = "https://news.trusted.example/", third = third)))
            assertFalse(alias, filter.isMatch(request(page = "https://news.trusted.example/", third = !third)))
            assertFalse(alias, filter.isMatch(request(page = "https://private.trusted.example/", third = third)))
            assertFalse(alias, filter.isMatch(request(page = "https://other.example/", third = third)))
        }
    }

    @Test
    fun excludedTypesDoNotDependOnOptionOrder() {
        listOf("script,image,~image", "~image,image,script").forEach { options ->
            val filter = decode("||ads.example^\$$options").blackList.single()
            assertTrue(filter.isMatch(request()))
            assertFalse(filter.isMatch(request(type = ContentRequest.TYPE_IMAGE)))
        }
        assertTrue(decode("||ads.example^\$script,~script").blackList.isEmpty())
    }

    @Test
    fun omittedPatternStillRespectsTypesDomainsAndBadfilterIdentity() {
        val filter = decode("\$script,domain=trusted.example").blackList.single()
        assertTrue(filter.isMatch(request(page = "https://trusted.example/")))
        assertFalse(filter.isMatch(request(page = "https://other.example/")))
        assertFalse(filter.isMatch(request(page = "https://trusted.example/", type = ContentRequest.TYPE_IMAGE)))
        val network = rules(decode("\$script,domain=trusted.example", "\$domain=trusted.example,script,badfilter"))
        assertNull(network.deny[request(page = "https://trusted.example/")])
        val text = decode("||ads.example^\$redirect=noop.txt").blackList.single()
        assertEquals(ContentRequest.REDIRECT_EMPTY, text.contentType and ContentRequest.REDIRECT_MASK)
    }

    @Test
    fun importantWinsOverNetworkExceptionsButNotAnAllowedPage() {
        val rules = rules(decode("||ads.example^\$important,script,3p", "@@||ads.example^"))
        val blocker = Blocker(FilterContainer(), rules.allow, rules.deny, rules.important)
        assertNotNull(blocker.isBlock(request()))
        assertNull(blocker.isBlock(request(type = ContentRequest.TYPE_IMAGE)))
        assertNull(blocker.isBlock(request(third = false)))
        val pages = FilterContainer().also { page -> decode("||page.example^").blackList.forEach(page::plusAssign) }
        assertNull(Blocker(pages, rules.allow, rules.deny, rules.important).isBlock(request()))
    }

    @Test
    fun invalidAndUnsupportedModifiersNeverBecomeBroaderRules() {
        val invalid = listOf(
            "@@||ads.example^\$important", "||ads.example^\$~important", "||ads.example^\$important=1",
            "||ads.example^\$badfilter=1", "||ads.example^\$~badfilter", "||ads.example^\$script=1",
            "||ads.example^\$from=*.example.com", "||ads.example^\$domain=example.*",
            "||ads.example^\$from=trusted.example,domain=other.example", "||ads.example^\$script,unknown",
            "||ads.example^\$redirect-rule=noopjs", "@@||ads.example^\$redirect=noopjs",
            "||ads.example^\$redirect=custom-resource", "||ads.example^\$redirect=noopjs:10",
            "||ads.example^\$3p,1p", "||ads.example^\$match-case,~match-case",
        )
        invalid.forEach { line ->
            val set = decode(line)
            assertTrue(line, set.blackList.isEmpty() && set.whiteList.isEmpty() && set.elementDisableFilter.isEmpty())
        }
    }

    @Test
    fun badfilterRemovesAnExactRuleAcrossSerializedSubscriptions() {
        val first = decode("||ads.example^\$script,3p", "||other.example^\$image")
        val second = decode("||ads.example^\$3p,badfilter,script")
        val network = NetworkFilterRules(roundTrip(first.blackList) + roundTrip(second.blackList), emptyList(), emptyList())
        assertNull(network.deny[request()])
        assertNotNull(network.deny[request(url = "https://other.example/a.png", type = ContentRequest.TYPE_IMAGE)])
        assertNull(network.deny[request(url = "https://other.example/a.js")])
    }

    @Test
    fun badfilterCanonicalizesPositiveAndNegativePartyAliasesAcrossCaches() {
        listOf(listOf("1p", "first-party", "~3p", "~third-party"),
            listOf("3p", "third-party", "~1p", "~first-party")).forEach { aliases ->
            aliases.forEach { blocking -> aliases.forEach { disabling ->
                val first = decode("||ads.example^\$$blocking,script")
                val second = decode("||ads.example^\$script,$disabling,badfilter")
                val network = NetworkFilterRules(roundTrip(first.blackList) + roundTrip(second.blackList), emptyList(), emptyList())
                assertNull("$blocking / $disabling", network.deny[request(third = aliases.first() == "3p")])
            } }
        }
        val distinct = rules(decode("||ads.example^\$1p,script", "||ads.example^\$3p,script,badfilter"))
        assertNotNull(distinct.deny[request(third = false)])
    }

    @Test
    fun redirectsWinOverPlainBlockingRegardlessOfInsertionOrderAndPriorityClass() {
        listOf(false, true).forEach { important ->
            val modifier = if (important) ",important" else ""
            val plain = "||ads.example^\$script$modifier"
            val redirect = "||ads.example^\$script,redirect=noopjs$modifier"
            listOf(listOf(plain, redirect), listOf(redirect, plain)).forEach { order ->
                val network = rules(decode(*order.toTypedArray()))
                val blocker = Blocker(FilterContainer(), network.allow, network.deny, network.important)
                val result = blocker.isBlock(request())!!
                assertEquals(order.toString(), ContentRequest.REDIRECT_SCRIPT, result.contentType and ContentRequest.REDIRECT_MASK)
                assertEquals(if (important) ContentRequest.FLAG_IMPORTANT else 0, result.contentType and ContentRequest.FLAG_IMPORTANT)
            }
        }
        val allowed = rules(decode("||ads.example^\$redirect=noopjs", "@@||ads.example^"))
        assertNull(Blocker(FilterContainer(), allowed.allow, allowed.deny, allowed.important).isBlock(request()))
    }

    @Test
    fun importantBlockingUsesNormalRedirectButKeepsItsOwnPatternAndPriority() {
        val plain = "||ads.example^\$important,script"
        val redirect = "/ad.js\$script,redirect=noopjs"
        listOf(listOf(plain, redirect), listOf(redirect, plain)).forEach { order ->
            val network = rules(decode(*(order + "@@||ads.example^").toTypedArray()))
            val blocker = Blocker(FilterContainer(), network.allow, network.deny, network.important)
            val result = blocker.isBlock(request())!!
            assertEquals("ads.example", result.pattern)
            assertEquals(ContentRequest.FLAG_IMPORTANT, result.contentType and ContentRequest.FLAG_IMPORTANT)
            assertEquals(ContentRequest.REDIRECT_SCRIPT, result.contentType and ContentRequest.REDIRECT_MASK)
            assertNull(blocker.isBlock(request(type = ContentRequest.TYPE_IMAGE)))
        }
        val competing = rules(decode(plain, redirect, "||ads.example^\$script,important,redirect=noop.css"))
        val winner = Blocker(FilterContainer(), competing.allow, competing.deny, competing.important).isBlock(request())!!
        assertEquals(ContentRequest.REDIRECT_STYLE, winner.contentType and ContentRequest.REDIRECT_MASK)
        val removed = rules(decode(plain, redirect, "/ad.js\$script,redirect=noopjs,badfilter"))
        val withoutRedirect = Blocker(FilterContainer(), removed.allow, removed.deny, removed.important).isBlock(request())!!
        assertEquals(0, withoutRedirect.contentType and ContentRequest.REDIRECT_MASK)
    }

    @Test
    fun badfilterCanDisableAnExceptionAndAnImportantRuleIndependently() {
        val disabledException = rules(decode(
            "||ads.example^", "@@||ads.example^\$script", "@@||ads.example^\$script,badfilter",
        ))
        assertNotNull(Blocker(FilterContainer(), disabledException.allow, disabledException.deny).isBlock(request()))
        val disabledImportant = rules(decode(
            "||ads.example^\$important", "||ads.example^\$badfilter,important", "@@||ads.example^",
        ))
        assertNull(Blocker(FilterContainer(), disabledImportant.allow, disabledImportant.deny, disabledImportant.important).isBlock(request()))
    }

    @Test
    fun badfilterDoesNotCancelIndependentCosmeticOrNetworkRules() {
        val first = decode("@@||ads.example^\$script,ehide", "@@||ads.example^\$script")
        val second = decode("@@||ads.example^\$script,ehide,badfilter")
        val network = NetworkFilterRules(roundTrip(first.blackList + second.blackList),
            roundTrip(first.whiteList + second.whiteList), roundTrip(first.elementDisableFilter + second.elementDisableFilter))
        assertNotNull(network.allow[request()])
        assertNull(network.cosmetic[request(type = ContentRequest.TYPE_ELEMENT_HIDE)])
        val other = rules(decode("@@||ads.example^\$ehide", "@@||ads.example^\$script,ehide,badfilter"))
        assertNotNull(other.cosmetic[request(type = ContentRequest.TYPE_ELEMENT_HIDE)])
    }

    @Test
    fun badfilterDistinguishesRedirectResourcesAndPlainBlocking() {
        val plain = rules(decode("||ads.example^\$script", "||ads.example^\$script,redirect=noopjs,badfilter"))
        assertNotNull(plain.deny[request()])
        val redirected = rules(decode("||ads.example^\$script,redirect=noopjs", "||ads.example^\$script,badfilter"))
        assertNotNull(redirected.deny[request()])
        val differentResource = rules(decode("||ads.example^\$redirect=noopjs", "||ads.example^\$redirect=empty,badfilter"))
        assertNotNull(differentResource.deny[request()])
        val exact = rules(decode("||ads.example^\$script,redirect=noopjs", "||ads.example^\$redirect=noopjs,script,badfilter"))
        assertNull(exact.deny[request()])
    }

    @Test
    fun cachedRulesKeepPriorityRedirectAndOriginalRuleIdentity() {
        val originals = decode("/ad[a-z]+/\$script,important,redirect=noopjs,from=trusted.example",
            "||ads.example^\$badfilter", "||ads.example^\$redirect=noop.css").blackList
        val restored = roundTrip(originals)
        assertEquals(originals, restored)
        assertEquals(ContentRequest.FLAG_IMPORTANT or ContentRequest.REDIRECT_SCRIPT,
            restored[0].contentType and (ContentRequest.FLAG_IMPORTANT or ContentRequest.REDIRECT_MASK))
        assertTrue(restored[0].isMatch(request(page = "https://trusted.example/")))
        assertFalse(restored[0].isMatch(request(page = "https://other.example/")))
        assertEquals(ContentRequest.FLAG_BADFILTER, restored[1].contentType and ContentRequest.FLAG_BADFILTER)
        assertEquals(ContentRequest.REDIRECT_STYLE, restored[2].contentType and ContentRequest.REDIRECT_MASK)
    }

    private fun rules(set: UnifiedFilterSet) = NetworkFilterRules(set.blackList, set.whiteList, set.elementDisableFilter)
    private fun decode(vararg lines: String) = AbpFilterDecoder().decode(lines.joinToString("\n").reader().buffered(), null)

    private fun request(url: String = "https://ads.example/ad.js", page: String = "https://page.example/",
                        type: Int = ContentRequest.TYPE_SCRIPT, third: Boolean = true) =
        ContentRequest(uri(url), uri(page), type, third)

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
