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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 画面の中の検索の照合（#25・#42） */
class SearchTextTest {

    @Test
    fun hiraganaMatchesKatakana() {
        assertTrue(SearchText.containsAll("タブを閉じる", SearchText.words("たぶ")))
        assertTrue(SearchText.containsAll("たぶを閉じる", SearchText.words("タブ")))
    }

    @Test
    fun caseIsIgnored() {
        assertTrue(SearchText.containsAll("Close tab", SearchText.words("CLOSE")))
    }

    @Test
    fun allWordsMustMatch() {
        val words = SearchText.words("たぶ　閉じる")
        assertEquals(2, words.size)
        assertTrue(SearchText.containsAll("タブを閉じる", words))
        assertFalse(SearchText.containsAll("新しいタブ", words))
    }

    @Test
    fun blankQueryHasNoWords() {
        assertEquals(emptyList<String>(), SearchText.words("  　 "))
    }

    @Test
    fun kanjiAndLongVowelMarkAreKept() {
        assertEquals("ページ", SearchText.normalize("ぺーじ"))
        assertEquals("先頭", SearchText.normalize("先頭"))
        // 照合では両方をそろえるので、ひらがなの「の」を含む名前にも合う
        assertTrue(SearchText.containsAll("ページの先頭へ", SearchText.words("ぺーじの")))
    }
}
