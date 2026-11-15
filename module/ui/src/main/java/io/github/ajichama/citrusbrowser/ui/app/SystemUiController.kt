/*
 * Copyright (C) 2017-2021 Hazuki
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

package io.github.ajichama.citrusbrowser.ui.app

import android.view.Window
import androidx.annotation.CallSuper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

sealed class SystemUiController(
    protected val window: Window,
) {
    var isLightStatusBar = false
        set(value) {
            val isUpdated = field != value
            field = value

            if (isUpdated) isNeedUpdate = true
        }

    @Suppress("DEPRECATION")
    var statusBarColor = window.statusBarColor
        set(value) {
            val isUpdated = field != value
            field = value

            if (isUpdated) isNeedUpdate = true
        }

    open var isLightNavigationBar = false
        set(value) {
            val isUpdated = field != value
            field = value

            if (isUpdated) isNeedUpdate = true
        }

    @Suppress("DEPRECATION")
    open var navigationBarColor = window.navigationBarColor
        set(value) {
            val isUpdated = field != value
            field = value

            if (isUpdated) isNeedUpdate = true
        }

    var barState = State.NORMAL
        set(value) {
            val isUpdated = field != value
            field = value

            if (isUpdated) isNeedUpdate = true
        }

    protected var isNeedUpdate = false

    @CallSuper
    open fun updateConfigure() {
        isNeedUpdate = false
    }

    fun updateConfigureIfNeed() {
        if (isNeedUpdate) {
            updateConfigure()
        }
    }

    enum class State {
        NORMAL,
        HIDE_STATUS_BAR,
        HIDE_NAVIGATION_BAR,
        HIDE_ALL_BAR,
    }

    companion object {
        fun create(window: Window): SystemUiController = ControllerCompat(window)
    }

    // WindowInsetsControllerCompat により API 23〜37 のシステムバー外観・表示制御を統一
    private class ControllerCompat(window: Window) : SystemUiController(window) {

        override fun updateConfigure() {
            super.updateConfigure()

            window.also {
                @Suppress("DEPRECATION")
                it.statusBarColor = statusBarColor
                @Suppress("DEPRECATION")
                it.navigationBarColor = navigationBarColor
                it.decorView.setBackgroundColor(statusBarColor)

                val hide = when (barState) {
                    State.NORMAL -> 0
                    State.HIDE_STATUS_BAR -> WindowInsetsCompat.Type.statusBars()
                    State.HIDE_NAVIGATION_BAR -> WindowInsetsCompat.Type.navigationBars()
                    State.HIDE_ALL_BAR -> WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.navigationBars()
                }

                val show = when (barState) {
                    State.NORMAL -> WindowInsetsCompat.Type.statusBars() or
                        WindowInsetsCompat.Type.navigationBars()
                    State.HIDE_STATUS_BAR -> WindowInsetsCompat.Type.navigationBars()
                    State.HIDE_NAVIGATION_BAR -> WindowInsetsCompat.Type.statusBars()
                    State.HIDE_ALL_BAR -> 0
                }

                WindowCompat.getInsetsController(it, it.decorView).run {
                    if (hide != 0) {
                        hide(hide)
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                    if (show != 0) {
                        show(show)
                    }
                    isAppearanceLightStatusBars = isLightStatusBar
                    isAppearanceLightNavigationBars = isLightNavigationBar
                }
            }
        }
    }
}
