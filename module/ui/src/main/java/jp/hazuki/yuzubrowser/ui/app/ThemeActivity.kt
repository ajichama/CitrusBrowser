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

package jp.hazuki.yuzubrowser.ui.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import jp.hazuki.yuzubrowser.core.utility.utils.createLanguageConfig
import jp.hazuki.yuzubrowser.ui.settings.AppPrefs
import jp.hazuki.yuzubrowser.ui.theme.ThemeData

@SuppressLint("Registered")
open class ThemeActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val application = newBase.applicationContext
        if (!ThemeData.isLoaded()) {
            ThemeData.createInstance(application, PrefPool.getSharedPref(application).getString(theme_setting, ThemeData.THEME_LIGHT))
        }

        val isLightMode = isLightMode()
        val config = newBase.createLanguageConfig(AppPrefs.language.get())

        applyThemeMode(isLightMode)
        config.updateTheme(isLightMode)

        super.attachBaseContext(ContextCompat(newBase.createConfigurationContext(config), newBase))
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        setupEdgeToEdgeInsets()
    }

    // Android 15〜17 (API 35〜37) の Edge-to-Edge 強制化に伴い、システムバー・ノッチ・IME の余白を適用する
    private fun setupEdgeToEdgeInsets() {
        val rootView = findViewById<View>(androidx.appcompat.R.id.action_bar_root)
            ?: findViewById<View>(android.R.id.content)
            ?: return
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
    }

    // フルスクリーン設定に応じてステータスバーの表示・非表示を制御する
    protected fun setStatusBarHidden(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun applyThemeMode(isLightMode: Boolean) {
        val defaultMode = AppCompatDelegate.getDefaultNightMode()
        if (isLightMode) {
            if (defaultMode != AppCompatDelegate.MODE_NIGHT_NO) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        } else {
            if (defaultMode != AppCompatDelegate.MODE_NIGHT_YES) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }
    }

    private fun isLightMode(): Boolean {
        return ThemeData.getInstance()?.lightTheme ?: false
    }

    private fun Configuration.updateTheme(isLightMode: Boolean) {
        val newNightMode = if (isLightMode) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES

        uiMode = newNightMode or (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv())
    }

    private class ContextCompat(
        configContext: Context,
        private val baseActivityContext: Context
    ) : ContextWrapper(configContext) {

        override fun getSystemService(name: String): Any? {
            return baseActivityContext.getSystemService(name)
        }

        override fun getSystemServiceName(serviceClass: Class<*>): String? {
            return baseActivityContext.getSystemServiceName(serviceClass)
        }
    }

    companion object {
        private const val theme_setting = "theme_setting"
        private const val language = "language"
    }
}
