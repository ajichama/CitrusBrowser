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

package io.github.ajichama.citrusbrowser.adblock.filter.unified

import io.github.ajichama.citrusbrowser.adblock.core.ContentRequest
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementContainer.Companion.removeEffectiveTld
import java.util.Locale

/**
 * 通信ルールの追加条件。既存の DomainMap で表せない条件だけを持つ。
 * - pageDomains: `$domain=` に entity（`example.*`）を含む場合の、ページのホストの条件
 * - denyAllow: `$denyallow=` 要求先のホストがこれに当たれば、ルールを適用しない
 * - requestDomains: `$to=` 要求先のホストの条件（entity・否定を含む）
 * - methods: `$method=` HTTP メソッドの条件（否定を含む）
 */
internal class NetworkConstraints(
    val pageDomains: String? = null,
    val denyAllow: String? = null,
    val requestDomains: String? = null,
    val methods: String? = null,
) {
    private val page = pageDomains?.let(::HostCondition)
    private val deny = denyAllow?.split('|')?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() }
    private val request = requestDomains?.let(::HostCondition)
    private val methodIncludes = methods?.split('|')?.filterNot { it.startsWith('~') }?.map { it.lowercase(Locale.ROOT) }
    private val methodExcludes = methods?.split('|')?.filter { it.startsWith('~') }?.map { it.substring(1).lowercase(Locale.ROOT) }

    fun matches(request: ContentRequest): Boolean {
        page?.let { if (!it.matches(request.pageUrl.host)) return false }
        val requestHost = request.url.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        deny?.let { list -> if (requestHost != null && list.any { requestHost == it || requestHost.endsWith(".$it") }) return false }
        this.request?.let { if (!it.matches(requestHost)) return false }
        if (methods != null) {
            val method = request.method.lowercase(Locale.ROOT)
            if (methodExcludes!!.contains(method)) return false
            if (methodIncludes!!.isNotEmpty() && !methodIncludes.contains(method)) return false
        }
        return true
    }

    /** 保存形式。各欄は `\u001F` で区切る（値に含まれない文字）。 */
    fun encode(): String = listOf(pageDomains, denyAllow, requestDomains, methods).joinToString(SEPARATOR) { it ?: "" }

    override fun equals(other: Any?): Boolean = other is NetworkConstraints && encode() == other.encode()
    override fun hashCode(): Int = encode().hashCode()

    /** `a.com|~b.com|c.*` の形のホスト条件。否定に当たれば不一致、肯定があればどれかに当たる必要がある。 */
    private class HostCondition(expression: String) {
        private val includes = mutableListOf<String>()
        private val excludes = mutableListOf<String>()

        init {
            expression.split('|').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.forEach {
                if (it.startsWith('~')) excludes += it.substring(1) else includes += it
            }
        }

        fun matches(rawHost: String?): Boolean {
            val host = rawHost?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return includes.isEmpty()
            val tldRemoved by lazy { host.removeEffectiveTld() }
            fun hit(token: String): Boolean = if (token.endsWith(".*")) {
                // entity: 公開サフィックスを除いた名前で照合する（example.* は example.co.jp にも当たる）。
                val name = token.removeSuffix("*")
                val removed = tldRemoved ?: return false
                removed == name || removed.endsWith(".$name")
            } else {
                host == token || host.endsWith(".$token")
            }
            if (excludes.any(::hit)) return false
            return includes.isEmpty() || includes.any(::hit)
        }
    }

    companion object {
        private const val SEPARATOR = "\u001F"

        fun decode(text: String): NetworkConstraints? {
            if (text.isEmpty()) return null
            val parts = text.split(SEPARATOR)
            if (parts.size != 4) return null
            fun part(index: Int) = parts[index].takeIf { it.isNotEmpty() }
            return NetworkConstraints(part(0), part(1), part(2), part(3))
        }

        private val HOST = Regex("[a-z0-9.-]+")

        /** `example.com`・`~example.com`・`example.*` の並び（`|` 区切り）として正しいか。 */
        fun isValidHostList(value: String, allowNegation: Boolean, allowEntity: Boolean): Boolean {
            val tokens = value.split('|').map { it.trim().lowercase(Locale.ROOT) }
            if (tokens.isEmpty() || tokens.any { it.isEmpty() }) return false
            return tokens.all { token ->
                val body = if (allowNegation) token.removePrefix("~") else token
                if (body.isEmpty() || body.startsWith('~')) return@all false
                if (body.endsWith(".*")) allowEntity && HOST.matches(body.removeSuffix(".*")) && !body.removeSuffix(".*").contains('*')
                else HOST.matches(body)
            }
        }

        fun isValidMethodList(value: String): Boolean =
            value.split('|').all { Regex("~?[a-zA-Z]+").matches(it.trim()) }
    }
}
