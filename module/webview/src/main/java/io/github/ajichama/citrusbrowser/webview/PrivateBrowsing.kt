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
package io.github.ajichama.citrusbrowser.webview

import android.content.Context
import android.content.ContextWrapper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * プライベートタブ（Issue #15）。通常のタブと並べて使え、Cookie・サイトのデータ・キャッシュを通常のタブと分ける。
 *
 * プライベートタブの WebView は [PrivateContext] から作る。WebView の土台（JvmWebViewBridge）が生成の直後に
 * [applyProfile] で専用のプロファイルへ切り替える。戻る・進む用に中で作られる WebView にも同じ Context が渡るので、漏れなく切り替わる。
 * プロファイルのデータは、最後のプライベートタブを閉じたときと、アプリの起動時に消す。
 * 端末の WebView がプロファイルに対応していない（WebView 119 未満）ときは、プライベートタブを使えない。
 */
object PrivateBrowsing {
    const val PROFILE = "citrus_private"

    /** プライベートタブの WebView を作るための Context。 */
    class PrivateContext(base: Context) : ContextWrapper(base)

    @JvmStatic
    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    @JvmStatic
    fun isPrivate(context: Context?): Boolean {
        var current = context
        while (current != null) {
            if (current is PrivateContext) return true
            current = (current as? ContextWrapper)?.baseContext
        }
        return false
    }

    /** WebView の生成の直後（何かを読み込む前）に呼ぶ。プライベートなら専用のプロファイルへ切り替え、true を返す。 */
    @JvmStatic
    fun applyProfile(web: WebView, context: Context?): Boolean {
        if (!isPrivate(context) || !isSupported()) return false
        ProfileStore.getInstance().getOrCreateProfile(PROFILE)
        WebViewCompat.setProfile(web, PROFILE)
        return true
    }

    /** プライベートタブの Cookie の管理。対応していなければ null。 */
    @JvmStatic
    fun cookieManager(): CookieManager? =
        if (isSupported()) ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager else null

    /** プライベートタブのデータを消す。使っている WebView が残っているときは消せず false を返す。 */
    @JvmStatic
    fun deleteData(): Boolean {
        if (!isSupported()) return false
        return try {
            ProfileStore.getInstance().deleteProfile(PROFILE)
        } catch (e: IllegalStateException) {
            false
        }
    }
}
