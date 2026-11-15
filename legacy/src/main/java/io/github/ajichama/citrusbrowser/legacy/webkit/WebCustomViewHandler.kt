/*
 * Copyright (C) 2017 Hazuki
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

package io.github.ajichama.citrusbrowser.legacy.webkit

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class WebCustomViewHandler(private val fullscreenLayout: ViewGroup) {
    private var customView: View? = null
    private lateinit var customViewCallback: WebChromeClient.CustomViewCallback
    private var oldOrientation: Int = 0
    private var oldKeepScreenOn = false
    private var oldVisibleSystemBars = 0
    private var oldSystemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT

    val isCustomViewShowing: Boolean
        get() = customView != null

    @Suppress("DEPRECATION")
    fun showCustomView(activity: Activity, view: View, orientation: Int, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }

        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        val oldVisibility = window.decorView.systemUiVisibility
        oldKeepScreenOn = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        oldVisibleSystemBars = 0
        if (insets?.isVisible(WindowInsetsCompat.Type.statusBars())
            ?: (window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN == 0 &&
                oldVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)) {
            oldVisibleSystemBars = oldVisibleSystemBars or WindowInsetsCompat.Type.statusBars()
        }
        if (insets?.isVisible(WindowInsetsCompat.Type.navigationBars())
            ?: (oldVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0)) {
            oldVisibleSystemBars = oldVisibleSystemBars or WindowInsetsCompat.Type.navigationBars()
        }
        if (insets?.isVisible(WindowInsetsCompat.Type.captionBar()) == true) {
            oldVisibleSystemBars = oldVisibleSystemBars or WindowInsetsCompat.Type.captionBar()
        }
        oldSystemBarsBehavior = controller.systemBarsBehavior
        oldOrientation = activity.requestedOrientation
        activity.requestedOrientation = orientation

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        fullscreenLayout.setBackgroundColor(Color.BLACK)
        fullscreenLayout.visibility = View.VISIBLE
        fullscreenLayout.addView(view, FULLSCREEN_LP)
        fullscreenLayout.bringToFront()

        customView = view
        customViewCallback = callback
    }

    @SuppressLint("WrongConstant")
    fun hideCustomView(activity: Activity) {
        if (customView == null) return

        activity.requestedOrientation = oldOrientation

        val window = activity.window
        // 動画表示前の常時点灯と、各システムバーの表示状態を個別に復元する。
        if (oldKeepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = oldSystemBarsBehavior
            val hiddenBars = WindowInsetsCompat.Type.systemBars() and oldVisibleSystemBars.inv()
            if (hiddenBars != 0) hide(hiddenBars)
            if (oldVisibleSystemBars != 0) show(oldVisibleSystemBars)
        }

        fullscreenLayout.visibility = View.GONE
        fullscreenLayout.removeView(customView)

        customView = null
        customViewCallback.onCustomViewHidden()
    }

    companion object {
        private val FULLSCREEN_LP = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
}
