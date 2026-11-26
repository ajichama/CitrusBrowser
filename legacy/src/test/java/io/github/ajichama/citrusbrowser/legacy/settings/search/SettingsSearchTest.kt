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

package io.github.ajichama.citrusbrowser.legacy.settings.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private fun entry(title: String, summary: String? = null, path: List<String> = listOf("UI settings")) =
        SettingsSearchEntry(title, summary, path, "fragment_ui", emptyList(), title)

    private val toolbar = entry("Toolbar size", "Height of the toolbar")
    private val font = entry("Font size")
    private val cookie = entry("Accept cookies", path = listOf("Privacy"))
    private val all = listOf(toolbar, font, cookie)

    @Test
    fun 項目名か説明に含むものを大文字小文字を区別せずに返す() {
        assertEquals(listOf(toolbar), SettingsSearch.find(all, "TOOLBAR"))
        assertEquals(listOf(toolbar), SettingsSearch.find(all, "height"))
    }

    @Test
    fun 空白で区切った言葉はすべて含むものだけを返す() {
        assertEquals(listOf(toolbar, font), SettingsSearch.find(all, "size"))
        assertEquals(listOf(font), SettingsSearch.find(all, "font  size"))
        assertEquals(listOf(font), SettingsSearch.find(all, "size　font"))
    }

    @Test
    fun 場所の名前とは比べない() {
        assertTrue(SettingsSearch.find(all, "privacy").isEmpty())
    }

    @Test
    fun 空や空白だけの入力では何も返さない() {
        assertTrue(SettingsSearch.find(all, "").isEmpty())
        assertTrue(SettingsSearch.find(all, " 　 ").isEmpty())
    }

    @Test
    fun 日本語の項目名でも探せる() {
        val ja = entry("ツールバーの大きさ")
        assertEquals(listOf(ja), SettingsSearch.find(listOf(font, ja), "ツールバー"))
    }
}
