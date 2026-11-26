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
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class BookmarkNameOrderTest {

    private var nextId = 1L

    private fun site(title: String, url: String = "https://example.com/$title") = BookmarkSite(title, url, nextId++)

    private fun folder(title: String) = BookmarkFolder(title, null, nextId++)

    private fun titles(items: List<BookmarkItem>) = items.map { it.title }

    @Test
    fun フォルダを先にまとめて名前順に並べる() {
        val items = listOf(site("b"), folder("Z"), site("a"), folder("m"))
        val sorted = BookmarkNameOrder.sorted(items, Locale.ENGLISH)
        assertEquals(listOf("m", "Z", "a", "b"), titles(sorted))
    }

    @Test
    fun 大文字と小文字を区別しない() {
        val items = listOf(site("banana"), site("Apple"), site("cherry"), site("apricot"))
        val sorted = BookmarkNameOrder.sorted(items, Locale.ENGLISH)
        assertEquals(listOf("Apple", "apricot", "banana", "cherry"), titles(sorted))
    }

    @Test
    fun 同じ名前は元の順を保つ() {
        val first = site("same", "https://example.com/1")
        val second = site("SAME", "https://example.com/2")
        val third = site("Same", "https://example.com/3")
        val sorted = BookmarkNameOrder.sorted(listOf(first, second, third), Locale.ENGLISH)
        assertEquals(listOf(first, second, third), sorted)
    }

    @Test
    fun 題名が空のブックマークはURLで比べる() {
        val empty = site("", "https://b.example.com/")
        // 空の題名は "https://b.example.com/" として、"a" と "z" の間に入る。
        val items = listOf(site("z"), empty, site("a"))
        val sorted = BookmarkNameOrder.sorted(items, Locale.ENGLISH)
        assertEquals(listOf("a", "", "z"), titles(sorted))
        assertEquals(empty, sorted[1])
    }

    @Test
    fun 元のリストは変えない() {
        val items = listOf(site("b"), site("a"))
        BookmarkNameOrder.sorted(items, Locale.ENGLISH)
        assertEquals(listOf("b", "a"), titles(items))
    }
}
