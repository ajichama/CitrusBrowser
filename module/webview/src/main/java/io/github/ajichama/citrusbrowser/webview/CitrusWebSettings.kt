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

package io.github.ajichama.citrusbrowser.webview

import android.os.Build
import android.webkit.WebSettings
import androidx.annotation.RequiresApi
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.github.ajichama.citrusbrowser.core.utility.utils.PageColorMode
import io.github.ajichama.citrusbrowser.webview.utility.trimForHttpHeader

class CitrusWebSettings(private val origin: WebSettings, private val onPageColorChanged: ((Int) -> Unit)? = null) {

    var appCacheEnabled = false
        set(flag) {
            // HTML5 AppCache は Android API 33 以降で廃止・削除されたため処理を行わない
            field = flag
        }
    var geolocationEnabled = false
        set(flag) {
            origin.setGeolocationEnabled(flag)
            field = flag
        }

    var mediaPlaybackRequiresUserGesture: Boolean
        get() = origin.mediaPlaybackRequiresUserGesture
        set(require) {
            origin.mediaPlaybackRequiresUserGesture = require
        }

    var allowFileAccess: Boolean
        get() = origin.allowFileAccess
        set(allow) {
            origin.allowFileAccess = allow
        }

    var allowContentAccess: Boolean
        get() = origin.allowContentAccess
        set(allow) {
            origin.allowContentAccess = allow
        }

    var loadWithOverviewMode: Boolean
        get() = origin.loadWithOverviewMode
        set(overview) {
            origin.loadWithOverviewMode = overview
        }

    var saveFormData: Boolean
        @Deprecated("")
        get() = origin.saveFormData
        @Deprecated("")
        set(save) {
            origin.saveFormData = save
        }

    var savePassword: Boolean
        @Suppress("DEPRECATION")
        @Deprecated("")
        get() = origin.savePassword
        @Suppress("DEPRECATION")
        @Deprecated("")
        set(save) {
            origin.savePassword = save
        }

    var textZoom: Int
        get() = origin.textZoom
        set(textZoom) {
            origin.textZoom = textZoom
        }

    var lightTouchEnabled: Boolean
        @Suppress("DEPRECATION")
        @Deprecated("")
        get() = origin.lightTouchEnabled
        @Suppress("DEPRECATION")
        @Deprecated("")
        set(enabled) {
            origin.lightTouchEnabled = enabled
        }

    var useWideViewPort: Boolean
        get() = origin.useWideViewPort
        set(use) {
            origin.useWideViewPort = use
        }

    var layoutAlgorithm: WebSettings.LayoutAlgorithm
        get() = origin.layoutAlgorithm
        set(l) {
            origin.layoutAlgorithm = l
        }

    var standardFontFamily: String
        get() = origin.standardFontFamily
        set(font) {
            origin.standardFontFamily = font
        }

    var fixedFontFamily: String
        get() = origin.fixedFontFamily
        set(font) {
            origin.fixedFontFamily = font
        }

    var sansSerifFontFamily: String
        get() = origin.sansSerifFontFamily
        set(font) {
            origin.sansSerifFontFamily = font
        }

    var serifFontFamily: String
        get() = origin.serifFontFamily
        set(font) {
            origin.serifFontFamily = font
        }

    var cursiveFontFamily: String
        get() = origin.cursiveFontFamily
        set(font) {
            origin.cursiveFontFamily = font
        }

    var fantasyFontFamily: String
        get() = origin.fantasyFontFamily
        set(font) {
            origin.fantasyFontFamily = font
        }

    var minimumFontSize: Int
        get() = origin.minimumFontSize
        set(size) {
            origin.minimumFontSize = size
        }

    var minimumLogicalFontSize: Int
        get() = origin.minimumLogicalFontSize
        set(size) {
            origin.minimumLogicalFontSize = size
        }

    var defaultFontSize: Int
        get() = origin.defaultFontSize
        set(size) {
            origin.defaultFontSize = size
        }

    var defaultFixedFontSize: Int
        get() = origin.defaultFixedFontSize
        set(size) {
            origin.defaultFixedFontSize = size
        }

    var loadsImagesAutomatically: Boolean
        get() = origin.loadsImagesAutomatically
        set(flag) {
            origin.loadsImagesAutomatically = flag
        }

    var blockNetworkImage: Boolean
        get() = origin.blockNetworkImage
        set(flag) {
            origin.blockNetworkImage = flag
        }

    var blockNetworkLoads: Boolean
        get() = origin.blockNetworkLoads
        set(flag) {
            origin.blockNetworkLoads = flag
        }

    var domStorageEnabled: Boolean
        get() = origin.domStorageEnabled
        set(flag) {
            origin.domStorageEnabled = flag
        }

    var databasePath: String
        @Suppress("DEPRECATION")
        @Deprecated("")
        get() = origin.databasePath
        @Suppress("DEPRECATION")
        @Deprecated("")
        set(databasePath) {
            origin.databasePath = databasePath
        }

    var databaseEnabled: Boolean
        get() = origin.databaseEnabled
        set(flag) {
            origin.databaseEnabled = flag
        }

    var javaScriptEnabled: Boolean
        get() = origin.javaScriptEnabled
        set(flag) {
            origin.javaScriptEnabled = flag
        }

    var allowUniversalAccessFromFileURLs: Boolean
        get() = origin.allowUniversalAccessFromFileURLs
        set(flag) {
            origin.allowUniversalAccessFromFileURLs = flag
        }

    var allowFileAccessFromFileURLs: Boolean
        get() = origin.allowFileAccessFromFileURLs
        set(flag) {
            origin.allowFileAccessFromFileURLs = flag
        }

    var javaScriptCanOpenWindowsAutomatically: Boolean
        get() = origin.javaScriptCanOpenWindowsAutomatically
        set(flag) {
            origin.javaScriptCanOpenWindowsAutomatically = flag
        }

    var defaultTextEncodingName: String
        get() = origin.defaultTextEncodingName
        set(encoding) {
            origin.defaultTextEncodingName = encoding
        }

    var userAgentString: String?
        get() = origin.userAgentString
        set(ua) {
            origin.userAgentString = trimForHttpHeader(ua)
        }

    var cacheMode: Int
        get() = origin.cacheMode
        set(mode) {
            origin.cacheMode = mode
        }

    var mixedContentMode: Int
        get() = origin.mixedContentMode
        set(mode) {
            origin.mixedContentMode = mode
        }

    var offscreenPreRaster: Boolean
        get() = origin.offscreenPreRaster
        set(enabled) {
            origin.offscreenPreRaster = enabled
        }

    var safeBrowsingEnabled: Boolean
        @RequiresApi(api = Build.VERSION_CODES.O)
        get() = origin.safeBrowsingEnabled
        @RequiresApi(api = Build.VERSION_CODES.O)
        set(enabled) {
            origin.safeBrowsingEnabled = enabled
        }

    var disabledActionModeMenuItems: Int
        @RequiresApi(api = Build.VERSION_CODES.N)
        get() = origin.disabledActionModeMenuItems
        @RequiresApi(api = Build.VERSION_CODES.N)
        set(menuItems) {
            origin.disabledActionModeMenuItems = menuItems
        }

    var displayZoomButtons: Boolean
        get() = origin.displayZoomControls
        set(show) {
            origin.setSupportZoom(true)
            origin.builtInZoomControls = true
            origin.displayZoomControls = show
        }

    var webTheme: Int = 0
        set(value) {
            field = PageColorMode.normalize(value)

            setWebTheme()
        }

    fun setSupportZoom(support: Boolean) {
        origin.setSupportZoom(support)
    }

    fun supportZoom(): Boolean {
        return origin.supportZoom()
    }

    @Suppress("DEPRECATION")
    @Deprecated("")
    fun setEnableSmoothTransition(enable: Boolean) {
        origin.setEnableSmoothTransition(enable)
    }

    fun setSupportMultipleWindows(support: Boolean) {
        origin.setSupportMultipleWindows(support)
    }

    fun supportMultipleWindows(): Boolean {
        return origin.supportMultipleWindows()
    }

    @Suppress("DEPRECATION")
    @Deprecated("")
    fun setGeolocationDatabasePath(databasePath: String) {
        origin.setGeolocationDatabasePath(databasePath)
    }

    fun setAppCachePath(appCachePath: String) {
        // HTML5 AppCache は Android API 33 以降で廃止・削除されたため互換用の空実装とする
    }

    @Suppress("DEPRECATION")
    @Deprecated("")
    fun setAppCacheMaxSize(appCacheMaxSize: Long) {
        // HTML5 AppCache は Android API 33 以降で廃止・削除されたため互換用の空実装とする
    }

    fun setNeedInitialFocus(flag: Boolean) {
        origin.setNeedInitialFocus(flag)
    }

    private fun setWebTheme() {
        // 現行の targetSdk では旧 Force Dark API が無効なため、端末 OS ではなく WebView の対応状況で判定する。
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(origin, PageColorMode.allowsAlgorithmicDarkening(webTheme))
        }
        // isLightThemeの更新はalgorithm未対応のWebViewでもサイト自身の配色指定に必要。
        onPageColorChanged?.invoke(webTheme)
    }

    companion object {
        const val WEB_THEME_LIGHT = PageColorMode.LIGHT
        const val WEB_THEME_DARK = PageColorMode.SITE_DARK
        const val WEB_THEME_PREFER_WEB_OVER_FORCE_DARK = PageColorMode.AUTO_DARK
        // 旧保存値の読込互換用。現行APIではサイト配色を無視する強制暗色化を再現できない。
        const val WEB_THEME_FORCE_DARK = 3
    }
}
