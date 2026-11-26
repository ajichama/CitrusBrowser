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
import java.text.Collator
import java.util.Locale

/**
 * ブックマークを名前順に並べる順序（#33）。
 *
 * - フォルダを先にまとめ、フォルダ同士・ブックマーク同士をそれぞれ名前順に並べる。
 * - 名前は、端末の言語の並び順（[Collator]）で比べ、大文字・小文字やアクセントの違いは区別しない。
 * - 題名が空のブックマークは URL で比べる。名前が同じものは、元の順を保つ。
 */
object BookmarkNameOrder {

    fun sorted(items: List<BookmarkItem>, locale: Locale): List<BookmarkItem> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        // 比べるたびに作らないよう、照合用のキーを先に作る。
        val keys = items.associateWith { collator.getCollationKey(nameOf(it)) }
        // sortedWith は安定なので、名前が同じものは元の順のまま残る。
        return items.sortedWith(
            compareBy<BookmarkItem> { if (it is BookmarkFolder) 0 else 1 }
                .thenComparator { a, b -> keys.getValue(a).compareTo(keys.getValue(b)) },
        )
    }

    private fun nameOf(item: BookmarkItem): String {
        val title = item.title?.trim().orEmpty()
        if (title.isNotEmpty()) return title
        return if (item is BookmarkSite) item.url else ""
    }
}
