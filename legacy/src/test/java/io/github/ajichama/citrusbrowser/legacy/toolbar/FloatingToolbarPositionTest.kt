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

package io.github.ajichama.citrusbrowser.legacy.toolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingToolbarPositionTest {

    @Test
    fun 割合を動かせる範囲の中の位置にする() {
        assertEquals(0f, FloatingToolbarPosition.toOffset(0f, 800), 0f)
        assertEquals(400f, FloatingToolbarPosition.toOffset(0.5f, 800), 0f)
        assertEquals(800f, FloatingToolbarPosition.toOffset(1f, 800), 0f)
    }

    @Test
    fun 範囲の外の割合は端にそろえる() {
        assertEquals(0f, FloatingToolbarPosition.toOffset(-0.5f, 800), 0f)
        assertEquals(800f, FloatingToolbarPosition.toOffset(2f, 800), 0f)
    }

    @Test
    fun 範囲が無いときは端に置く() {
        assertEquals(0f, FloatingToolbarPosition.toOffset(0.7f, -100), 0f)
    }

    @Test
    fun 位置を割合にする() {
        assertEquals(0.25f, FloatingToolbarPosition.toFraction(200f, 800)!!, 0.0001f)
        assertEquals(1f, FloatingToolbarPosition.toFraction(900f, 800)!!, 0f)
    }

    @Test
    fun 範囲が無いときは割合を保存しない() {
        assertNull(FloatingToolbarPosition.toFraction(10f, 0))
    }

    @Test
    fun 回転して範囲が変わっても同じ割合の位置に置く() {
        val fraction = FloatingToolbarPosition.toFraction(300f, 1200)!!
        assertEquals(225f, FloatingToolbarPosition.toOffset(fraction, 900), 0.001f)
    }
}
