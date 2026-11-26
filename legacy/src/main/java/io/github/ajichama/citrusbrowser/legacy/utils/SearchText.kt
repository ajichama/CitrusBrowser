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

package io.github.ajichama.citrusbrowser.legacy.utils

import java.util.Locale

/**
 * 画面の中の検索（設定の検索 #25、操作を選ぶ画面の検索 #42）で共通に使う照合。
 *
 * 大文字・小文字と、ひらがな・カタカナを区別しない。検索語は空白（全角を含む）で区切る。
 */
object SearchText {

    private val separators = Regex("[\\s　]+")

    /** 検索語を空白で区切り、照合用にそろえた言葉の一覧にする。空なら空の一覧 */
    fun words(query: String): List<String> =
        query.split(separators).filter { it.isNotEmpty() }.map(::normalize)

    /** 文に、すべての言葉が含まれるか（言葉は [words] で作ったもの） */
    fun containsAll(text: String, words: List<String>): Boolean {
        val normalized = normalize(text)
        return words.all { normalized.contains(it) }
    }

    /** 小文字にし、ひらがなをカタカナにそろえる */
    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val builder = StringBuilder(lower.length)
        for (c in lower) {
            builder.append(if (c in 'ぁ'..'ゖ' || c in 'ゝ'..'ゞ') c + KANA_OFFSET else c)
        }
        return builder.toString()
    }

    /** ひらがなと、対応するカタカナの文字コードの差 */
    private const val KANA_OFFSET = 'ァ' - 'ぁ'
}
