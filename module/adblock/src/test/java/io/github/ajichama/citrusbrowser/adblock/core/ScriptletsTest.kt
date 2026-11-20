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
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.ElementReader
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.ElementWriter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.whenever
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI

/** scriptlet（+js）の解析・保存・ドメイン照合・例外。 */
class ScriptletsTest {
    @Test
    fun argumentsSplitOnCommasHonourEscapesQuotesAndAliases() {
        assertEquals(listOf("prevent-setTimeout", "a,b", "500"), Scriptlets.parse("+js(nostif, a\\,b, 500)"))
        assertEquals(listOf("addEventListener-defuser", "", "preventDefault"), Scriptlets.parse("+js(aeld, , preventDefault)"))
        // 引用符は wiki の規定どおり文字として残す（外すと照合の範囲が広がる）。
        assertEquals(listOf("abort-current-script", "document.createElement", "'overlay'"),
            Scriptlets.parse("+js(acs, document.createElement, 'overlay')"))
        assertEquals(listOf("set-constant", "foo", "''"), Scriptlets.parse("+js(set, foo, '')"))
        assertEquals(listOf("abort-current-script", "Math", "/\\}\\s*\\(/"), Scriptlets.parse("+js(acis.js, Math, /\\}\\s*\\(/)"))
        assertEquals(listOf("remove-node-text", "#text", "広告"), Scriptlets.parse("+js(rmnt, #text, 広告)"))
        assertEquals(emptyList<String>(), Scriptlets.parse("+js()"))
        // 未対応の名前・壊れた構文は null（行ごと破棄）。
        assertNull(Scriptlets.parse("+js(trusted-set-cookie, a, b)"))
        // trusted の scriptlet（rpnt は trusted-replace-node-text の別名）と未実装の名前は破棄する。
        assertNull(Scriptlets.parse("+js(rpnt, script, a, b)"))
        assertNull(Scriptlets.parse("+js(nobab)"))
        assertEquals(listOf("prevent-window-open", "ads"), Scriptlets.parse("+js(nowoif, ads)"))
        assertEquals(listOf("window.name-defuser"), Scriptlets.parse("+js(window.name-defuser.js)"))
        assertNull(Scriptlets.parse("+js(set, a"))
        assertNull(Scriptlets.parse("+js(, a)"))
    }

    @Test
    fun trustedScriptletsAreAcceptedOnlyFromTrustedSources() {
        val line = "example.com##+js(rpnt, script, adblock, ok)"
        val storage = "example.com##+js(trusted-set-local-storage-item, consent, '{\"a\":1\\,\"b\":2}')"
        // 利用者が追加した任意のリストと、出典の分からない解析では受け付けない。
        for (source in listOf(null, "https://filters.example/list.txt", "https://ublockorigin.github.io.evil.example/uAssets/x.txt",
                // 本家の raw URL でも、PR やコミットを指すものは信頼しない（master に固定）。
                "https://raw.githubusercontent.com/uBlockOrigin/uAssets/refs/pull/1/head/filters/filters.txt",
                "https://raw.githubusercontent.com/uBlockOrigin/uAssets/0123456789abcdef/filters/filters.txt")) {
            val set = AbpFilterDecoder().decode("$line\n$storage".reader().buffered(), source)
            assertTrue(source.toString(), set.elementList.isEmpty())
        }
        for (source in listOf("https://ublockorigin.github.io/uAssets/filters/filters.txt", "citrus://adblock/filter")) {
            val calls = AbpFilterDecoder().decode("$line\n$storage".reader().buffered(), source).elementList.map { Scriptlets.decode(it.selector) }
            assertEquals(listOf(
                listOf("trusted-replace-node-text", "script", "adblock", "ok"),
                listOf("trusted-set-local-storage-item", "consent", "'{\"a\":1,\"b\":2}'"),
            ), calls)
        }
    }

    @Test
    fun simpleHtmlScriptFiltersBecomeRemoveNodeTextAndOthersAreDropped() {
        val filters = decode(
            "example.com##^script:has-text(adblock)",
            "example.com##^script:has-text('a b')",
            "example.com##^script:has-text(/ab+c/i)",
            "##^script:has-text(generic)",
            "sub.example.com#@#^script:has-text(adblock)",
            "example.com##^script:has-text(a(b)",
            "example.com##^div:has-text(x)",
            "example.com##^script[src]:has-text(x)",
        ).elementList
        assertEquals(listOf(
            listOf("remove-node-text", "script", "adblock"),
            listOf("remove-node-text", "script", "a b"),
            listOf("remove-node-text", "script", "/ab+c/i"),
        ), filters.filter { it.isHide }.map { Scriptlets.decode(it.selector) })
        // 例外（#@#^…）は代用の rmnt を打ち消す。
        val cosmetic = cosmetic(filters)
        assertTrue(cosmetic.scriptlets(uri("https://example.com/")).contains(listOf("remove-node-text", "script", "adblock")))
        assertFalse(cosmetic.scriptlets(uri("https://sub.example.com/")).contains(listOf("remove-node-text", "script", "adblock")))
    }

    @Test
    fun decoderStoresOnlySpecificSupportedScriptletsAndKeepsThemOutOfCss() {
        val decoded = decode(
            "example.com##+js(set, ads, false)",
            "##+js(set, generic, false)",
            "*##+js(set, star, false)",
            "~example.com##+js(set, negated, false)",
            "example.com##+js(nobab)",
            "example.com##+js()",
            "example.com##.banner",
        )
        val scriptlets = decoded.elementList.filter { Scriptlets.isStored(it.selector) }
        assertEquals(1, scriptlets.size)
        assertEquals(listOf("set-constant", "ads", "false"), Scriptlets.decode(scriptlets.single().selector))
        val cosmetic = cosmetic(decoded.elementList)
        val css = requireNotNull(cosmetic.loadScript(uri("https://example.com/")))
        assertTrue(css.contains(".banner"))
        assertFalse(css.contains("+js"))
    }

    @Test
    fun scriptletsFollowDomainRulesExceptionsAndElemhide() {
        val filters = decode(
            "example.com,~sub.example.com##+js(set, a, true)",
            "example.*##+js(nostif, ad)",
            "news.example.com##+js(aopr, b)",
            "news.example.com#@#+js(set-constant, a, true)",
            "other.test##+js(aopw, c)",
            "other.test#@#+js()",
            "example.com##+js(nosiif, timer)",
            "example.com##+js(no-setInterval-if, timer)",
        ).elementList
        val cosmetic = cosmetic(filters)
        assertEquals(setOf(
            listOf("set-constant", "a", "true"),
            listOf("prevent-setTimeout", "ad"),
            listOf("prevent-setInterval", "timer"),
        ), cosmetic.scriptlets(uri("https://www.example.com/")).toSet())
        assertEquals(1, cosmetic.scriptlets(uri("https://www.example.com/")).count { it[0] == "prevent-setInterval" })
        // 除外ドメインでは適用しない。entity（example.*）は別の公開サフィックスにも一致する。
        assertFalse(cosmetic.scriptlets(uri("https://sub.example.com/")).any { it[0] == "set-constant" })
        assertTrue(cosmetic.scriptlets(uri("https://example.co.uk/")).contains(listOf("prevent-setTimeout", "ad")))
        // 別名で書いた例外も、正式名で保存した行を無効化する。
        val news = cosmetic.scriptlets(uri("https://news.example.com/"))
        assertTrue(news.contains(listOf("abort-on-property-read", "b")))
        assertFalse(news.contains(listOf("set-constant", "a", "true")))
        // #@#+js() はそのホストのすべてを止める。
        assertTrue(cosmetic.scriptlets(uri("https://other.test/")).isEmpty())
        assertTrue(cosmetic.scriptlets(uri("https://unrelated.test/")).isEmpty())
        // $elemhide の例外では、要素フィルタと一緒に止める。
        val disabled = AbpFilterDecoder().decode("@@||example.com^\$elemhide".reader().buffered(), null).elementDisableFilter
        val withException = CosmeticFiltering(
            FilterContainer().also { container -> disabled.forEach { container += it } },
            ElementContainer().also { container -> filters.forEach { container += it } },
        )
        assertTrue(withException.scriptlets(uri("https://www.example.com/")).isEmpty())
    }

    @Test
    fun storedScriptletsSurviveTheElementCacheRoundTrip() {
        val filters = decode("example.com,example.org##+js(rmnt, script, /ad\\,s/, stay, 1)", "example.com#@#+js()").elementList
        val output = ByteArrayOutputStream()
        ElementWriter().write(output, filters)
        val restored = ElementReader(ByteArrayInputStream(output.toByteArray())).let { assertTrue(it.checkHeader()); it.readAll().toList() }
        assertEquals(filters.map { it.selector }, restored.map { it.selector })
        assertTrue(cosmetic(restored).scriptlets(uri("https://example.com/")).isEmpty())
        assertEquals(listOf(listOf("remove-node-text", "script", "/ad,s/", "stay", "1")), cosmetic(restored).scriptlets(uri("https://example.org/")))
    }

    @Test
    fun jsonEscapesQuotesControlCharactersAndScriptTerminators() {
        assertEquals("[[\"set-constant\",\"a\\\"b\\\\c\",\"\\u003c/script>\\u2028\\u000a\"]]",
            Scriptlets.toJson(listOf(listOf("set-constant", "a\"b\\c", "</script>\u2028\n"))))
        assertEquals("[]", Scriptlets.toJson(emptyList()))
    }

    @Test
    fun separateScriptletContainerServesScriptletsWithoutLeakingIntoCss() {
        val all = decode("example.com##+js(set, ads, false)", "example.com##.banner", "##.generic-ad").elementList
        val css = ElementContainer()
        val scriptlets = ElementContainer()
        all.forEach { if (Scriptlets.isStored(it.selector)) scriptlets += it else css += it }
        val cosmetic = CosmeticFiltering(FilterContainer(), css, scriptlets)
        assertEquals(listOf(listOf("set-constant", "ads", "false")), cosmetic.scriptlets(uri("https://example.com/")))
        val script = requireNotNull(cosmetic.loadScript(uri("https://example.com/")))
        assertTrue(script.contains(".banner") && script.contains(".generic-ad") && !script.contains("+js"))
    }

    private fun decode(vararg lines: String) = AbpFilterDecoder().decode(lines.joinToString("\n").reader().buffered(), null)

    private fun cosmetic(elements: List<ElementFilter>) = CosmeticFiltering(
        FilterContainer(), ElementContainer().also { container -> elements.forEach { container += it } })

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
