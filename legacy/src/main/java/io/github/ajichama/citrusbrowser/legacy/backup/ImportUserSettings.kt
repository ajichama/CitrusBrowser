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

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.github.ajichama.citrusbrowser.adblock.filter.fastmatch.FastMatcherFactory
import io.github.ajichama.citrusbrowser.core.utility.utils.BrowserSchemeCompat
import io.github.ajichama.citrusbrowser.core.utility.utils.PageColorMode
import io.github.ajichama.citrusbrowser.core.utility.utils.migrateLegacyBrowserIntentUri
import io.github.ajichama.citrusbrowser.legacy.utils.WebUtils
import okio.Buffer
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** UA と URL 別設定を検証し、実ファイルへ触れず既存優先の追加内容だけを作る。 */
internal object ImportUserSettings {
    const val USER_AGENTS = "files/ualist_1.dat"
    const val URL_PATTERNS = "app_pattern1/url_1.dat"
    val paths = setOf(USER_AGENTS, URL_PATTERNS)
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_ITEMS = 2048

    internal data class Agent(val name: String, val value: String)
    internal data class UrlPattern(val type: Int, val data: Any?, val pattern: String, val enabled: Boolean) {
        val key: Pair<Int, String> get() = type to pattern
        fun json(): List<Any?> = listOf(type, data, linkedMapOf("0" to pattern, "1" to enabled))
    }

    data class Prepared internal constructor(
        val path: String,
        internal val agents: List<Agent> = emptyList(),
        internal val patterns: List<UrlPattern> = emptyList(),
        val ignored: Int = 0,
    ) {
        val count: Int get() = agents.size + patterns.size
    }

    data class Merged(val files: Map<String, ByteArray>, val added: Int, val duplicates: Int, val ignored: Int)

    fun validate(path: String, bytes: ByteArray): Prepared {
        if (path !in paths) throw IOException("未対応のユーザー設定ファイルです。")
        val entries = readJson(bytes) as? List<*> ?: throw IOException("ユーザー設定の一覧がありません。")
        if (entries.size > MAX_ITEMS) throw IOException("ユーザー設定の件数が上限を超えています。")
        if (path == USER_AGENTS) {
            return Prepared(path, agents = entries.map { value ->
                val entry = objectValue(value)
                if (entry.keys != setOf("0", "1")) throw IOException("UA の項目が不正です。")
                Agent(text(entry["1"], 256), text(entry["0"], 8192))
            })
        }
        val patterns = arrayListOf<UrlPattern>()
        var ignored = 0
        val factory = FastMatcherFactory()
        try {
            entries.forEach { value ->
                val entry = value as? List<*> ?: throw IOException("URL 別設定の構造が不正です。")
                if (entry.size != 3) throw IOException("URL 別設定の必須項目がありません。")
                val type = integer(entry[0])
                if (type !in setOf(1, 3, 4)) { ignored++; return@forEach }
                val attributes = objectValue(entry[2])
                if (attributes.keys.any { it !in setOf("0", "1") }) { ignored++; return@forEach }
                val rawPattern = text(attributes["0"], 4096)
                // Yuzu は空のパターンも保存できる（実際の書き出しにあった）。当たる範囲が定まらないため、その項目だけを除外する。
                if (rawPattern.isBlank()) { ignored++; return@forEach }
                val pattern = BrowserSchemeCompat.normalizeUrl(rawPattern)
                // 既存実装と同じ glob / [Java regexp] を構文確認する。URL との照合は実行しない。
                try { WebUtils.makeUrlPatternWithThrow(factory, pattern) }
                catch (error: IllegalArgumentException) { throw IOException("URL パターンが不正です。", error) }
                val enabled = if (attributes.containsKey("1")) attributes["1"] as? Boolean
                    ?: throw IOException("URL 別設定の有効状態が不正です。") else true
                val data = when (type) {
                    1 -> openAction(entry[1])
                    3 -> webAction(entry[1])
                    else -> { integer(entry[1]); 0 }
                }
                if (data === Unsupported) { ignored++; return@forEach }
                patterns += UrlPattern(type, data, pattern, enabled)
            }
        } finally { factory.release() }
        return Prepared(path, patterns = patterns, ignored = ignored)
    }

    fun merge(prepared: Collection<Prepared>, existing: Map<String, ByteArray>,
              remapIntent: (String) -> String? = { it }): Merged {
        if (prepared.map { it.path }.toSet().size != prepared.size) throw IOException("ユーザー設定の対象が重複しています。")
        val files = linkedMapOf<String, ByteArray>()
        var added = 0
        var duplicates = 0
        var ignored = prepared.sumOf { it.ignored }
        prepared.forEach { source ->
            val current = existing[source.path]?.let { validate(source.path, it) } ?: Prepared(source.path)
            // 既存の未知項目を落として書き直さない。不明な既存形式では適用前に中止する。
            if (current.ignored != 0) throw IOException("現在のユーザー設定に未対応の項目があるため保持します。")
            if (source.path == USER_AGENTS) {
                val result = current.agents.toMutableList()
                val seen = result.map { it.value }.toHashSet()
                source.agents.forEach { if (seen.add(it.value)) { result += it; added++ } else duplicates++ }
                files[source.path] = boundedEncode(result.map { linkedMapOf("1" to it.name, "0" to it.value) })
            } else {
                val keys = current.patterns.map { it.key }.toSet()
                val seen = current.patterns.map { encode(it.json()).toString(Charsets.UTF_8) }.toHashSet()
                val incoming = arrayListOf<UrlPattern>()
                source.patterns.forEach patternLoop@ { original ->
                    if (original.key in keys) { duplicates++; return@patternLoop }
                    var item = original
                    if (item.type == 1) {
                        val data = objectValue(item.data)
                        if (integer(data["0"]) == 0) {
                            val transformed = remapIntent(data["1"] as String)
                            if (transformed == null) { ignored++; return@patternLoop }
                            checkIntent(transformed)
                            item = item.copy(data = linkedMapOf("0" to 0, "1" to transformed))
                        }
                    }
                    if (seen.add(encode(item.json()).toString(Charsets.UTF_8))) { incoming += item; added++ }
                    else duplicates++
                }
                // Web 設定は後勝ち、外部起動・遮断は先勝ち。既存の相対順と明示設定を優先する。
                val result = incoming.filter { it.type == 3 } + current.patterns + incoming.filter { it.type != 3 }
                files[source.path] = boundedEncode(result.map { it.json() })
            }
        }
        return Merged(files, added, duplicates, ignored)
    }

    private object Unsupported

    private fun openAction(value: Any?): Any {
        val data = objectValue(value)
        if (data.keys.any { it !in setOf("0", "1") }) return Unsupported
        val type = integer(data["0"])
        if (type !in 0..2) return Unsupported
        val intent = data["1"]
        return if (type == 0) {
            val normalized = LegacyImportCompatibility.normalize(migrateLegacyBrowserIntentUri(text(intent, 32768)))
            checkIntent(normalized)
            linkedMapOf("0" to type, "1" to normalized)
        } else {
            if (intent != null && intent !is String) throw IOException("外部起動の内容が不正です。")
            linkedMapOf("0" to type, "1" to intent)
        }
    }

    private fun webAction(value: Any?): Any {
        val data = objectValue(value)
        if (data.keys.any { it !in (0..7).map(Int::toString) }) return Unsupported
        val result = linkedMapOf<String, Any?>()
        if (data.containsKey("0")) result["0"] = text(data["0"], 8192)
        for (index in 1..5) {
            val state = data[index.toString()]?.let(::integer) ?: 0
            if (state !in 0..2) throw IOException("URL 別設定の選択値が不正です。")
            result[index.toString()] = state
        }
        val rendering = data["6"]?.let(::integer) ?: -1
        val theme = data["7"]?.let(::integer) ?: -1
        if (rendering !in -1..4 || theme !in -1..3) throw IOException("URL 別設定の配色値が不正です。")
        result["6"] = rendering
        result["7"] = PageColorMode.normalizeOverride(theme)
        return result
    }

    private fun checkIntent(value: String) {
        val marker = value.lastIndexOf("#Intent;")
        if (marker < 0 || !value.endsWith(";end")) throw IOException("保存済み Intent URI ではありません。")
        // Android の intent:#Intent; は有効だが、java.net.URI は空の SSP を拒否する。
        // 汎用 URI の構文確認時だけ仮の path を置き、保存値・component・extra は変えない。
        val syntax = if (value.startsWith("intent:#Intent;")) "intent:/" + value.substring("intent:".length) else value
        try { URI(syntax) } catch (error: java.net.URISyntaxException) { throw IOException("Intent URI が不正です。", error) }
        val fields = value.substring(marker + 8).split(';')
        if (fields.dropLast(1).any { it == "end" } || fields.count { it == "SEL" } > 1) throw IOException("Intent URI の終端が不正です。")
        fields.filter { it.startsWith("launchFlags=") }.forEach { field ->
            val flags = try { java.lang.Long.decode(field.substringAfter('=')) }
                catch (error: NumberFormatException) { throw IOException("Intent の flags が不正です。", error) }
            if (flags !in Int.MIN_VALUE.toLong()..0xffff_ffffL) throw IOException("Intent の flags が範囲外です。")
        }
        val component = fields.takeWhile { it != "SEL" }.filter { it.startsWith("component=") }
        if (component.size != 1) throw IOException("外部起動の component がありません。")
        val decoded = try { URLDecoder.decode(component.single().substringAfter('=').replace("+", "%2B"), "UTF-8") }
            catch (error: IllegalArgumentException) { throw IOException("外部起動の component が不正です。", error) }
        val parts = decoded.split('/')
        if (parts.size != 2 || parts.any { it.isBlank() || it.any { character -> character.isWhitespace() || character in ";=#" } }) {
            throw IOException("外部起動の component が不正です。")
        }
        // 不正な数値 extra を保存すると実行時の parseUri が失敗するため、適用前に拒否する。
        fields.filter { it.length > 2 && it[1] == '.' && '=' in it }.forEach { field ->
            val decodedValue = try { URLDecoder.decode(field.substringAfter('=').replace("+", "%2B"), "UTF-8") }
                catch (error: IllegalArgumentException) { throw IOException("Intent extra が不正です。", error) }
            try {
                when (field[0]) {
                    'S' -> Unit
                    'B' -> if (decodedValue !in setOf("true", "false")) throw IOException("Intent の真偽値 extra が不正です。")
                    'b' -> decodedValue.toByte()
                    'c' -> if (decodedValue.isEmpty()) throw IOException("Intent の文字 extra が空です。")
                    'd' -> decodedValue.toDouble()
                    'f' -> decodedValue.toFloat()
                    'i' -> decodedValue.toInt()
                    'l' -> decodedValue.toLong()
                    's' -> decodedValue.toShort()
                    else -> throw IOException("未対応の Intent extra 型です。")
                }
            } catch (error: NumberFormatException) { throw IOException("Intent の数値 extra が不正です。", error) }
        }
    }

    private fun text(value: Any?, max: Int): String {
        val text = value as? String ?: throw IOException("文字列設定ではありません。")
        if (text.length > max || text.any { it == '\u0000' || it == '\r' || it == '\n' }) throw IOException("文字列設定が長すぎるか改行を含みます。")
        return text
    }

    private fun integer(value: Any?): Int = try {
        when (value) { is BigDecimal -> value.intValueExact(); is Int -> value; else -> throw IOException("整数設定ではありません。") }
    } catch (error: ArithmeticException) { throw IOException("整数設定の範囲が不正です。", error) }

    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?): Map<String, Any?> = value as? Map<String, Any?>
        ?: throw IOException("設定 object がありません。")

    private fun readJson(bytes: ByteArray): Any? {
        if (bytes.size > MAX_BYTES) throw IOException("ユーザー設定が上限サイズを超えています。")
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        var count = 0
        JsonReader.of(Buffer().writeUtf8(text)).use { reader ->
            fun read(depth: Int): Any? {
                if (depth > 12 || ++count > 32768) throw IOException("ユーザー設定の階層・件数が上限を超えています。")
                return when (reader.peek()) {
                    JsonReader.Token.BEGIN_ARRAY -> {
                        reader.beginArray()
                        val list = arrayListOf<Any?>()
                        while (reader.hasNext()) list += read(depth + 1)
                        reader.endArray(); list
                    }
                    JsonReader.Token.BEGIN_OBJECT -> {
                        reader.beginObject()
                        val map = linkedMapOf<String, Any?>()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            if (key.length > 64 || map.containsKey(key)) throw IOException("ユーザー設定のキーが重複または不正です。")
                            map[key] = read(depth + 1)
                        }
                        reader.endObject(); map
                    }
                    JsonReader.Token.STRING -> reader.nextString()
                    JsonReader.Token.NUMBER -> {
                        val number = reader.nextString()
                        if (number.length > 32) throw IOException("数値設定が長すぎます。")
                        BigDecimal(number)
                    }
                    JsonReader.Token.BOOLEAN -> reader.nextBoolean()
                    JsonReader.Token.NULL -> reader.nextNull<Any>()
                    else -> throw IOException("ユーザー設定 JSON が不正です。")
                }
            }
            val result = read(0)
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) throw IOException("ユーザー設定の末尾に余分な内容があります。")
            return result
        }
    }

    private fun encode(value: Any?): ByteArray {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            fun write(item: Any?) {
                when (item) {
                    null -> writer.nullValue()
                    is String -> writer.value(item)
                    is Boolean -> writer.value(item)
                    is Number -> writer.value(item)
                    is List<*> -> { writer.beginArray(); item.forEach(::write); writer.endArray() }
                    is Map<*, *> -> { writer.beginObject(); item.forEach { (key, entry) -> writer.name(key as String); write(entry) }; writer.endObject() }
                    else -> throw IOException("ユーザー設定を保存できません。")
                }
            }
            write(value)
        }
        return buffer.readByteArray()
    }

    private fun boundedEncode(entries: List<*>): ByteArray {
        if (entries.size > MAX_ITEMS) throw IOException("統合後のユーザー設定件数が上限を超えています。")
        return encode(entries).also { if (it.size > MAX_BYTES) throw IOException("統合後のユーザー設定が上限サイズを超えています。") }
    }
}
