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

/**
 * クイックコントロールで選んでいる項目の名前の吹き出しを置く位置（#32）。
 *
 * 指に隠れないよう、指の上に置く。上に入りきらないときは指の下に置く。
 * 横は指を中心にし、画面からはみ出す分だけ内側へ寄せる。
 */
internal object PieLabelLayout {

    data class Position(val left: Float, val top: Float)

    /**
     * @param offset 指から吹き出しまでの距離
     * @param margin 画面の端との最小の間隔
     */
    fun place(
        touchX: Float,
        touchY: Float,
        labelWidth: Float,
        labelHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
        offset: Float,
        margin: Float,
    ): Position {
        val maxLeft = viewWidth - margin - labelWidth
        val left = if (maxLeft < margin) {
            (viewWidth - labelWidth) / 2
        } else {
            (touchX - labelWidth / 2).coerceIn(margin, maxLeft)
        }

        val above = touchY - offset - labelHeight
        val top = if (above >= margin) {
            above
        } else {
            (touchY + offset).coerceAtMost(viewHeight - margin - labelHeight).coerceAtLeast(margin)
        }
        return Position(left, top)
    }
}
