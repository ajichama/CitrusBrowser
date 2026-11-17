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

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class LegacyImportCompatibilityTest {
    @Test fun exactOldReplacementTokensAndIntentValuesAreMigrated() {
        val previous = "http://jp.hazuki.yuzubrowser/REPLACE_URI"
        val current = "http://io.github.ajichama.citrusbrowser/REPLACE_URI"
        assertEquals(current, LegacyImportCompatibility.normalize(previous))
        val intent = "intent:#Intent;S.page=" + URLEncoder.encode(previous, "UTF-8") + ";end"
        assertEquals("intent:#Intent;S.page=" + URLEncoder.encode(current, "UTF-8") + ";end", LegacyImportCompatibility.normalize(intent))
    }
    @Test fun arbitraryTextAndLongerUrlsAreNotRebranded() {
        for (text in listOf("Yuzu Browser user title", "https://jp.hazuki.yuzubrowser.example/path", "http://jp.hazuki.yuzubrowser/REPLACE_URI2", "intent:#Intent;S.value=http://jp.hazuki.yuzubrowser/REPLACE_URI2;end"))
            assertEquals(text, LegacyImportCompatibility.normalize(text))
    }
}