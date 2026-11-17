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

package io.github.ajichama.citrusbrowser.search.repository

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSettings
import io.github.ajichama.citrusbrowser.search.model.provider.SearchUrl
import okio.Buffer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 既知の検索 JSON だけを検証・統合し、実ファイルや設定には書き込まない。 */
object SearchImport {
    const val PATH = "files/searchUrl.json"
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_ITEMS = 1024
    private const val MAX_STRING = 64 * 1024

    data class MergeResult(val settings: SearchSettings, val idRemap: Map<Int, Int>)

    @JvmStatic
    @Throws(IOException::class)
    fun decode(bytes: ByteArray, moshi: Moshi): SearchSettings {
        if (bytes.size > MAX_BYTES) throw IOException("検索設定が上限サイズを超えています。")
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        try {
            JsonReader.of(Buffer().writeUtf8(text)).use { reader ->
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) throw IOException("検索設定の構造が不正です。")
                var count = 0
                fun inspect(depth: Int) {
                    if (depth > 8 || ++count > 20_000) throw IOException("検索設定の階層・件数が上限を超えています。")
                    when (reader.peek()) {
                        JsonReader.Token.BEGIN_OBJECT -> {
                            reader.beginObject()
                            val keys = hashSetOf<String>()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                if (key.length > 128 || !keys.add(key)) throw IOException("検索設定のキーが不正または重複しています。")
                                inspect(depth + 1)
                            }
                            reader.endObject()
                        }
                        JsonReader.Token.BEGIN_ARRAY -> {
                            reader.beginArray()
                            while (reader.hasNext()) inspect(depth + 1)
                            reader.endArray()
                        }
                        JsonReader.Token.STRING -> if (reader.nextString().length > MAX_STRING) throw IOException("検索設定の文字列が長すぎます。")
                        JsonReader.Token.NUMBER -> if (reader.nextString().length > 64) throw IOException("検索設定の数値が長すぎます。")
                        JsonReader.Token.BOOLEAN -> reader.nextBoolean()
                        JsonReader.Token.NULL -> reader.nextNull<Any>()
                        else -> throw IOException("検索設定の JSON が不正です。")
                    }
                }
                inspect(0)
                if (reader.peek() != JsonReader.Token.END_DOCUMENT) throw IOException("検索設定の末尾が不正です。")
            }
            val settings = moshi.adapter(SearchSettings::class.java).fromJson(text)
                ?: throw IOException("検索設定が空です。")
            validate(settings, allowLegacy = true)
            return normalizeLegacyIds(SearchDefaults.normalize(settings))
        } catch (error: JsonDataException) {
            throw IOException("検索設定の項目・型が不正です。", error)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun merge(current: SearchSettings, incoming: SearchSettings): MergeResult {
        validate(current, allowLegacy = true)
        validate(incoming, allowLegacy = true)
        val target = normalizeLegacyIds(current)
        // 初期 Google の指紋は、ID の衝突解消で採番し直す前に確認する。
        val source = normalizeLegacyIds(SearchDefaults.normalize(incoming))
        val result = target.items.map { it.copy() }.toMutableList()
        val used = result.mapTo(hashSetOf()) { it.id }
        var counter = maxOf(maxOf(target.idCount, source.idCount),
            maxOf(used.maxOrNull() ?: 0, source.items.maxOfOrNull { it.id } ?: 0))
        val remap = linkedMapOf<Int, Int>()
        for ((index, entry) in source.items.withIndex()) {
            val originalId = incoming.items[index].id
            val identical = result.firstOrNull {
                it.title == entry.title && it.url == entry.url && it.color == entry.color && it.isUseFavicon == entry.isUseFavicon
            }
            if (identical != null) {
                remap.putIfAbsent(originalId, identical.id)
                continue
            }
            if (result.size >= MAX_ITEMS) throw IOException("統合後の検索項目数が上限を超えています。")
            val id = if (entry.id !in used) entry.id else {
                if (counter >= Int.MAX_VALUE - 1) throw IOException("検索項目の ID を追加できません。")
                ++counter
            }
            used += id
            result += entry.copy(id = id)
            remap.putIfAbsent(originalId, id)
        }
        // 旧初期値0は当時と同じ先頭選択。未知のIDを推測でindexへ読み替えない。
        val importedSelection = remap[incoming.selectedId]
            ?: if (incoming.selectedId == 0) incoming.items.firstOrNull()?.let { remap[it.id] } else null
        val selection = importedSelection ?: target.selectedId
        return MergeResult(SearchSettings(selection, counter, result), remap)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun encode(settings: SearchSettings, moshi: Moshi): ByteArray {
        validate(settings)
        return moshi.adapter(SearchSettings::class.java).toJson(settings).toByteArray(Charsets.UTF_8).also {
            if (it.size > MAX_BYTES) throw IOException("統合後の検索設定が上限サイズを超えています。")
        }
    }

    /** 旧UIは新規項目を採番せず -1 のまま保存した。最初の一致という旧選択の意味を保つ。 */
    @JvmStatic
    @Throws(IOException::class)
    fun normalizeLegacyIds(settings: SearchSettings): SearchSettings {
        if (settings.items.none { it.id == -1 }) return settings
        var counter = maxOf(settings.idCount, settings.items.maxOfOrNull { it.id } ?: 0)
        var firstId: Int? = null
        val items = settings.items.map { entry ->
            if (entry.id != -1) entry else {
                if (counter >= Int.MAX_VALUE - 1) throw IOException("旧検索項目の ID を補正できません。")
                val id = ++counter
                if (firstId == null) firstId = id
                entry.copy(id = id)
            }
        }
        return settings.copy(selectedId = if (settings.selectedId == -1) firstId!! else settings.selectedId,
            idCount = counter, items = items)
    }

    private fun validate(settings: SearchSettings, allowLegacy: Boolean = false) {
        val stableIds = settings.items.map { it.id }.filterNot { allowLegacy && it == -1 }
        if (settings.idCount < 0 || settings.idCount == Int.MAX_VALUE || settings.items.size > MAX_ITEMS ||
            stableIds.toSet().size != stableIds.size ||
            settings.items.any { (it.id < 0 && !(allowLegacy && it.id == -1)) || it.id == Int.MAX_VALUE || it.title.length > MAX_STRING || it.url.length > MAX_STRING }) {
            throw IOException("検索設定の ID・件数・文字列が不正です。")
        }
    }
}
