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

import android.content.SharedPreferences
import io.github.ajichama.citrusbrowser.core.utility.utils.BrowserSchemeCompat
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import io.github.ajichama.citrusbrowser.ui.settings.container.Containable
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory

internal object ImportPreferences {
    fun parse(bytes: ByteArray): Map<String, Any> {
        if (bytes.size > 4 * 1024 * 1024) throw IOException("設定 XML が上限サイズを超えています。")
        val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        // 外部文書・エンティティを解決せず、Android の map 形式だけを受け入れる。
        if (xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) throw IOException("DTD は取り込めません。")
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false; isValidating = false }.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("外部エンティティは禁止です。") }
        val root = builder.parse(InputSource(StringReader(xml))).documentElement
        if (root.tagName != "map") throw IOException("設定の map がありません。")
        val values = linkedMapOf<String, Any>()
        for (index in 0 until root.childNodes.length) {
            val node = root.childNodes.item(index)
            if (node !is Element) {
                if (node.nodeType == org.w3c.dom.Node.TEXT_NODE && node.textContent.isNotBlank()) throw IOException("設定 XML の内容が不正です。")
                continue
            }
            val name = node.getAttribute("name")
            if (name.isEmpty() || name.length > 256 || values.containsKey(name) || values.size >= 4096) throw IOException("設定キーが不正または重複しています。")
            val value = node.getAttribute("value")
            values[name] = when (node.tagName) {
                "string" -> {
                    if ((0 until node.childNodes.length).any { node.childNodes.item(it) is Element }) throw IOException("文字列設定が不正です。")
                    node.textContent
                }
                "int" -> value.toInt()
                "long" -> value.toLong()
                "float" -> value.toFloat().also { if (!it.isFinite()) throw IOException("数値設定が不正です。") }
                "boolean" -> when (value) { "true" -> true; "false" -> false; else -> throw IOException("真偽値設定が不正です。") }
                "set" -> {
                    val strings = linkedSetOf<String>()
                    for (child in 0 until node.childNodes.length) {
                        val item = node.childNodes.item(child)
                        if (item is Element) {
                            if (item.tagName != "string") throw IOException("文字列集合が不正です。")
                            strings += item.textContent
                        }
                    }
                    strings
                }
                else -> throw IOException("未対応の設定型です。")
            }
        }
        return values
    }

    data class Known(val values: Map<String, Any>, val ignored: Int)

    fun known(values: Map<String, Any>): Known {
        // 既存 Container 自身にメモリ上の Editor へ書かせ、キーと型を列挙する。
        // 実 SharedPreferences には書き込まず、R8 後のフィールド名にも依存しない。
        val schema = CaptureEditor()
        AppPrefs::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers) }.forEach { field ->
            field.isAccessible = true
            (field.get(null) as? Containable)?.write(schema)
        }
        val accepted = linkedMapOf<String, Any>()
        values.forEach { (key, value) ->
            val current = schema.values[key]
            if (!key.startsWith('_') && key != "download_folder" &&
                (key != "theme_setting" || value in setOf("", "theme://internal/light")) &&
                current != null && current.javaClass == value.javaClass &&
                !(value is String && value.startsWith("content://"))) {
                accepted[key] = if (value is String) BrowserSchemeCompat.normalizeUrl(value) else value
            }
        }
        return Known(accepted, values.size - accepted.size)
    }

    fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
            else -> throw IOException("設定型を保存できません。")
        }
    }

    private class CaptureEditor : SharedPreferences.Editor {
        val values = linkedMapOf<String, Any>()
        override fun putString(key: String, value: String?) = apply { if (value != null) values[key] = value }
        override fun putStringSet(key: String, value: Set<String>?) = apply { if (value != null) values[key] = value }
        override fun putInt(key: String, value: Int) = apply { values[key] = value }
        override fun putLong(key: String, value: Long) = apply { values[key] = value }
        override fun putFloat(key: String, value: Float) = apply { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
        override fun remove(key: String) = apply { values.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun commit() = true
        override fun apply() = Unit
    }
}
