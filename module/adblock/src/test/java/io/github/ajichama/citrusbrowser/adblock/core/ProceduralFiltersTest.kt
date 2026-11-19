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
import io.github.ajichama.citrusbrowser.adblock.core.ProceduralFilters.Result
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementContainer
import io.github.ajichama.citrusbrowser.adblock.filter.unified.whenever
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.net.URI

/** procedural の要素フィルタの判定・検証と、保存・照合。 */
class ProceduralFiltersTest {
    @Test
    fun pureCssStaysCssAndProceduralOperatorsAreRecognised() {
        for (css in listOf("div.ad", "div:has(> a.ad)", "a:not(.x)", "li:nth-child(2)", "a[href*=\"x:y(\"]")) {
            assertEquals(css, Result.Css, ProceduralFilters.classify(css, false))
        }
        for (procedural in listOf(
            "div:has-text(広告)",
            "span:has-text(/^PR$/i):upward(2)",
            "body:style(overflow: auto !important)",
            ".overlay:remove()",
            "div:-abp-has(.ad)",
            ":xpath(//div[@id=\"x\"])",
            "div:has(span:has-text(PR))",
            "div:not(:has-text(本文))",
            ":matches-path(/news) .ad",
            "a:matches-attr(data-x=/^ad/):remove-attr(onclick)",
            "div:has-text(\"a)b\")",
        )) {
            assertTrue(procedural, ProceduralFilters.classify(procedural, false) is Result.Procedural)
        }
        // #?# で書いた CSS だけの行は、通常の CSS として扱う。
        assertEquals(Result.Css, ProceduralFilters.classify("div.ad", true))
    }

    @Test
    fun unsafeOrUnsupportedProceduralFiltersAreRejected() {
        for (invalid in listOf(
            ".a:style(background: url(https://x.example/a.png))",
            ".a:style(color: red} body{display:none)",
            ".a:style(/* x */ color: red)",
            ".a:remove():upward(1)",
            "a:if(.b)",
            "a:spath(> b)",
            "a, b:has-text(x)",
            "div:has-text(",
            "div:has-text(x) extra:remove() more",
        )) {
            assertEquals(invalid, Result.Invalid, ProceduralFilters.classify(invalid, false))
        }
    }

    @Test
    fun decoderStoresOnlySpecificProceduralFiltersAndAppliesExceptionsExactly() {
        val filters = decode(
            "example.com##div:has-text(広告)",
            "##div:has-text(汎用)",
            "~example.com##div:has-text(否定のみ)",
            "example.com#?#div:-abp-has(.ad)",
            "example.com##div:has-text(除外)",
            "example.com#@#div:has-text(除外)",
            "example.com##body:style(overflow: auto !important)",
            "example.com#@#+js()",
            "example.com##.banner",
        )
        val stored = filters.filter { it.selector.startsWith(Scriptlets.PROCEDURAL_PREFIX) }
        assertFalse(stored.any { "汎用" in it.selector || "否定のみ" in it.selector })
        val cosmetic = CosmeticFiltering(FilterContainer(), ElementContainer().also { c -> filters.forEach { c += it } })
        // #@#+js() は scriptlet だけを止め、procedural は止めない。完全に同じ本文の例外だけが除外する。
        assertEquals(setOf(
            listOf(Scriptlets.PROCEDURAL_CALL, "div:has-text(広告)"),
            listOf(Scriptlets.PROCEDURAL_CALL, "div:-abp-has(.ad)"),
            listOf(Scriptlets.PROCEDURAL_CALL, "body:style(overflow: auto !important)"),
        ), cosmetic.scriptlets(uri("https://example.com/")).toSet())
        val css = requireNotNull(cosmetic.loadScript(uri("https://example.com/")))
        assertTrue(css.contains(".banner") && !css.contains("has-text") && !css.contains(":style("))
    }

    private fun decode(vararg lines: String) =
        AbpFilterDecoder().decode(lines.joinToString("\n").reader().buffered(), null).elementList

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
