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

package io.github.ajichama.citrusbrowser.legacy.utils.view.pie

import org.junit.Assert.assertEquals
import org.junit.Test

class PieLabelLayoutTest {

    private fun place(touchX: Float, touchY: Float, labelWidth: Float = 200f, viewWidth: Float = 1000f) =
        PieLabelLayout.place(touchX, touchY, labelWidth, 50f, viewWidth, 2000f, offset = 100f, margin = 10f)

    @Test
    fun 指の上に指を中心にして置く() {
        assertEquals(PieLabelLayout.Position(400f, 850f), place(500f, 1000f))
    }

    @Test
    fun 画面の端からはみ出す分は内側へ寄せる() {
        assertEquals(790f, place(950f, 1000f).left)
        assertEquals(10f, place(20f, 1000f).left)
    }

    @Test
    fun 上に入りきらないときは指の下に置く() {
        assertEquals(250f, place(500f, 150f).top)
    }

    @Test
    fun 下にも入りきらないときは画面の中に収める() {
        val pos = PieLabelLayout.place(500f, 100f, 200f, 50f, 1000f, 200f, offset = 100f, margin = 10f)
        assertEquals(140f, pos.top)
    }

    @Test
    fun 画面より幅が広いときは中央に置く() {
        assertEquals(-50f, place(100f, 1000f, labelWidth = 1100f).left)
    }
}
