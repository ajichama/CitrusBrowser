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
                        )
                    } else {
                        trimmedLine.decodeFilter(black, white, elementDisableFilter)
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
    ) {
        if (!body.isSupportedSelector()) return

        if (domains == null && type == "@") return

        var domainList = domains?.run {
            splitToSequence(',')
                .map { it.trim().lowercase(Locale.ROOT) }
                .toList()
        } ?: emptyList()

        if (domainList.isEmpty()) {
            domainList = listOf("")
        }

        val isHide = when (type) {
            "@" -> false
            null -> true
            else -> return
        }

        if (domainList.any { token ->
            val value = token.removePrefix("~")
            (value.isEmpty() && !(domainList.size == 1 && token.isEmpty())) ||
                value.any { it.isWhitespace() || it in "/|~" } ||
                ('*' in value && value != "*" && !(value.endsWith(".*") && value.count { it == '*' } == 1))
        }) return
        if (domainList.size > 1) {
            elementFilterList += GroupElementFilter(domainList.joinToString(","), isHide, body.sanitizeSelector())
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
                TldRemovedElementFilter(domain, isHide, isNot, body.sanitizeSelector())
            } else {
                PlaneElementFilter(domain, isHide, isNot, body.sanitizeSelector())
            }
        }
    }

    private fun String.sanitizeSelector() = trim().replace("\\", "\\\\").replace("'", "\\'")
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
    ) {
        var contentType = 0
        var ignoreCase = true
        var domain: String? = null
        var thirdParty = -1
        var filter = this
        var cosmeticType = 0
        var hasNetworkType = false
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
                if (option.isEmpty()) return@forEach

                val inverse = option[0] == '~'
                if (inverse) {
                    option = option.substring(1)
                }

                option = option.lowercase(Locale.ENGLISH)
                val type = option.getOptionBit()
                if (type == -1) return

                when {
                    type > 0x00ff_ffff -> {
                        // 要素非表示の制御は例外専用。通常通信の許可へ拡大しない。
                        if (blocking || inverse || value != null) return
                        cosmeticType = cosmeticType or type
                    }
                    type > 0 -> {
                        hasNetworkType = true
                        contentType = if (inverse) {
                            if (contentType == 0) contentType = 0xffff
                            contentType and (type.inv())
                        } else {
                            contentType or type
                        }
                    }
                    type == 0 -> {
                        when (option) {
                            "match-case" -> ignoreCase = inverse
                            "domain" -> {
                                if (inverse || value.isNullOrBlank()) return
                                if (value.split('|').any {
                                    val name = it.trim().removePrefix("~")
                                    name.isEmpty() || name.any { character -> character.isWhitespace() || character in "*/~" }
                                }) return
                                domain = value
                            }
                            "third-party" -> thirdParty = if (inverse) 0 else 1
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
        if (contentType == 0) contentType = 0xffff

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
            val networkFilter = createFilter(contentType) ?: return
            if (blocking) blackList += networkFilter else whiteList += networkFilter
        }
        if (cosmeticType != 0) {
            // next を共有すると別コンテナの連結を壊すため、カテゴリごとに別インスタンスを作る。
            elementFilterList += createFilter(cosmeticType) ?: return
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
    }
}
