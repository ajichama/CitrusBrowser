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
) {
    private var cacheUrl: Uri? = null
    private var cache: String? = null

    fun loadScript(url: Uri): String? {
        if (cacheUrl == url) return cache

        val request = ContentRequest(url, url, TYPE, false)
        val result = disables[request]

        val isUseGeneric = when (result?.filterType ?: 0) {
            ContentRequest.TYPE_ELEMENT_HIDE -> return null
            ContentRequest.TYPE_ELEMENT_GENERIC_HIDE -> false
            else -> true
        }

        val results = filters[url, isUseGeneric]
        if (results.isEmpty()) return null

        val builder = StringBuilder()
        // 新旧キャッシュで引用符の保存形式が異なっても、同じ CSS の例外を適用する。
        val plain = results.asSequence().filter { !it.isHide }.map { it.selector.escapeCachedSelector() }.toSet()
        val selector = results.asSequence().filter { it.isHide }
            .map { it.selector.escapeCachedSelector() }.filterNot { it in plain }.toList()

        builder.append("(function(){const b=[")
        selector.forEach {
            builder.append('\'').append(it).append("',")
        }
        // 未対応の拡張セレクター一件で、残りの通常 CSS まで止めない。
        builder.append("];b.forEach(a=>{try{document.querySelectorAll(a).forEach(n=>{n.remove()})}catch(e){}})})()")

        val script = builder.toString()

        cacheUrl = url
        cache = script

        return script
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

    companion object {
        private const val TYPE = ContentRequest.TYPE_ELEMENT_GENERIC_HIDE or
            ContentRequest.TYPE_ELEMENT_HIDE
    }
}
