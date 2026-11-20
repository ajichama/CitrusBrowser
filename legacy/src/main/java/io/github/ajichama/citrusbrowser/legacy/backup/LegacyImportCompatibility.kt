/*
 * Copyright (C) 2026 CitrusBrowser contributors
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

package io.github.ajichama.citrusbrowser.legacy.backup

import java.net.URLEncoder

/** 原アプリの「ページ URL/タイトルで置換」専用トークンだけを移行する。任意 URL は変えない。 */
internal object LegacyImportCompatibility {
    fun normalize(value: String): String {
        var result = value
        for (suffix in listOf("REPLACE_URI", "REPLACE_TITLE")) {
            val previous = "http://jp.hazuki.yuzubrowser/$suffix"
            val current = "http://io.github.ajichama.citrusbrowser/$suffix"
            if (result == previous) return current
            if (result.contains("#Intent;")) {
                result = result.replace(Regex(Regex.escape(previous) + "(?=;|#|&|$)"), current)
                    .replace(Regex(Regex.escape(URLEncoder.encode(previous, "UTF-8")) + "(?=;|%3[Bb]|%26|%23|$)"), URLEncoder.encode(current, "UTF-8"))
            }
        }
        return result
    }
}
