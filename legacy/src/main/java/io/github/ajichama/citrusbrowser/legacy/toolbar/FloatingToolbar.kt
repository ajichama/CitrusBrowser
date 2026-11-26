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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.ajichama.citrusbrowser.core.utility.extensions.convertDpToPx
import io.github.ajichama.citrusbrowser.core.utility.extensions.getResColor
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.action.Action
import io.github.ajichama.citrusbrowser.legacy.action.manager.ActionController
import io.github.ajichama.citrusbrowser.legacy.action.manager.ActionIconManager
import io.github.ajichama.citrusbrowser.legacy.action.ActionNameArray
import io.github.ajichama.citrusbrowser.legacy.action.manager.ToolbarActionManager
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.ActionLabelDrawable
import io.github.ajichama.citrusbrowser.legacy.utils.view.swipebutton.SwipeImageButton
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import io.github.ajichama.citrusbrowser.ui.theme.ThemeData

/**
 * ページの上に浮かび、端のつまみを引いて好きな位置へ動かせるツールバー（#35）。
 *
 * 位置は、動かせる範囲に対する割合（0〜1）で保存する。画面の回転や大きさが変わっても、
 * 同じ割合の位置に置き直すので、画面からはみ出さない。全画面でも表示したままにし、
 * 文字の入力中（キーボードの表示中）だけ隠す。
 *
 * ボタンをスワイプして別の操作に切り替わったときは、離すと実行される操作の名前を、
 * 指の上の吹き出しで示す（#40）。吹き出しは親のビューの overlay に描く。
 */
class FloatingToolbar(
    context: Context,
    private val controller: ActionController,
    private val iconManager: ActionIconManager,
) : LinearLayout(context) {

    private val grip = ImageView(context).apply {
        setImageResource(R.drawable.ic_drag_indicator_white_24dp)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = context.getString(R.string.floating_toolbar_move)
    }
    private val buttonLayout = LinearLayout(context)
    private var buttonController: ButtonToolbarController? = null
    private val backgroundShape = GradientDrawable().apply {
        cornerRadius = context.convertDpToPx(12).toFloat()
    }
    private var themeData: ThemeData? = null
    private var isActive = false
    private var isImeShown = false

    private var showActionLabel = true
    private val actionLabel = ActionLabelDrawable(context)
    private var isActionLabelShown = false
    private val actionNames by lazy(LazyThreadSafetyMode.NONE) { ActionNameArray(context) }
    private val screenLocation = IntArray(2)
    private val swipeActionListener = SwipeImageButton.OnSwipeActionListener { action, rawX, rawY ->
        updateActionLabel(action, rawX, rawY)
    }

    init {
        background = backgroundShape
        elevation = context.convertDpToPx(4).toFloat()
        visibility = View.GONE
        addView(grip)
        addView(buttonLayout)
        grip.setOnTouchListener(DragListener())
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyPosition()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? View)?.addOnLayoutChangeListener(parentLayoutListener)
    }

    override fun onDetachedFromWindow() {
        hideActionLabel()
        (parent as? View)?.removeOnLayoutChangeListener(parentLayoutListener)
        super.onDetachedFromWindow()
    }

    private val parentLayoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) post { applyPosition() }
    }

    fun onPreferenceReset() {
        isActive = AppPrefs.floatbar_enable.get()
        showActionLabel = AppPrefs.floatbar_show_label.get()
        hideActionLabel()
        val vertical = AppPrefs.floatbar_vertical.get()
        orientation = if (vertical) VERTICAL else HORIZONTAL
        buttonLayout.orientation = orientation

        val size = context.convertDpToPx(AppPrefs.floatbar_size.get())
        val gripLength = size * 3 / 5
        grip.layoutParams = if (vertical) LayoutParams(size, gripLength) else LayoutParams(gripLength, size)

        // 大きさを変えられるよう、ボタンは毎回作り直す
        buttonLayout.removeAllViews()
        buttonController = if (isActive) {
            ButtonToolbarController(buttonLayout, controller, iconManager, size).apply {
                addButtons(ToolbarActionManager.getInstance(context).floatbar1.list)
                onSwipeActionListener = swipeActionListener
            }
        } else {
            null
        }
        alpha = AppPrefs.floatbar_alpha.get() / 100f
        applyTheme()
        updateVisibility()
        post { applyPosition() }
    }

    fun onThemeChanged(themeData: ThemeData?) {
        this.themeData = themeData
        applyTheme()
    }

    fun onImeChanged(isShown: Boolean) {
        isImeShown = isShown
        updateVisibility()
    }

    fun notifyChangeWebState() {
        buttonController?.notifyChangeState()
    }

    fun resetToolBar() {
        buttonController?.resetIcon()
    }

    private fun applyTheme() {
        val theme = themeData
        backgroundShape.setColor(
            if (theme != null && theme.toolbarBackgroundColor != 0) theme.toolbarBackgroundColor
            else context.getResColor(R.color.deep_gray)
        )
        AbstractToolbar.applyTheme(grip)
        buttonController?.let {
            AbstractToolbar.applyTheme(it)
            it.notifyChangeState()
        }
    }

    private fun updateVisibility() {
        visibility = if (isActive && !isImeShown) View.VISIBLE else View.GONE
        if (visibility != View.VISIBLE) hideActionLabel()
    }

    /** スワイプで選んでいる操作の名前を、指の上に出す。操作が無いときは消す */
    private fun updateActionLabel(action: Action?, rawX: Float, rawY: Float) {
        val host = parent as? ViewGroup
        val title = if (showActionLabel && isShown) action?.toString(actionNames) else null
        if (host == null || title.isNullOrEmpty()) {
            hideActionLabel()
            return
        }
        host.getLocationOnScreen(screenLocation)
        actionLabel.place(title, rawX - screenLocation[0], rawY - screenLocation[1], host.width, host.height)
        if (!isActionLabelShown) {
            host.overlay.add(actionLabel)
            isActionLabelShown = true
        }
        host.invalidate()
    }

    private fun hideActionLabel() {
        if (!isActionLabelShown) return
        isActionLabelShown = false
        (parent as? ViewGroup)?.let {
            it.overlay.remove(actionLabel)
            it.invalidate()
        }
    }

    private fun rangeX(): Int = ((parent as? View)?.width ?: 0) - width

    private fun rangeY(): Int = ((parent as? View)?.height ?: 0) - height

    private fun applyPosition() {
        if (parent == null) return
        translationX = FloatingToolbarPosition.toOffset(AppPrefs.floatbar_x.get(), rangeX())
        translationY = FloatingToolbarPosition.toOffset(AppPrefs.floatbar_y.get(), rangeY())
    }

    /** つまみを引いて動かし、離したら位置を保存する */
    private inner class DragListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0f
        private var startY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = translationX
                    startY = translationY
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    translationX = (startX + event.rawX - startRawX).coerceIn(0f, rangeX().coerceAtLeast(0).toFloat())
                    translationY = (startY + event.rawY - startRawY).coerceIn(0f, rangeY().coerceAtLeast(0).toFloat())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    FloatingToolbarPosition.toFraction(translationX, rangeX())?.let { AppPrefs.floatbar_x.set(it) }
                    FloatingToolbarPosition.toFraction(translationY, rangeY())?.let { AppPrefs.floatbar_y.set(it) }
                    AppPrefs.commit(context, AppPrefs.floatbar_x, AppPrefs.floatbar_y)
                }
            }
            return true
        }
    }
}

/** フローティングツールバーの位置と、保存する割合（0〜1）の変換 */
internal object FloatingToolbarPosition {

    /** 割合を、動かせる範囲の中の位置にする。範囲が無いときは端に置く */
    fun toOffset(fraction: Float, range: Int): Float =
        range.coerceAtLeast(0) * fraction.coerceIn(0f, 1f)

    /** 位置を割合にする。範囲が無いときは保存しない（null） */
    fun toFraction(offset: Float, range: Int): Float? =
        if (range <= 0) null else (offset / range).coerceIn(0f, 1f)
}
