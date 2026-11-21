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
package io.github.ajichama.citrusbrowser.core.utility.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class UserAgentTest {
    private val webView = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/145.0.0.0 Mobile Safari/537.36"

    @Test fun desktopUserAgentHasNoMobileOrWebViewMarkers() {
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
            webView.convertToPc(),
        )
        assertEquals(webView.convertToPc(), webView.toFakeChrome().convertToPc())
    }

    @Test fun fakeChromeRemovesTheWebViewMarkersOnly() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36",
            webView.toFakeChrome(),
        )
    }
}
