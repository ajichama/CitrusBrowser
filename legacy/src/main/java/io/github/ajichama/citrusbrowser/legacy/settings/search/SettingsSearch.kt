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

package io.github.ajichama.citrusbrowser.legacy.settings.search

import io.github.ajichama.citrusbrowser.legacy.utils.SearchText

/**
 * 設定の検索の1項目（#25）。
 *
 * @property title 項目名
 * @property summary 説明（XML に書かれた固定の文だけ。値によって変わるものは持たない）
 * @property path 表示する場所（画面・区分の名前を上から順に）
 * @property topKey 設定のトップ画面の項目のキー（最初に開く画面）
 * @property clickKeys 最初の画面から順に押す項目のキー（入れ子の画面・別の画面への入口）
 * @property key 目的の項目のキー（スクロールする先。無ければ画面を開くだけ）
 */
class SettingsSearchEntry(
    val title: String,
    val summary: String?,
    val path: List<String>,
    val topKey: String,
    val clickKeys: List<String>,
    val key: String?,
)

object SettingsSearch {

    /**
     * 項目名か説明に、空白で区切った言葉をすべて含む項目を返す
     * （大文字・小文字と、ひらがな・カタカナは区別しない。照合は [SearchText]）。
     * 場所（画面の名前）は比べない。並びは索引の順（画面の上から）のまま。
     */
    fun find(entries: List<SettingsSearchEntry>, query: String): List<SettingsSearchEntry> {
        val words = SearchText.words(query)
        if (words.isEmpty()) return emptyList()
        return entries.filter { entry -> matches(entry, words) }
    }

    private fun matches(entry: SettingsSearchEntry, words: List<String>): Boolean {
        val title = SearchText.normalize(entry.title)
        val summary = entry.summary?.let(SearchText::normalize)
        return words.all { word -> title.contains(word) || summary?.contains(word) == true }
    }
}
