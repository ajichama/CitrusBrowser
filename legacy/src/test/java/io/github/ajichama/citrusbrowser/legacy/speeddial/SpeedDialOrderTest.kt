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

package io.github.ajichama.citrusbrowser.legacy.speeddial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedDialOrderTest {

    private fun reorder(current: List<Int>, requested: List<Int>) =
        SpeedDialOrder.reorder(current, requested) { it }

    @Test
    fun ページの順に並べ替える() {
        assertEquals(listOf(3, 1, 2), reorder(listOf(1, 2, 3), listOf(3, 1, 2)))
    }

    @Test
    fun 順番が変わらないときは保存しない() {
        assertNull(reorder(listOf(1, 2, 3), listOf(1, 2, 3)))
    }

    @Test
    fun ページに無かった項目は元の順で後ろへ続ける() {
        // ページを開いた後に、別の画面で 4 を足した場合
        assertEquals(listOf(2, 1, 3, 4), reorder(listOf(1, 2, 3, 4), listOf(2, 1, 3)))
    }

    @Test
    fun 今は無い項目と重なった番号は無視する() {
        // ページを開いた後に、別の画面で 2 を消した場合
        assertEquals(listOf(3, 1), reorder(listOf(1, 3), listOf(3, 2, 3, 1)))
    }

    @Test
    fun 番号の並びを読む() {
        assertEquals(listOf(3, 1, 20), SpeedDialOrder.parseIds("3, 1,x,,20"))
        assertEquals(emptyList<Int>(), SpeedDialOrder.parseIds(""))
    }
}
