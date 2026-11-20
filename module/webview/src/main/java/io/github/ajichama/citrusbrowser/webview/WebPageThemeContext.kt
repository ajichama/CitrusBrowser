package io.github.ajichama.citrusbrowser.webview

import android.content.Context
import android.view.ContextThemeWrapper
import io.github.ajichama.citrusbrowser.core.utility.utils.PageColorMode

/** WebViewごとにテーマを分離し、基底Activityと他タブのテーマを変更しない。 */
internal class WebPageThemeContext(base: Context) : ContextThemeWrapper(base, R.style.CitrusWebPage_Light) {
    var pageMode: Int = PageColorMode.LIGHT
        private set

    fun updateMode(mode: Int): Boolean {
        val normalized = PageColorMode.normalize(mode)
        if (pageMode == normalized) return false
        pageMode = normalized
        return true
    }

    fun reapplyPageTheme() {
        // ContextThemeWrapperの独立したThemeへコピーし、ActivityへのsetThemeは行わない。
        theme.setTo(baseContext.theme)
        theme.applyStyle(if (PageColorMode.isDark(pageMode)) R.style.CitrusWebPage_Dark else R.style.CitrusWebPage_Light, true)
    }
}
