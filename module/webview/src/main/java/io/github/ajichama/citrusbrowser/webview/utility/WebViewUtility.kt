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

package io.github.ajichama.citrusbrowser.webview.utility

import android.webkit.WebSettings
import io.github.ajichama.citrusbrowser.core.utility.utils.BrowserSchemeCompat

interface WebViewUtility {

    fun String.shouldLoadSameTabAuto() = regionMatches(0, "about:", 0, 6, true)

    fun String.shouldLoadSameTabScheme() = regionMatches(0, "intent:", 0, 7, true)
            || isSpeedDial()

    fun String.isSpeedDial() = BrowserSchemeCompat.normalizeUrl(this).equals("citrus:speeddial", true)

    fun String.shouldLoadSameTabUser() = regionMatches(0, "javascript:", 0, 11, true)

    fun WebSettings.setDisplayZoomButtons(show: Boolean) {
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = show
    }

    fun WebSettings.copyDisplayZoomButtonsTo(to: WebSettings) {
        to.setSupportZoom(true)
        to.builtInZoomControls = true
        to.displayZoomControls = displayZoomControls
    }
}
