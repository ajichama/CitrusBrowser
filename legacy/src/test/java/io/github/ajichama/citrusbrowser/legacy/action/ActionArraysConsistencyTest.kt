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

package io.github.ajichama.citrusbrowser.legacy.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * 操作の一覧を作る3つの配列（番号・名前・アイコン）の整合を確かめる。
 * 3つは同じ位置で対応するので、1つだけ足したり位置がずれたりすると、別の操作の名前やアイコンが出る。
 * 対応表（action-arrays.tsv）と比べて、意図しない変更を見つける。
 */
class ActionArraysConsistencyTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile }
    private val values = items("arrays_action_values.xml").map { it.toInt() }
    private val names = items("arrays_action_list.xml").map { it.removePrefix("@string/") }
    private val icons = items("arrays_action_icon.xml").map { it.removePrefix("@drawable/") }

    @Test
    fun threeArraysHaveTheSameSize() {
        assertEquals("番号と名前の件数", values.size, names.size)
        assertEquals("番号とアイコンの件数", values.size, icons.size)
    }

    @Test
    fun actionIdsAreUniqueAndMatchSingleActionConstants() {
        val duplicated = values.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("番号が重複している: $duplicated", duplicated.isEmpty())

        val constants = actionConstants()
        val unknown = values.filterNot { it in constants.values }
        assertTrue("SingleAction に定数がない番号: $unknown", unknown.isEmpty())
        // 長押しのメニュー用（負の番号）以外の定数は、すべて一覧に出す。
        val missing = constants.filter { it.value > 0 && it.value !in values }.keys
        assertTrue("一覧の配列にない操作: $missing", missing.isEmpty())
    }

    @Test
    fun everyNameHasDefaultAndJapaneseStrings() {
        val defaults = stringNames("values")
        val japanese = stringNames("values-ja")
        assertTrue("既定の文字列がない: ${names - defaults}", (names - defaults).isEmpty())
        assertTrue("日本語の文字列がない: ${names - japanese}", (names - japanese).isEmpty())
    }

    @Test
    fun everyIconExists() {
        val drawables = resDirs().flatMap { res ->
            res.listFiles { f -> f.isDirectory && f.name.startsWith("drawable") }.orEmpty().asList()
        }.flatMap { it.listFiles().orEmpty().asList() }.map { it.name.substringBefore('.') }.toSet()
        val missing = icons.filterNot { it in drawables }
        assertTrue("アイコンがない: $missing", missing.isEmpty())
    }

    @Test
    fun arraysMatchTheRecordedTable() {
        val byValue = actionConstants().entries.associate { it.value to it.key }
        val actual = values.indices.map { i -> listOf(byValue[values[i]], values[i], names[i], icons[i]).joinToString("\t") }
        val expected = File(root, "legacy/src/test/resources/action-arrays.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(
            "操作の配列が対応表と違う。意図した変更なら、3つの配列と同じ位置で action-arrays.tsv も直す",
            expected.joinToString("\n"),
            actual.joinToString("\n"),
        )
    }

    private fun items(file: String): List<String> {
        val text = File(root, "legacy/src/main/res/values/$file").readText()
        return ITEM.findAll(text).map { it.groupValues[1].trim() }.toList()
    }

    private fun stringNames(qualifier: String): Set<String> =
        resDirs().map { File(it, qualifier) }.filter { it.isDirectory }
            .flatMap { it.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().asList() }
            .flatMap { STRING_NAME.findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .toSet()

    /** アプリに入るモジュールの res ディレクトリ（直下・module・libraries の各モジュール）。 */
    private fun resDirs(): List<File> =
        listOf(root, File(root, "module"), File(root, "libraries"))
            .flatMap { it.listFiles { f -> f.isDirectory }.orEmpty().asList() }
            .map { File(it, "src/main/res") }
            .filter { it.isDirectory }

    private fun actionConstants(): Map<String, Int> =
        SingleAction::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }

    private companion object {
        val ITEM = Regex("<item>([^<]+)</item>")
        val STRING_NAME = Regex("<string\\s+name=\"([^\"]+)\"")
    }
}
