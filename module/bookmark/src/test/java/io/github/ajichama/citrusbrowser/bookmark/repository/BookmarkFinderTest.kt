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
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkFinderTest {

    private var nextId = 1L
    private val root = BookmarkFolder(null, null, -1)

    private fun site(parent: BookmarkFolder, title: String, url: String) =
        BookmarkSite(title, url, nextId++).also { parent.add(it) }

    private fun folder(parent: BookmarkFolder, title: String) =
        BookmarkFolder(title, parent, nextId++).also { parent.add(it) }

    @Test
    fun すべてのフォルダから題名とURLを部分一致で探す() {
        val work = folder(root, "Work")
        val docs = folder(work, "Docs")
        val byTitle = site(docs, "Android Developers", "https://developer.android.com/")
        val byUrl = site(root, "Release notes", "https://example.com/android/notes")
        site(work, "Other", "https://other.example/")

        val hits = BookmarkFinder.find(root, "android")

        // 日本語名のテストの中では、補助のクラスを生むラムダ（sortedBy など）を使わない。
        // クラスファイルの名前に関数名が入り、ビルドの環境によっては書き出せない。
        assertEquals(listOf(byTitle, byUrl), hits.map { it.item })
        assertSame(docs, hits.single { it.item === byTitle }.parent)
        assertSame(root, hits.single { it.item === byUrl }.parent)
    }

    @Test
    fun フォルダの名前でも見つかり画面の並びの順に並ぶ() {
        val travel = folder(root, "Travel")
        val inside = site(travel, "Hotel travel guide", "https://hotel.example/")
        val after = site(root, "travel blog", "https://blog.example/")

        val hits = BookmarkFinder.find(root, "TRAVEL")

        assertEquals(listOf(travel, inside, after), hits.map { it.item })
    }

    @Test
    fun 空や空白だけの入力では何も返さない() {
        site(root, "a", "https://a.example/")
        assertTrue(BookmarkFinder.find(root, "").isEmpty())
        assertTrue(BookmarkFinder.find(root, "   ").isEmpty())
    }

    @Test
    fun 前後の空白は無視する() {
        val hit = site(root, "News", "https://news.example/")
        assertEquals(listOf(hit), BookmarkFinder.find(root, "  news ").map { it.item })
    }

    @Test
    fun ルートからの経路を返す() {
        val a = folder(root, "A")
        val b = folder(a, "B")
        assertEquals(listOf(root, a, b), BookmarkFinder.pathOf(b))
        assertEquals(listOf(root), BookmarkFinder.pathOf(root))
    }
}
