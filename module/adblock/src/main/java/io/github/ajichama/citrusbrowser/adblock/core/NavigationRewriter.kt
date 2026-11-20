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

import io.github.ajichama.citrusbrowser.adblock.filter.unified.NetworkRuleFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.UnifiedFilter
import com.google.re2j.Pattern
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * 主フレームのページ移動の URL を書き換える `$removeparam` と `$urlskip`。
 * 仕様は uBO wiki の Static-filter-syntax（removeparam・urlskip の節）だけを参照した独自実装。
 * WebView ではサブリソースの URL を差し替えられないため、主フレームの GET の移動にだけ適用する。
 */
class NavigationRewriter internal constructor(rules: List<UnifiedFilter>, exceptions: List<UnifiedFilter>) {
    private val rules = rules.filterIsInstance<NetworkRuleFilter>().filter { it.constraints?.modifier != null }
    private val exceptions = exceptions.filterIsInstance<NetworkRuleFilter>().filter { it.constraints?.modifier != null }

    val isEmpty: Boolean get() = rules.isEmpty()

    /** 書き換えた URL。書き換えない場合は null。[blocked] は、この移動が遮断の対象か。 */
    fun rewrite(request: ContentRequest, url: String, blocked: Boolean = false): String? {
        if (rules.isEmpty()) return null
        val matched = rules.filter { it.isMatch(request) }
        if (matched.isEmpty()) return null
        val excepted = exceptions.filter { it.isMatch(request) }.mapNotNull { it.constraints?.modifier }.toSet()
        fun allowed(modifier: String): Boolean {
            val kind = modifier.substringBefore('=')
            // 値のない例外（@@…$removeparam）は、その種類をすべて止める。
            return modifier !in excepted && kind !in excepted
        }
        // urlskip（中継先の URL を取り出す）を先に、次に removeparam を適用する。
        matched.mapNotNull { it.constraints?.modifier }.filter { it.startsWith("urlskip=") && allowed(it) }
            .filter { !blocked || it.split(' ').contains("-blocked") }.forEach { modifier ->
            skip(url, modifier.removePrefix("urlskip="))?.let { return it }
        }
        var current = url
        matched.mapNotNull { it.constraints?.modifier }.filter { it.substringBefore('=') == "removeparam" && allowed(it) }
            .distinct().forEach { modifier ->
                current = removeParams(current, if ('=' in modifier) modifier.substringAfter('=') else "")
            }
        return current.takeIf { it != url }
    }

    companion object {
        private val SKIP_STEP = Regex("\\?[^\\s]+|&\\d+|#|/(?:\\\\.|[^\\\\/])+/|\\+https|-base64|-safebase64|-uricomponent|-blocked")

        /** urlskip の手順が、wiki に記載された指示だけでできているか。 */
        fun isValidSkipSteps(steps: String): Boolean {
            val list = steps.trim().split(Regex("\\s+"))
            return list.isNotEmpty() && list.all { SKIP_STEP.matches(it) }
        }

        /** removeparam の値（空・名前・/正規表現/）が扱えるか。否定（~）は文書にないため扱わない。 */
        fun isValidRemoveParam(value: String?): Boolean {
            if (value.isNullOrEmpty()) return true
            if (value.startsWith('~') || value.contains('|') && !value.startsWith('/')) return false
            if (value.startsWith('/')) return value.length > 2 && value.endsWith('/') && compile(value.substring(1, value.length - 1)) != null
            return value.none { it.isWhitespace() || it == '&' || it == '=' }
        }

        /** wiki の removeparam: 値なしは全パラメータ、名前は完全一致、/正規表現/ は「名前=値」に対して照合する。 */
        fun removeParams(url: String, value: String): String {
            val hashIndex = url.indexOf('#')
            val beforeHash = if (hashIndex >= 0) url.substring(0, hashIndex) else url
            val hash = if (hashIndex >= 0) url.substring(hashIndex) else ""
            val queryIndex = beforeHash.indexOf('?')
            if (queryIndex < 0) return url
            val base = beforeHash.substring(0, queryIndex)
            val params = beforeHash.substring(queryIndex + 1).split('&').filter { it.isNotEmpty() }
            val regex = if (value.length > 2 && value.startsWith('/') && value.endsWith('/')) {
                compile(value.substring(1, value.length - 1)) ?: return url
            } else null
            val kept = params.filterNot { param ->
                when {
                    value.isEmpty() -> true
                    regex != null -> regex.matcher(decode(param)).find()
                    else -> decode(param.substringBefore('=')) == value
                }
            }
            if (kept.size == params.size) return url
            return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + hash
        }

        /** wiki の urlskip の手順を順に適用し、最終的に http(s) の正しい URL になった場合だけ返す。 */
        fun skip(url: String, steps: String): String? {
            var current = url
            for (step in steps.trim().split(Regex("\\s+"))) {
                current = when {
                    step == "-blocked" -> current
                    step.startsWith("?") -> queryValue(current, step.substring(1)) ?: return null
                    step.startsWith("&") -> queryName(current, step.substring(1).toIntOrNull() ?: return null) ?: return null
                    step == "#" -> current.substringAfter('#', "").takeIf { it.isNotEmpty() } ?: return null
                    step.startsWith("/") && step.length > 2 && step.endsWith("/") -> {
                        val matcher = (compile(step.substring(1, step.length - 1)) ?: return null).matcher(current)
                        if (!matcher.find() || matcher.groupCount() < 1) return null
                        matcher.group(1) ?: return null
                    }
                    step == "+https" -> when {
                        current.startsWith("http://", ignoreCase = true) -> "https://" + current.substring(7)
                        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(current) -> current
                        else -> "https://$current"
                    }
                    step == "-base64" -> base64(current, Base64.getMimeDecoder()) ?: return null
                    step == "-safebase64" -> base64(current, Base64.getUrlDecoder()) ?: return null
                    step == "-uricomponent" -> runCatching { URLDecoder.decode(current.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
                    else -> return null
                }
            }
            // 移動先は http(s) の正しい URL に限る（javascript: 等へ移動させない）。
            val parsed = runCatching { URI(current) }.getOrNull() ?: return null
            val scheme = parsed.scheme?.lowercase() ?: return null
            if ((scheme != "http" && scheme != "https") || parsed.host.isNullOrEmpty()) return null
            return current.takeIf { it != url }
        }

        private val patterns = java.util.concurrent.ConcurrentHashMap<String, Pattern>()

        /**
         * リストの正規表現は re2j（線形時間）で扱い、一度だけコンパイルする。
         * java.util.regex の後方参照等による暴走（ReDoS）を、移動のたびに UI スレッドで起こさないため。
         */
        private fun compile(source: String): Pattern? = patterns[source] ?: runCatching { Pattern.compile(source) }.getOrNull()
            ?.also { if (patterns.size < 4096) patterns[source] = it }

        private fun queryParams(url: String): List<String> {
            val query = url.substringBefore('#').substringAfter('?', "")
            return query.split('&').filter { it.isNotEmpty() }
        }

        private fun queryValue(url: String, name: String): String? =
            queryParams(url).firstOrNull { decode(it.substringBefore('=')) == name }?.let { decode(it.substringAfter('=', "")) }

        private fun queryName(url: String, position: Int): String? =
            queryParams(url).getOrNull(position - 1)?.let { decode(it.substringBefore('=')) }

        private fun decode(text: String): String = runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)

        private fun base64(text: String, decoder: Base64.Decoder): String? {
            val padded = text.trim().let { it + "=".repeat((4 - it.length % 4) % 4) }
            return runCatching { String(decoder.decode(padded), Charsets.UTF_8) }.getOrNull()
        }
    }
}
