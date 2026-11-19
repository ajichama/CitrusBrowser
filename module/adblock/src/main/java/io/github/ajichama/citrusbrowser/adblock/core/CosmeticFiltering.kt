/*
 * Copyright 2020 Hazuki
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
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementContainer

class CosmeticFiltering(
    private val disables: FilterContainer,
    private val filters: ElementContainer,
    // 本体では scriptlet だけを入れた別の入れ物を渡す。省略時は同じ入れ物から絞り込む。
    private val scriptletFilters: ElementContainer = filters,
) {
    private var cacheUrl: Uri? = null
    private var cache: String? = null
    private var cacheIgnoreGeneric = false

    fun loadScript(url: Uri, ignoreGeneric: Boolean = false): String? {
        if (cacheUrl == url && cacheIgnoreGeneric == ignoreGeneric) return cache
        cacheUrl = url
        cacheIgnoreGeneric = ignoreGeneric
        cache = null

        // 種別番号ではなく contentType で照合する。別の generichide が先に登録されても elemhide を優先する。
        if (disables[ContentRequest(url, url, ContentRequest.TYPE_ELEMENT_HIDE, false)] != null) return null
        val isUseGeneric = !ignoreGeneric &&
            disables[ContentRequest(url, url, ContentRequest.TYPE_ELEMENT_GENERIC_HIDE, false)] == null

        // scriptlet は同じ保存領域に同居しているため、CSS には含めない。
        val results = filters[url, isUseGeneric].filterNot { Scriptlets.isStored(it.selector) }
        if (results.isEmpty()) return null

        val builder = StringBuilder()
        // 新旧キャッシュで引用符の保存形式が異なっても、同じ CSS の例外を適用する。
        val plain = results.asSequence().filter { !it.isHide }.map { it.selector.escapeCachedSelector() }.toSet()
        val selector = results.asSequence().filter { it.isHide }
            .map { it.selector.escapeCachedSelector() }.filterNot { it in plain }.distinct().toList()
        if (selector.isEmpty()) return null

        builder.append("(function(){const b=[")
        selector.forEach {
            builder.append('\'').append(it).append("',")
        }
        // ページの寿命中 CSS を保ち、後挿入や属性変更にも通常の CSS 照合を適用する。
        builder.append("];").append(COSMETIC_STYLE_SCRIPT).append("})()")

        val script = builder.toString()

        cache = script

        return script
    }

    /**
     * フレームの URL に適用する scriptlet の呼び出し（[正式名, 引数...]）を返す。
     * 状態を持たないため、JavaScript ブリッジのスレッドから呼んでよい。
     * `$elemhide` の例外ではページの要素フィルタと一緒に止め、`#@#+js()` ではそのホストのすべてを止める。
     */
    fun scriptlets(url: Uri): List<List<String>> {
        if (disables[ContentRequest(url, url, ContentRequest.TYPE_ELEMENT_HIDE, false)] != null) return emptyList()
        // scriptlet はドメイン指定が必須なので、汎用の要素非表示は引かない。
        val stored = scriptletFilters[url, false].filter { Scriptlets.isStored(it.selector) }
        if (stored.isEmpty()) return emptyList()
        val exceptions = stored.asSequence().filter { !it.isHide }.map { it.selector }.toSet()
        // #@#+js() は scriptlet だけを止め、procedural の要素フィルタは止めない。
        val scriptletsDisabled = Scriptlets.encode(emptyList()) in exceptions
        return stored.asSequence().filter { it.isHide }.map { it.selector }
            .filterNot { it in exceptions }
            .filterNot { scriptletsDisabled && !it.startsWith(Scriptlets.PROCEDURAL_PREFIX) }
            .distinct().map(Scriptlets::decode).toList()
    }

    private fun String.escapeCachedSelector(): String = buildString {
        // 旧キャッシュの未エスケープ引用符も保護し、既存の二重バックスラッシュは維持する。
        var escaped = false
        this@escapeCachedSelector.forEach { character ->
            when (character) {
                '\\' -> {
                    append(character)
                    escaped = !escaped
                }
                '\'' -> {
                    if (!escaped) append('\\')
                    append(character)
                    escaped = false
                }
                '\n', '\r', '\u2028', '\u2029' -> {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    escaped = false
                }
                else -> {
                    append(character)
                    escaped = false
                }
            }
        }
        if (escaped) append('\\')
    }

}
