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

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.github.ajichama.citrusbrowser.legacy.useragent.UserAgent
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ImportUserSettingsTest {
    private val ua = ImportUserSettings.USER_AGENTS
    private val patterns = ImportUserSettings.URL_PATTERNS

    @Test fun uaUsesTheUpstreamStringSchemaAndKeepsExistingOrderLabelsAndValues() {
        val existing = """[{"1":"既存PC","0":"@pc"},{"1":"独自","0":"My-UA/1.0"}]""".bytes()
        val imported = prepare(ua, """[{"1":"旧PC","0":"@pc"},{"1":"モバイル","0":"Mobile-UA/2.0"},{"1":"重複","0":"Mobile-UA/2.0"}]""")
        val before = existing.copyOf()
        val merged = ImportUserSettings.merge(listOf(imported), mapOf(ua to existing))
        val adapter = Moshi.Builder().build().adapter<List<UserAgent>>(Types.newParameterizedType(List::class.java, UserAgent::class.java))
        assertEquals(listOf(UserAgent("既存PC", "@pc"), UserAgent("独自", "My-UA/1.0"), UserAgent("モバイル", "Mobile-UA/2.0")),
            adapter.fromJson(merged.files.getValue(ua).toString(Charsets.UTF_8)))
        assertEquals(1, merged.added)
        assertEquals(2, merged.duplicates)
        assertArrayEquals(before, existing)
    }

    @Test fun uaValuesAreStringsAndNeverPositionalIds() {
        val imported = prepare(ua, """[{"1":"既定","0":""},{"1":"独自","0":"custom-agent"}]""")
        val merged = ImportUserSettings.merge(listOf(imported), emptyMap())
        assertEquals(listOf("", "custom-agent"), ImportUserSettings.validate(ua, merged.files.getValue(ua)).agents.map { it.value })
        assertEquals(setOf(ua), merged.files.keys)
    }

    @Test fun malformedUtf8DuplicateKeysWrongTypesAndHeaderNewlinesAreRejectedBeforeMerge() {
        for (json in listOf("""[{"0":"ok","0":"duplicate","1":"name"}]""",
            """[{"0":2,"1":"name"}]""", """[{"0":"UA\r\nHeader: value","1":"name"}]""",
            """[{"0":"UA","1":null}]""", """[{"0":"UA","1":"name"}] []""")) {
            assertThrows(IOException::class.java) { prepare(ua, json) }
        }
        assertThrows(IOException::class.java) { ImportUserSettings.validate(ua, byteArrayOf(0x5b, 0xc3.toByte(), 0x28, 0x5d)) }
    }

    @Test fun malformedPatternRegexUnknownModesAndMissingIntentComponentsAreRejected() {
        for (json in listOf("""[[4,0,{"0":"[(]","1":true}]]""",
            """[[3,{"1":5},{"0":"example.test/*","1":true}]]""",
            """[[3,{"6":99},{"0":"example.test/*","1":true}]]""",
            """[[1,{"0":0,"1":"intent:#Intent;action=android.intent.action.VIEW;end"},{"0":"example.test/*","1":true}]]""")) {
            assertThrows(IOException::class.java) { prepare(patterns, json) }
        }
    }

    @Test fun existingPatternOrderWinsForBothFirstMatchActionsAndLastMatchWebSettings() {
        val existing = """[[4,0,{"0":"keep.test/*","1":true}],[3,{"1":2,"7":1},{"0":"same.test/*","1":true}]]""".bytes()
        val imported = prepare(patterns, """[[4,0,{"0":"new.test/*","1":true}],
            [3,{"0":"My-UA","1":1,"7":3},{"0":"*.test/*","1":true}],
            [3,{"1":1},{"0":"same.test/*","1":true}],
            [4,0,{"0":"keep.test/*","1":false}]]""")
        val merged = ImportUserSettings.merge(listOf(imported), mapOf(patterns to existing))
        val actual = ImportUserSettings.validate(patterns, merged.files.getValue(patterns)).patterns
        assertEquals(listOf("*.test/*", "keep.test/*", "same.test/*", "new.test/*"), actual.map { it.pattern })
        assertEquals(listOf(3, 4, 3, 4), actual.map { it.type })
        assertTrue(actual[1].enabled)
        assertEquals("My-UA", (actual[0].data as Map<*, *>)["0"])
        assertEquals(2, (actual[0].data as Map<*, *>)["7"])
        assertEquals(2, (actual[2].data as Map<*, *>)["1"])
        assertEquals(2, merged.added)
        assertEquals(2, merged.duplicates)
    }

    @Test fun multipleImportedWebSettingsKeepTheirOwnRelativePriorityAndExactDuplicatesAreSkipped() {
        val imported = prepare(patterns, """[[3,{"1":1},{"0":"same.test/*","1":true}],
            [3,{"1":2},{"0":"same.test/*","1":true}],[3,{"1":2},{"0":"same.test/*","1":true}]]""")
        val merged = ImportUserSettings.merge(listOf(imported), emptyMap())
        val actual = ImportUserSettings.validate(patterns, merged.files.getValue(patterns)).patterns
        assertEquals(listOf(1, 2), actual.map { (it.data as Map<*, *>)["1"] })
        assertEquals(1, merged.duplicates)
    }

    @Test fun emptyPatternsFromARealYuzuExportAreExcludedInsteadOfRejectingTheFile() {
        // Yuzu の実際の書き出しにあった形（空のパターンが2件）。
        val imported = prepare(patterns, """[[4,0,{"0":"safe.test/*","1":false}],[1,{"0":1},{"0":"","1":true}],[1,{"0":1},{"0":" ","1":true}]]""")
        assertEquals(1, imported.count)
        assertEquals(2, imported.ignored)
    }
    @Test fun futureImportedActionsAreCountedButUnknownExistingDataIsNeverDropped() {
        val json = """[[99,null,{"0":"future.test/*","1":true}],[4,0,{"0":"safe.test/*","1":true}]]"""
        val imported = prepare(patterns, json)
        assertEquals(1, imported.count)
        assertEquals(1, imported.ignored)
        assertEquals(1, ImportUserSettings.merge(listOf(imported), emptyMap()).ignored)
        assertThrows(IOException::class.java) { ImportUserSettings.merge(listOf(imported), mapOf(patterns to json.bytes())) }
    }

    @Test fun oldInternalIntentMigratesAndActualIdReferencesCanBeRemappedOrSkipped() {
        // 原アプリ由来の保存済み Intent を移行する回帰に限って旧 namespace を保持する。
        val old = "intent:#Intent;component=jp.hazuki.yuzubrowser/jp.hazuki.yuzubrowser.bookmark.view.BookmarkActivity;l.id=7;S.note=%E4%BF%9D%E6%8C%81;end"
        val imported = prepare(patterns, """[[1,{"0":0,"1":"$old"},{"0":"example.test/*","1":true}]]""")
        var callbackValue = ""
        val merged = ImportUserSettings.merge(listOf(imported), emptyMap()) { value ->
            callbackValue = value
            value.replace("l.id=7;", "l.id=77;")
        }
        assertTrue(callbackValue.contains("io.github.ajichama.citrusbrowser"))
        val actual = ImportUserSettings.validate(patterns, merged.files.getValue(patterns)).patterns.single().data as Map<*, *>
        assertTrue((actual["1"] as String).contains("l.id=77;"))
        assertTrue((actual["1"] as String).contains("S.note=%E4%BF%9D%E6%8C%81;"))
        val skipped = ImportUserSettings.merge(listOf(imported), emptyMap()) { null }
        assertEquals(0, skipped.added)
        assertEquals(1, skipped.ignored)
    }

    @Test fun externalComponentsAndEncodedIntentValuesAreNotNamespaceRewritten() {
        val intent = "intent:#Intent;component=com.external/.Reader;S.note=a%3Bb%2Bc;end"
        val imported = prepare(patterns, """[[1,{"0":0,"1":"$intent"},{"0":"example.test/*","1":true}]]""")
        val merged = ImportUserSettings.merge(listOf(imported), emptyMap())
        val actual = ImportUserSettings.validate(patterns, merged.files.getValue(patterns)).patterns.single().data as Map<*, *>
        assertEquals(intent, actual["1"])
    }

    @Test fun malformedIntentTypedExtrasAndPrematureTerminatorsAreRejected() {
        for (intent in listOf("intent:#Intent;component=com.external/.Reader;i.id=not-a-number;end",
            "intent:#Intent;end;component=com.external/.Reader;end")) {
            assertThrows(IOException::class.java) {
                prepare(patterns, """[[1,{"0":0,"1":"$intent"},{"0":"example.test/*","1":true}]]""")
            }
        }
    }

    @Test fun itemAndFileBoundsAreCheckedBeforePublication() {
        val many = (0..2048).joinToString(",", "[", "]") { """{"1":"$it","0":"UA-$it"}""" }
        assertThrows(IOException::class.java) { prepare(ua, many) }
        assertThrows(IOException::class.java) { ImportUserSettings.validate(ua, ByteArray(2 * 1024 * 1024 + 1)) }
        val half = (0 until 1100).joinToString(",", "[", "]") { """{"1":"$it","0":"UA-$it"}""" }
        val another = (1100 until 2200).joinToString(",", "[", "]") { """{"1":"$it","0":"UA-$it"}""" }
        assertThrows(IOException::class.java) { ImportUserSettings.merge(listOf(prepare(ua, another)), mapOf(ua to half.bytes())) }
    }

    private fun prepare(path: String, json: String) = ImportUserSettings.validate(path, json.bytes())
    private fun String.bytes() = toByteArray(Charsets.UTF_8)
}
