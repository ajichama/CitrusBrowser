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

/** removeparam・urlskip（主フレームの移動の書き換え）。 */
class NavigationRewriterTest {
    private val trustedSource = "https://ublockorigin.github.io/uAssets/filters/filters.txt"

    @Test
    fun removeParamsFollowsTheWikiRules() {
        val url = "https://a.example/p?utm_source=x&id=1&utm_medium=y#top"
        assertEquals("https://a.example/p?id=1&utm_medium=y#top", NavigationRewriter.removeParams(url, "utm_source"))
        assertEquals("https://a.example/p?id=1#top", NavigationRewriter.removeParams(url, "/^utm_/"))
        assertEquals("https://a.example/p#top", NavigationRewriter.removeParams(url, ""))
        // 正規表現は「名前=値」に対して照合する。
        assertEquals("https://a.example/p?utm_source=x&utm_medium=y#top", NavigationRewriter.removeParams(url, "/^id=1$/"))
        assertEquals(url, NavigationRewriter.removeParams(url, "none"))
    }

    @Test
    fun urlSkipStepsExtractOnlyHttpDestinations() {
        assertEquals("https://example.org/", NavigationRewriter.skip("https://t.example/go?url=https%3A%2F%2Fexample.org%2F", "?url"))
        assertEquals("https://example.org/a", NavigationRewriter.skip("https://t.example/go?u=aHR0cHM6Ly9leGFtcGxlLm9yZy9h", "?u -base64"))
        assertEquals("https://example.org/", NavigationRewriter.skip("https://t.example/go#example.org/", "# +https"))
        assertEquals("https://example.org/x", NavigationRewriter.skip("https://t.example/r/example.org/x", "/\\/r\\/(.+)$/ +https"))
        assertEquals("https://example.org/", NavigationRewriter.skip("https://t.example/?https://example.org/=", "&1"))
        // http(s) 以外の移動先・失敗した手順は書き換えない。
        assertNull(NavigationRewriter.skip("https://t.example/go?url=javascript:alert(1)", "?url"))
        assertNull(NavigationRewriter.skip("https://t.example/go", "?url"))
        assertFalse(NavigationRewriter.isValidSkipSteps("?url -unknown"))
    }

    @Test
    fun modifierRulesRewriteNavigationsDoNotBlockAndHonourExceptionsAndTrust() {
        val lines = listOf(
            "*\$removeparam=utm_source",
            "||news.example^\$removeparam=/^fbclid=/",
            "@@||keep.example^\$removeparam",
            "||t.example/go\$urlskip=?url",
            "||bad.example^\$removeparam=~x",
        )
        val untrusted = rules(decode(lines, null))
        val trusted = rules(decode(lines, trustedSource))
        // removeparam は任意のリストで、urlskip は信頼できる出典だけで受け付ける。
        assertEquals("https://news.example/a?id=1", untrusted.rewriteNavigation(uri("https://news.example/a?utm_source=x&id=1&fbclid=z")))
        assertNull(untrusted.rewriteNavigation(uri("https://t.example/go?url=https%3A%2F%2Fexample.org%2F")))
        assertEquals("https://example.org/", trusted.rewriteNavigation(uri("https://t.example/go?url=https%3A%2F%2Fexample.org%2F")))
        // 値のない例外は、そのサイトの removeparam をすべて止める。
        assertNull(trusted.rewriteNavigation(uri("https://keep.example/?utm_source=x")))
        // 書き換えのルールは遮断に使わない。
        assertNull(trusted.isBlock(ContentRequest(uri("https://news.example/a?utm_source=x"), uri("https://news.example/"), ContentRequest.TYPE_DOCUMENT, false)))
        // 否定の値（文書にない）は行ごと破棄する。
        assertNull(trusted.rewriteNavigation(uri("https://bad.example/?x=1&y=2")))
    }

    private fun decode(lines: List<String>, source: String?) =
        AbpFilterDecoder().decode(lines.joinToString("\n").reader().buffered(), source)

    private fun rules(set: io.github.ajichama.citrusbrowser.adblock.filter.unified.UnifiedFilterSet): Blocker {
        val network = NetworkFilterRules(roundTrip(set.blackList), roundTrip(set.whiteList), set.elementDisableFilter)
        return Blocker(FilterContainer(), network.allow, network.deny, network.important, network.redirectRules, network.navigation)
    }

    private fun roundTrip(filters: List<UnifiedFilter>): List<UnifiedFilter> {
        val output = ByteArrayOutputStream()
        FilterWriter().write(output, filters)
        val reader = FilterReader(ByteArrayInputStream(output.toByteArray()))
        assertTrue(reader.checkHeader())
        return reader.readAll().toList()
    }

    private fun uri(value: String): Uri {
        val parsed = URI(value)
        return Mockito.mock(Uri::class.java).also {
            whenever(it.toString()).thenReturn(value)
            whenever(it.host).thenReturn(parsed.host)
            whenever(it.scheme).thenReturn(parsed.scheme)
            whenever(it.schemeSpecificPart).thenReturn(parsed.rawSchemeSpecificPart)
        }
    }
}
