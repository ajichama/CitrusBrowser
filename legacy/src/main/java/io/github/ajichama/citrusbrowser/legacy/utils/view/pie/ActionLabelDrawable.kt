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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 指で選んでいる操作の名前を示す吹き出し。
 * クイックコントロール（#32）とフローティングツールバー（#40）で同じ見た目にする。
 *
 * [place] で名前と指の位置を渡すと、指の上（上端に近いときは指の下）に置く。
 */
internal class ActionLabelDrawable(context: Context) : Drawable() {

    private val metrics = context.resources.displayMetrics
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, metrics)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BACKGROUND_COLOR
    }
    private val rect = RectF()
    private val paddingH = dp(12f)
    private val paddingV = dp(8f)
    private val offset = dp(56f)
    private val margin = dp(8f)
    private val radius = dp(8f)
    private var text = ""

    /**
     * 名前を決め、指の位置に合わせて吹き出しを置く。
     * 名前が入りきらないときは末尾を省く。置く場所がないときは何も描かない。
     *
     * @param touchX 指の位置（描く先のビューの座標）
     * @param viewWidth 描く先のビューの幅
     */
    fun place(title: String, touchX: Float, touchY: Float, viewWidth: Int, viewHeight: Int) {
        val maxTextWidth = viewWidth - 2 * (margin + paddingH)
        text = if (maxTextWidth <= 0f) "" else
            TextUtils.ellipsize(title, textPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
        if (text.isEmpty()) {
            rect.setEmpty()
            setBounds(0, 0, 0, 0)
            return
        }
        val fm = textPaint.fontMetrics
        val boxWidth = textPaint.measureText(text) + 2 * paddingH
        val boxHeight = fm.descent - fm.ascent + 2 * paddingV
        val pos = PieLabelLayout.place(touchX, touchY, boxWidth, boxHeight,
                viewWidth.toFloat(), viewHeight.toFloat(), offset, margin)
        rect.set(pos.left, pos.top, pos.left + boxWidth, pos.top + boxHeight)
        setBounds(floor(rect.left).toInt(), floor(rect.top).toInt(), ceil(rect.right).toInt(), ceil(rect.bottom).toInt())
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (text.isEmpty()) return
        val fm = textPaint.fontMetrics
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
        canvas.drawText(text, rect.left + paddingH, rect.top + paddingV - fm.ascent, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        textPaint.alpha = alpha
        backgroundPaint.alpha = Color.alpha(BACKGROUND_COLOR) * alpha / 255
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        textPaint.colorFilter = colorFilter
        backgroundPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun dp(value: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)

    companion object {
        private const val BACKGROUND_COLOR = 0xE6303030.toInt()
    }
}
