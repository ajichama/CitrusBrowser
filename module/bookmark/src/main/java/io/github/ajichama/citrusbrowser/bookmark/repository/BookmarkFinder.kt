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

package io.github.ajichama.citrusbrowser.bookmark.repository

import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkFolder
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkItem
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkSite

/**
 * ブックマーク画面の中の検索（#34）。
 *
 * すべてのフォルダから、ブックマークの題名・URL と、フォルダの名前を部分一致で探す。
 * 大文字・小文字は区別しない。結果は、画面の並びと同じ順（フォルダを上から深さ優先）に並べる。
 * アドレス欄の候補に使う [BookmarkManager.search]（単語の頭に一致）とは別のもの。
 */
object BookmarkFinder {

    /** 見つかった項目と、それが入っているフォルダ。 */
    class Hit(val item: BookmarkItem, val parent: BookmarkFolder)

    fun find(root: BookmarkFolder, query: String): List<Hit> {
        val words = query.trim()
        if (words.isEmpty()) return emptyList()
        val hits = mutableListOf<Hit>()
        collect(root, words, hits)
        return hits
    }

    private fun collect(folder: BookmarkFolder, query: String, hits: MutableList<Hit>) {
        folder.itemList.forEach { item ->
            if (matches(item, query)) hits.add(Hit(item, folder))
            if (item is BookmarkFolder) collect(item, query, hits)
        }
    }

    private fun matches(item: BookmarkItem, query: String): Boolean {
        if (item.title?.contains(query, ignoreCase = true) == true) return true
        return item is BookmarkSite && item.url.contains(query, ignoreCase = true)
    }

    /** ルートから [folder] までのフォルダの並び（ルートを含む）。 */
    fun pathOf(folder: BookmarkFolder): List<BookmarkFolder> {
        val path = mutableListOf<BookmarkFolder>()
        var current: BookmarkFolder? = folder
        while (current != null) {
            path.add(0, current)
            current = current.parent
        }
        return path
    }
}
