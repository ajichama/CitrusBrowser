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

package io.github.ajichama.citrusbrowser.adblock.filter.unified.element

import io.github.ajichama.citrusbrowser.adblock.filter.unified.Tag
import java.util.Locale

/** 除外はこの行の適用条件だけに作用し、同じ selector を持つ別の行を例外化しない。 */
class GroupElementFilter(domainExpression: String, isHide: Boolean, selector: String) :
    ElementFilter(domainExpression, isHide, false, selector) {

    private data class Condition(val pattern: String, val excluded: Boolean, val entity: Boolean) {
        fun specificity(host: String, tldRemoved: String?): Int {
            if (pattern.isEmpty()) return 0
            val candidate = if (entity) tldRemoved ?: return -1 else host
            if (candidate != pattern && !candidate.endsWith(".$pattern")) return -1
            // entity の末尾 .* も実際に一致した公開サフィックスの長さとして比較する。
            return pattern.length + if (entity) host.length - candidate.length else 0
        }
    }

    private val conditions = domainExpression.split(',').map { token ->
        val normalized = token.trim().lowercase(Locale.ROOT)
        val excluded = normalized.startsWith('~')
        val value = normalized.removePrefix("~")
        require(value.isNotEmpty() && value.none { it.isWhitespace() || it in "/|~" })
        require('*' !in value || value == "*" || (value.endsWith(".*") && value.count { it == '*' } == 1))
        Condition(if (value == "*") "" else value.removeSuffix("*"), excluded, value.endsWith(".*"))
    }
    private val includes = conditions.filterNot { it.excluded }

    override val type: Int get() = TYPE_GROUP
    override val isGeneric: Boolean get() = includes.isEmpty() || includes.any { it.pattern.isEmpty() }

    // 同じオブジェクトを複数 bucket に置くため、旧形式の単一 next ポインタは使わない。
    val indexTags: Set<String> = if (isGeneric) setOf("") else includes.map { Tag.createBest(it.pattern) }.toSet()

    override fun isMatch(domain: String, tldRemoved: String?): Boolean {
        var selected: Condition? = null
        var longestMatch = -1
        for (condition in conditions) {
            val specificity = condition.specificity(domain, tldRemoved)
            if (specificity < 0) continue
            // 同じ行では最も具体的な指定を採用し、親の除外から子を再び包含できる。
            // 同じ範囲の entity と通常ドメインなら通常ドメイン、同一指定なら末尾を優先する。
            if (specificity > longestMatch ||
                (specificity == longestMatch && (selected?.entity != false || !condition.entity))) {
                selected = condition
                longestMatch = specificity
            }
        }
        return selected?.let { !it.excluded } ?: includes.isEmpty()
    }
}
