/*
 * Copyright 2021 Hazuki
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

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.random.Random

internal class WebViewCallback(
    private val listener: OnWebViewListener
) {
    private val secretKey = Random.nextLong().toString(32)

    /**
     * DOMContentLoaded の通知を受ける窓口を登録する。JavaScript インターフェース（addJavascriptInterface）は使わず、
     * 文字列だけを受け取る WebMessageListener を使う。通知は主フレームからで、合言葉が一致したものだけを受け付ける。
     * 対応していない古い WebView では登録せず、onPageFinished 側の処理だけで動く。
     */
    fun install(web: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(web, INTERFACE_KEY, setOf("*")) { _, message, _, isMainFrame, _ ->
            // 呼び出しは UI スレッドで行われる。
            if (isMainFrame && message.data == secretKey) listener.onDomContentLoaded()
        }
    }

    fun createInjectScript(): String {
        return "(function() {var port = window.$INTERFACE_KEY; if (!port) return;" +
            "document.addEventListener('DOMContentLoaded'," +
            "function() {port.postMessage(\"$secretKey\")})}())"
    }

    companion object {
        const val INTERFACE_KEY = "_momo_browser_interface"
    }

    interface OnWebViewListener {
        fun onDomContentLoaded()
    }
}
