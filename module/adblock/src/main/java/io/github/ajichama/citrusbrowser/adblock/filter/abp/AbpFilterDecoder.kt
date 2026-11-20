/*
 * Copyright (C) 2017-2019 Hazuki
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

import com.google.re2j.Pattern
import io.github.ajichama.citrusbrowser.adblock.*
import io.github.ajichama.citrusbrowser.adblock.core.ContentRequest
import io.github.ajichama.citrusbrowser.adblock.core.NavigationRewriter
import io.github.ajichama.citrusbrowser.adblock.core.ProceduralFilters
import io.github.ajichama.citrusbrowser.adblock.core.Scriptlets
import io.github.ajichama.citrusbrowser.adblock.filter.unified.*
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.*
import io.github.ajichama.citrusbrowser.core.utility.extensions.forEachLine
import java.io.BufferedReader
import java.io.IOException
import java.nio.charset.Charset
import java.util.*

class AbpFilterDecoder {
    private val contentRegex = Pattern.compile(CONTENT_FILTER_REGEX)

    fun checkHeader(reader: BufferedReader, charset: Charset): Boolean {
        while (true) {
            reader.mark(64 * 1024)
            val line = reader.readLine() ?: return false
            val header = when (charset) {
                Charsets.UTF_8, Charsets.UTF_16, Charsets.UTF_16LE, Charsets.UTF_16BE ->
                    line.trim().removePrefix("\uFEFF").trim()
                else -> line.trim()
            }
            if (header.isEmpty()) continue
            return if (header[0] == '!') {
                reader.reset()
                true
            } else {
                header.startsWith(HEADER)
            }
        }
    }

    fun decode(reader: BufferedReader, url: String?): UnifiedFilterSet {
        val info = DecoderInfo()
        val black = mutableListOf<UnifiedFilter>()
        val white = mutableListOf<UnifiedFilter>()
        val elementDisableFilter = mutableListOf<UnifiedFilter>()
        val elementFilter = mutableListOf<ElementFilter>()
        // trusted の scriptlet は、信頼できる出典のリストからだけ受け付ける。
        val trusted = Scriptlets.isTrustedSource(url)
        reader.forEachLine { line ->
            val trimmedLine = line.trim().removePrefix("\uFEFF").trim()
            if (trimmedLine.isEmpty() || trimmedLine.startsWith(HEADER)) return@forEachLine
            when {
                trimmedLine[0] == '!' -> trimmedLine.decodeComment(url, info)?.let {
                    throw OnRedirectException(it)
                }
                else -> {
                    val matcher = contentRegex.matcher(trimmedLine)
                    if (matcher.matches()) {
                        decodeContentFilter(
                            matcher.group(1),
                            matcher.group(2),
                            matcher.group(3),
                            elementFilter,
                            trusted,
                        )
                    } else {
                        trimmedLine.decodeFilter(black, white, elementDisableFilter, trusted)
                    }
                }
            }
        }
        return UnifiedFilterSet(info, black, white, elementDisableFilter, elementFilter)
    }

    private fun decodeContentFilter(
        domains: String?,
        type: String?,
        body: String,
        elementFilterList: MutableList<ElementFilter>,
        trusted: Boolean = false,
    ) {
        // HTML フィルタのうち `##^script:has-text(文字列)` だけは、実行前にスクリプトの中身を消す rmnt で同じ効果を出す。
        // 受信中の HTML は書き換えられないため、それ以外の HTML フィルタと例外は従来どおり破棄する。
        // 例外（#@#^…）も同じ形で保存し、代用の rmnt を打ち消す。
        val htmlScriptNeedle = htmlScriptFilterNeedle(body)
        // scriptlet と procedural は要素フィルタと同じドメイン条件で保存し、CSS としては扱わない。
        val isScriptlet = Scriptlets.isScriptletBody(body) || htmlScriptNeedle != null
        var isProcedural = false
        val selector = if (htmlScriptNeedle != null) {
            Scriptlets.encode(listOf("remove-node-text", "script", htmlScriptNeedle))
        } else if (isScriptlet) {
            // 未対応の名前・壊れた構文は行ごと破棄する。空の +js() は例外（全無効化）でだけ意味を持つ。
            val call = Scriptlets.parse(body, trusted) ?: return
            if (call.isEmpty() && type != "@") return
            Scriptlets.encode(call)
        } else when (val result = ProceduralFilters.classify(body, explicitProcedural = type == "?")) {
            is ProceduralFilters.Result.Procedural -> {
                isProcedural = true
                Scriptlets.encodeProcedural(result.body)
            }
            ProceduralFilters.Result.Invalid -> return
            ProceduralFilters.Result.Css -> {
                if (body.any { it < ' ' } || !body.isSupportedSelector()) return
                body.sanitizeSelector()
            }
        }

        if (domains == null && type == "@") return

        var domainList = domains?.run {
            splitToSequence(',')
                .map { it.trim().lowercase(Locale.ROOT) }
                .toList()
        } ?: emptyList()

        if (domainList.isEmpty()) {
            domainList = listOf("")
        }

        // ドメインを限定しない scriptlet は、wiki の規定（must be specific）どおり無視する。
        // procedural も同じく、ドメインの指定が必須（wiki: must always be specific）。
        if ((isScriptlet || isProcedural) && domainList.none { it.isNotEmpty() && it != "*" && !it.startsWith('~') }) return

        val isHide = when (type) {
            "@" -> false
            null -> true
            // #?# は procedural であることを明示する書き方。CSS だけの行は通常の要素非表示として扱う。
            "?" -> true
            else -> return
        }

        if (domainList.any { token ->
            val value = token.removePrefix("~")
            (value.isEmpty() && !(domainList.size == 1 && token.isEmpty())) ||
                value.any { it.isWhitespace() || it in "/|~" } ||
                ('*' in value && value != "*" && !(value.endsWith(".*") && value.count { it == '*' } == 1))
        }) return
        if (domainList.size > 1) {
            elementFilterList += GroupElementFilter(domainList.joinToString(","), isHide, selector)
            return
        }

        domainList.forEach {
            var domain = if (it == "*") "" else it
            val isNot = domain.startsWith('~')
            if (isNot) {
                domain = domain.substring(1)
            }

            elementFilterList += if (domain.endsWith('*')) {
                domain = domain.substring(0, domain.length - 1)
                TldRemovedElementFilter(domain, isHide, isNot, selector)
            } else {
                PlaneElementFilter(domain, isHide, isNot, selector)
            }
        }
    }

    /**
     * `^script:has-text(文字列)` の探す文字列（`/正規表現/` はそのまま）。外側の引用符は外す。
     * 括弧・バックスラッシュ・入れ子を含む指定は意味が曖昧なため null（行ごと破棄）。
     */
    private fun htmlScriptFilterNeedle(body: String): String? {
        val match = HTML_SCRIPT_FILTER.matchEntire(body.trim()) ?: return null
        var needle = match.groupValues[1].trim()
        if (needle.length >= 2 && needle.first() == '/' && needle.last() == '/') return needle
        if (needle.length >= 2 && (needle.first() == '\'' || needle.first() == '"') && needle.last() == needle.first()) {
            needle = needle.substring(1, needle.length - 1)
        }
        if (needle.isEmpty() || needle.any { it in "()\\'\"" }) return null
        return needle
    }

    /** `||example.com^` のように、パス・ワイルドカードを含まないホスト名だけの規則か。 */
    private fun isPureHostnameFilter(filter: String): Boolean {
        if (!filter.startsWith("||")) return false
        val host = filter.substring(2).removeSuffix("^")
        return host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    private fun String.sanitizeSelector() =trim().replace("\\", "\\\\").replace("'", "\\'")
        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun String.isSupportedSelector(): Boolean {
        val selector = trim()
        if (selector.isEmpty() || selector.startsWith("+js") || selector.startsWith('^') ||
            selector.startsWith("//scriptlet")) return false
        // スクリプトレットや拡張 CSS の命令を通常の CSS として実行しない。
        return listOf(
            ":-abp-", ":has-text(", ":contains(", ":matches-", ":xpath(", ":remove(",
            ":remove-attr(", ":remove-class(", ":style(", ":watch-attr(", ":upward(",
            ":others(", ":min-text-length(", ":media(",
        ).none { selector.contains(it) }
    }

    private fun String.decodeFilter(
        blackList: MutableList<UnifiedFilter>,
        whiteList: MutableList<UnifiedFilter>,
        elementFilterList: MutableList<UnifiedFilter>,
        trusted: Boolean = false,
    ) {
        var contentType = 0
        var modifier: String? = null
        var ignoreCase = true
        var domain: String? = null
        var thirdParty = -1
        var filter = this
        var cosmeticType = 0
        var hasNetworkType = false
        var excludedTypes = 0
        var flags = 0
        var redirectType = 0
        var hasMatchCase = false
        var redirectRule = false
        var entityDomains: String? = null
        var denyAllow: String? = null
        var requestDomains: String? = null
        var methods: String? = null
        val canonicalOptions = mutableListOf<String>()
        if ('\u0000' in filter) return
        val blocking = if (filter.startsWith("@@")) {
            filter = substring(2)
            false
        } else {
            true
        }
        val regexEnd = if (filter.startsWith('/')) filter.lastIndexOf('/') else -1
        val optionsIndex = when {
            regexEnd > 0 && regexEnd == filter.lastIndex -> -1
            regexEnd > 0 && filter.getOrNull(regexEnd + 1) == '$' -> regexEnd + 1
            else -> filter.lastIndexOf('$')
        }
        if (optionsIndex >= 0) {
            filter.substring(optionsIndex + 1).split(',').forEach {
                var option = it
                var value: String? = null
                val separatorIndex = option.indexOf('=')
                if (separatorIndex >= 0) {
                    value = option.substring(separatorIndex + 1)
                    option = option.substring(0, separatorIndex)
                }
                if (option.isEmpty()) return

                val inverse = option[0] == '~'
                if (inverse) {
                    option = option.substring(1)
                }

                option = when (option.lowercase(Locale.ROOT)) {
                    "3p" -> "third-party"
                    "1p" -> "first-party"
                    "xhr" -> "xmlhttprequest"
                    "css" -> "stylesheet"
                    "frame" -> "subdocument"
                    "doc" -> "document"
                    "from" -> "domain"
                    "ehide" -> "elemhide"
                    "ghide" -> "generichide"
                    else -> option.lowercase(Locale.ROOT)
                }
                val canonicalOption = if (option == "first-party") {
                    (if (inverse) "" else "~") + "third-party"
                } else {
                    (if (inverse) "~" else "") + option
                }
                canonicalOptions += canonicalOption + (value?.let { "=$it" } ?: "")
                val type = option.getOptionBit()
                if (type == -1) return

                when {
                    type > 0x00ff_ffff -> {
                        // 要素非表示の制御は例外専用。通常通信の許可へ拡大しない。
                        if (blocking || inverse || value != null) return
                        cosmeticType = cosmeticType or type
                    }
                    type > 0 -> {
                        if (value != null) return
                        hasNetworkType = true
                        if (inverse) excludedTypes = excludedTypes or type else contentType = contentType or type
                    }
                    type == 0 -> {
                        when (option) {
                            "match-case" -> {
                                if (value != null || hasMatchCase) return
                                hasMatchCase = true
                                ignoreCase = inverse
                            }
                            "domain" -> {
                                if (inverse || value.isNullOrBlank() || domain != null || entityDomains != null) return
                                if (value.split('|').any { it.trim().removePrefix("~").endsWith(".*") }) {
                                    // entity（example.*）は公開サフィックスで照合するため、追加条件として持つ。
                                    if (!NetworkConstraints.isValidHostList(value, allowNegation = true, allowEntity = true)) return
                                    entityDomains = value
                                } else {
                                    if (value.split('|').any {
                                        val name = it.trim().removePrefix("~")
                                        name.isEmpty() || name.any { character -> character.isWhitespace() || character in "*/~" }
                                    }) return
                                    domain = value
                                }
                            }
                            "denyallow" -> {
                                if (inverse || value.isNullOrBlank() || denyAllow != null) return
                                if (!NetworkConstraints.isValidHostList(value, allowNegation = false, allowEntity = false)) return
                                denyAllow = value
                            }
                            "to" -> {
                                if (inverse || value.isNullOrBlank() || requestDomains != null) return
                                if (!NetworkConstraints.isValidHostList(value, allowNegation = true, allowEntity = true)) return
                                requestDomains = value
                            }
                            "removeparam", "queryprune" -> {
                                // 遮断しない書き換え。他の書き換え・redirect と同じ行には書けない（wiki の規定）。
                                if (inverse || modifier != null || !NavigationRewriter.isValidRemoveParam(value)) return
                                modifier = if (value.isNullOrEmpty()) "removeparam" else "removeparam=$value"
                            }
                            "urlskip" -> {
                                // wiki の規定どおり、信頼できる出典のリストだけで受け付ける。
                                if (!trusted || inverse || value.isNullOrBlank() || modifier != null) return
                                if (!NavigationRewriter.isValidSkipSteps(value)) return
                                modifier = "urlskip=$value"
                            }
                            "method" -> {
                                if (inverse || value.isNullOrBlank() || methods != null) return
                                if (!NetworkConstraints.isValidMethodList(value)) return
                                methods = value
                            }
                            "third-party", "first-party" -> {
                                if (value != null || thirdParty != -1) return
                                thirdParty = if ((option == "third-party") != inverse) 1 else 0
                            }
                            "important" -> {
                                if (!blocking || inverse || value != null) return
                                flags = flags or ContentRequest.FLAG_IMPORTANT
                            }
                            "badfilter" -> {
                                if (inverse || value != null) return
                                flags = flags or ContentRequest.FLAG_BADFILTER
                                canonicalOptions.removeAt(canonicalOptions.lastIndex)
                            }
                            "redirect", "redirect-rule" -> {
                                if (!blocking || inverse || value == null || redirectType != 0) return
                                // 既知の空資源と、独自に作った代替スクリプトだけを扱う。未知の資源は行ごと破棄する。
                                // 末尾の `:数字` は優先度の指定なので外す。
                                redirectType = when (value.replace(Regex(":\\d+$"), "")) {
                                    "empty", "noop.txt", "nooptext" -> ContentRequest.REDIRECT_EMPTY
                                    "noopjs", "noop.js" -> ContentRequest.REDIRECT_SCRIPT
                                    "noopcss", "noop.css" -> ContentRequest.REDIRECT_STYLE
                                    "1x1.gif", "1x1-transparent.gif", "2x2.png", "2x2-transparent.png",
                                    "3x2.png", "3x2-transparent.png", "32x32.png", "32x32-transparent.png" -> ContentRequest.REDIRECT_IMAGE
                                    "noopframe", "noop.html" -> ContentRequest.REDIRECT_FRAME
                                    "noopmp3-0.1s", "noop-0.1s.mp3" -> ContentRequest.REDIRECT_AUDIO
                                    "google-ima.js", "google-ima3" -> ContentRequest.REDIRECT_SURROGATE or ContentRequest.SURROGATE_GOOGLE_IMA
                                    else -> return
                                }
                                redirectRule = option == "redirect-rule"
                            }
                            // サイトキーを検証できないため、制約を落とさずルール全体を無視する。
                            "sitekey" -> return
                            else -> return
                        }
                    }
                }
            }
            filter = filter.substring(0, optionsIndex)
        }

        val domains = domain?.domainsToDomainMap('|')
        if (filter.isEmpty() && optionsIndex < 0) return
        if (modifier != null && (redirectType != 0 || cosmeticType != 0 || flags and ContentRequest.FLAG_IMPORTANT != 0)) return
        if (contentType == 0 && modifier != null) {
            // 書き換えはページ移動（document）に適用するため、種別を指定しない行も document を含める。
            contentType = ContentRequest.TYPE_NETWORK_ALL
        } else if (contentType == 0) {
            contentType = ContentRequest.TYPE_NETWORK_ALL
            // 種別未指定の通信ルールは、ABP と同じく本文ページ（document）と popup を含めない。
            // EasyList 等の汎用パス規則で記事ページ自体を遮断画面へ置き換えないため。
            // uBO と同じく、パスを持たない純粋なホスト規則（||host^）だけは本文ページにも効かせる。
            if (cosmeticType == 0 && !isPureHostnameFilter(filter)) {
                contentType = contentType and (ContentRequest.TYPE_DOCUMENT or ContentRequest.TYPE_POPUP).inv()
            }
        }
        contentType = contentType and excludedTypes.inv()
        // 書き換えは主フレームのページ移動にだけ適用できる。document を含まない行（$xhr 等）は効かないため破棄する。
        if (modifier != null && contentType and ContentRequest.TYPE_DOCUMENT == 0) return
        if (contentType == 0 || (cosmeticType != 0 && flags and ContentRequest.FLAG_IMPORTANT != 0)) return
        if (cosmeticType != 0 && redirectType != 0) return
        flags = flags or redirectType
        if (redirectRule) flags = flags or ContentRequest.FLAG_REDIRECT_RULE
        val constraints = if (entityDomains != null || denyAllow != null || requestDomains != null || methods != null || modifier != null) {
            NetworkConstraints(entityDomains, denyAllow, requestDomains, methods, modifier)
        } else null
        // denyallow は domain= との組み合わせが必須（wiki の規定）。単独では全サイトに広がるため行ごと破棄する。
        if (denyAllow != null && domain == null && entityDomains == null) return
        // 追加条件は要素非表示の例外（elemhide 等）には使わない。意味が変わるため行ごと破棄する。
        if (constraints != null && cosmeticType != 0) return
        val ruleKey = (if (blocking) "" else "@@") + filter +
            if (canonicalOptions.isEmpty()) "" else "$" + canonicalOptions.sorted().joinToString(",")
        if (filter.isEmpty()) filter = "*"

        fun createFilter(mask: Int): UnifiedFilter? =
            if (filter.length >= 2 && filter[0] == '/' && filter[filter.lastIndex] == '/') {
                createRegexFilter(filter.substring(1, filter.lastIndex), mask, ignoreCase, domains, thirdParty)
            } else {
                val isStartsWith = filter.startsWith("||")
                val isEndWith = filter.endsWith('^')
                val content = filter.substring(
                    if (isStartsWith) 2 else 0,
                    if (isEndWith) filter.length - 1 else filter.length
                )
                val isLiteral = content.isLiteralFilter()
                if (isLiteral) {
                    when {
                        isStartsWith && isEndWith -> StartEndFilter(
                            content,
                            mask,
                            ignoreCase,
                            domains,
                            thirdParty
                        )
                        isStartsWith -> StartsWithFilter(content, mask, ignoreCase, domains, thirdParty)
                        isEndWith -> EndWithFilter(content, mask, domains, thirdParty, ignoreCase)
                        else -> ContainsFilter(content, mask, domains, thirdParty, ignoreCase)
                    }
                } else {
                    PatternMatchFilter(filter, mask, ignoreCase, domains, thirdParty)
                }
            }

        if (cosmeticType == 0 || hasNetworkType) {
            val networkFilter = NetworkRuleFilter(createFilter(contentType) ?: return, ruleKey, flags, constraints)
            if (blocking) blackList += networkFilter else whiteList += networkFilter
        }
        if (cosmeticType != 0) {
            // next を共有すると別コンテナの連結を壊すため、カテゴリごとに別インスタンスを作る。
            elementFilterList += NetworkRuleFilter(createFilter(cosmeticType) ?: return, ruleKey, flags)
        }
    }

    private fun String.domainsToDomainMap(delimiter: Char): DomainMap? {
        if (length == 0) return null

        val items = split(delimiter).map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return null
        return if (items.size == 1) {
            if (items[0][0] == '~') {
                SingleDomainMap(false, items[0].substring(1))
            } else {
                SingleDomainMap(true, items[0])
            }
        } else {
            val domains = ArrayDomainMap(items.size)
            items.forEach { domain ->
                if (domain.isEmpty()) return@forEach
                if (domain[0] == '~') {
                    domains[domain.substring(1)] = false
                } else {
                    domains[domain] = true
                    domains.include = true
                }
            }
            domains
        }
    }

    private fun String.isLiteralFilter(): Boolean {
        forEach {
            when (it) {
                '*', '^', '|' -> return false
            }
        }
        return true
    }

    private fun String.getOptionBit(): Int {
        return when (this) {
            "other", "xbl", "dtd" -> ContentRequest.TYPE_OTHER
            "script" -> ContentRequest.TYPE_SCRIPT
            "image", "background" -> ContentRequest.TYPE_IMAGE
            "stylesheet" -> ContentRequest.TYPE_STYLE_SHEET
            "subdocument" -> ContentRequest.TYPE_SUB_DOCUMENT
            "document" -> ContentRequest.TYPE_DOCUMENT
            "websocket" -> ContentRequest.TYPE_WEB_SOCKET
            "media" -> ContentRequest.TYPE_MEDIA
            "font" -> ContentRequest.TYPE_FONT
            "popup" -> ContentRequest.TYPE_POPUP
            "xmlhttprequest" -> ContentRequest.TYPE_XHR
            "all" -> ContentRequest.TYPE_NETWORK_ALL
            "object", "webrtc", "csp", "ping",
            "object-subrequest", "genericblock" -> -1
            "elemhide", "ehide" -> ContentRequest.TYPE_ELEMENT_HIDE
            "generichide", "ghide" -> ContentRequest.TYPE_ELEMENT_GENERIC_HIDE
            else -> 0
        }
    }

    private fun String.decodeComment(url: String?, info: DecoderInfo): String? {
        val comment = split(':')
        if (comment.size < 2) return null

        when (comment[0].substring(1).trim().lowercase(Locale.getDefault())) {
            "title" -> info.title = comment[1].trim()
            "homepage" -> info.homePage = comment[1].trim()
            "last updated" -> info.lastUpdate = comment[1].trim()
            "expires" -> info.expires = comment[1].trim().decodeExpires()
            "version" -> info.version = comment[1].trim()
            "redirect" -> {
                val redirect = comment[1].trim()
                if (url != null && url != redirect) {
                    return url
                }
            }
        }
        return null
    }

    private fun String.decodeExpires(): Int {
        val hours = indexOf("hours")
        if (hours > 0) {
            return try {
                substring(0, hours).trim().toInt()
            } catch (e: NumberFormatException) {
                -1
            }
        }
        val days = indexOf("days")
        if (days > 0) {
            return try {
                substring(0, days).trim().toInt() * 24
            } catch (e: NumberFormatException) {
                -1
            }
        }
        return -1
    }

    class OnRedirectException(val url: String) : IOException()

    private class DecoderInfo : UnifiedFilterInfo(null, null, null, null, null) {
        override var expires: Int? = null
        override var homePage: String? = null
        override var lastUpdate: String? = null
        override var title: String? = null
        override var version: String? = null
    }

    companion object {
        const val HEADER = "[Adblock Plus"

        private const val CONTENT_FILTER_REGEX = "^([^/|@\"!]*?)#([@?\$])?#(.+)\$"
        private val HTML_SCRIPT_FILTER = Regex("\\^script:has-text\\((.+)\\)")
    }
}
