/*
 * Copyright (C) 2017-2019 Hazuki
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

package io.github.ajichama.citrusbrowser.legacy.utils.view.swipebutton

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import io.github.ajichama.citrusbrowser.core.utility.extensions.convertDpToPx
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.action.Action
import io.github.ajichama.citrusbrowser.legacy.action.manager.ActionController
import io.github.ajichama.citrusbrowser.legacy.action.manager.ActionIconManager
import io.github.ajichama.citrusbrowser.legacy.action.manager.SoftButtonActionFile
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import io.github.ajichama.citrusbrowser.ui.theme.ThemeData
import io.github.ajichama.citrusbrowser.ui.widget.swipebutton.OverlayImageButton
import io.github.ajichama.citrusbrowser.ui.widget.swipebutton.SmallButtonDrawable
import io.github.ajichama.citrusbrowser.ui.widget.swipebutton.SwipeController

class SwipeImageButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : OverlayImageButton(context, attrs), SwipeController.OnChangeListener {
    private val mController = SwipeSoftButtonController(getContext().applicationContext)
    private val smallIconSize = context.convertDpToPx(12)

    /**
     * スワイプで選んでいる操作が変わったとき・指が動いたとき・指を離したときに知らせる（#40）。
     * 押す・長押し・取り消しのときと、指を離したときは、操作を null で知らせる。
     */
    fun interface OnSwipeActionListener {
        fun onSwipeAction(action: Action?, rawX: Float, rawY: Float)
    }

    var onSwipeActionListener: OnSwipeActionListener? = null
        set(value) {
            if (value == null) field?.onSwipeAction(null, 0f, 0f)
            field = value
        }
    private var swipeAction: Action? = null
    private var lastRawX = 0f
    private var lastRawY = 0f

    init {
        scaleType = ScaleType.CENTER_INSIDE
    }

    fun setActionData(action_list: SoftButtonActionFile, controller: ActionController, iconManager: ActionIconManager) {
        mController.setActionData(action_list, controller, iconManager)
        mController.setOnChangeListener(this)
        setImageDrawable(mController.defaultIcon)
        setOverlayIcon(AppPrefs.toolbar_small_icon.get())
        setBackgroundResource(R.drawable.swipebtn_image_background_normal)
    }

    fun setToDefault() {
        mController.setToDefault()
    }

    fun notifyChangeState() {
        mController.notifyChangeState()
        setOverlayIcon(AppPrefs.toolbar_small_icon.get())
    }

    fun setSense(sense: Int) {
        mController.setSense(sense)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastRawX = event.rawX
        lastRawY = event.rawY
        mController.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            swipeAction?.let { onSwipeActionListener?.onSwipeAction(it, lastRawX, lastRawY) }
        }
        return super.onTouchEvent(event)
    }

    private fun clearSwipeAction() {
        if (swipeAction != null) {
            swipeAction = null
            onSwipeActionListener?.onSwipeAction(null, lastRawX, lastRawY)
        }
    }

    override fun onLongPress() {}

    override fun onEventOutSide(): Boolean {
        clearSwipeAction()
        setImageDrawable(mController.defaultIcon)
        overlay?.setVisible(true, false)
        setBackgroundResource(R.drawable.swipebtn_image_background_normal)
        return false
    }

    override fun onEventCancel(): Boolean {
        clearSwipeAction()
        setImageDrawable(mController.defaultIcon)
        overlay?.setVisible(true, false)
        setBackgroundResource(R.drawable.swipebtn_image_background_normal)
        return false
    }

    override fun onEventActionUp(whatNo: Int): Boolean {
        clearSwipeAction()
        setImageDrawable(mController.defaultIcon)
        overlay?.setVisible(true, false)
        setBackgroundResource(R.drawable.swipebtn_image_background_normal)
        return false
    }

    override fun onEventActionDown(): Boolean {
        val themeData = ThemeData.getInstance()
        if (themeData?.toolbarButtonBackgroundPress != null) {
            background = themeData.toolbarButtonBackgroundPress
        } else {
            setBackgroundResource(R.drawable.swipebtn_image_background_pressed)
        }
        return false
    }

    override fun onChangeState(whatNo: Int) {
        setImageDrawable(mController.icon)
        overlay?.setVisible(whatNo == SwipeController.SWIPE_PRESS || whatNo == SwipeController.SWIPE_CANCEL, false)

        val action = if (whatNo and SwipeController.SWIPE_X_Y != 0) {
            mController.getAction(whatNo)?.takeUnless { it.isEmpty() }
        } else {
            null
        }
        if (action == null) {
            clearSwipeAction()
        } else {
            swipeAction = action
            onSwipeActionListener?.onSwipeAction(action, lastRawX, lastRawY)
        }
    }

    private fun setOverlayIcon(show: Boolean) {
        val icon = if (show) mController.getIcon(SwipeController.SWIPE_LPRESS) else null

        overlay = if (icon != null) SmallButtonDrawable(icon, smallIconSize) else null
    }
}
