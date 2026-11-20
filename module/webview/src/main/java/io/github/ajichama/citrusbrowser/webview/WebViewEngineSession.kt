/*
 * Copyright (C) 2026 CitrusBrowser contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.github.ajichama.citrusbrowser.webview

import io.github.ajichama.citrusbrowser.engine.EngineCapability
import io.github.ajichama.citrusbrowser.engine.EngineNavigationState
import io.github.ajichama.citrusbrowser.engine.EngineSession
import java.util.Collections
import java.util.EnumSet

/**
 * 既存タブの WebView を作り直さず、操作だけを共通契約へ橋渡しする。
 * Cache/LimitCache の履歴制御を保つため、内側の android.webkit.WebView へは展開しない。
 */
class WebViewEngineSession(
    private val web: CustomWebView,
    private val isLoading: () -> Boolean
) : EngineSession {
    override val capabilities: Set<EngineCapability> = CAPABILITIES

    override val state: EngineNavigationState
        get() = EngineNavigationState(web.canGoBack(), web.canGoForward(), isLoading())

    override fun goBack() = web.goBack()
    override fun goForward() = web.goForward()
    override fun reload() = web.reload()
    override fun stopLoading() = web.stopLoading()

    private companion object {
        val CAPABILITIES: Set<EngineCapability> = Collections.unmodifiableSet(EnumSet.allOf(EngineCapability::class.java))
    }
}
