/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.github.ajichama.citrusbrowser.browser

import android.content.Context
import android.webkit.WebStorage
import androidx.annotation.UiThread
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * サイト単位のデータ（Cookie・ストレージ・キャッシュ）の消去と、ページごとの遮断数。
 * サイトは登録可能ドメイン（例: www.example.co.jp → example.co.jp）で扱う。WebView の消去も同じ単位で行われる。
 */
internal object SiteData {
    private const val PREFS = "site_data"
    private const val KEY_CLEAR_ON_CLOSE = "clear_on_close"

    private val blocked = Collections.synchronizedMap(WeakHashMap<Any, AtomicInteger>())

    /** 主フレームの読み込み開始で、そのタブの遮断数を0に戻す。 */
    fun resetBlocked(web: Any) {
        blocked[web] = AtomicInteger()
    }

    fun countBlocked(web: Any) {
        synchronized(blocked) { blocked.getOrPut(web) { AtomicInteger() } }.incrementAndGet()
    }

    fun blockedCount(web: Any): Int = blocked[web]?.get() ?: 0

    /** http(s) の URL の登録可能ドメイン。IP アドレスや localhost など、求められない場合は null。 */
    fun site(url: String?): String? = url?.toHttpUrlOrNull()?.topPrivateDomain()

    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

    /** サイトの Cookie・ストレージ・キャッシュを消す。非対応の WebView では false を返し、何もしない。 */
    @UiThread
    fun clear(site: String, done: () -> Unit): Boolean {
        if (!isSupported()) return false
        return try {
            WebStorageCompat.deleteBrowsingDataForSite(WebStorage.getInstance(), site) { done() }
            true
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: UnsupportedOperationException) {
            false
        }
    }

    fun isClearOnClose(context: Context, site: String): Boolean =
        site in prefs(context).getStringSet(KEY_CLEAR_ON_CLOSE, emptySet()).orEmpty()

    fun setClearOnClose(context: Context, site: String, enabled: Boolean) {
        val current = prefs(context).getStringSet(KEY_CLEAR_ON_CLOSE, emptySet()).orEmpty().toMutableSet()
        if (enabled) current += site else current -= site
        prefs(context).edit().putStringSet(KEY_CLEAR_ON_CLOSE, current).apply()
    }

    /** タブを閉じた後に呼ぶ。自動消去を選んだサイトで、同じサイトのタブがほかに開いていなければ消す。 */
    @UiThread
    fun onTabClosed(context: Context, closedUrl: String?, remainingUrls: List<String?>) {
        val site = site(closedUrl) ?: return
        if (!isClearOnClose(context, site)) return
        if (remainingUrls.any { site(it) == site }) return
        clear(site) {}
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
