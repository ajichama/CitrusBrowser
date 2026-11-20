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
package io.github.ajichama.citrusbrowser.legacy.backup

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 「後で読む」の取り込み。Yuzu と Citrus は同じ形式で、`app_readItLater/index`（time・url・title の一覧）と、
 * time を名前にした保存ページ（WebView の Web アーカイブ）を持つ。ページの中身は表示時に WebView が読むだけで、ここでは解釈しない。
 */
internal object ImportReadItLater {
    const val INDEX = "app_readItLater/index"
    private const val PREFIX = "app_readItLater/"
    private val PAGE = Regex("app_readItLater/([0-9]{1,18})")
    private const val MAX_INDEX_BYTES = 8 * 1024 * 1024
    private const val MAX_ITEMS = 20_000

    data class Item(val time: Long, val url: String, val title: String)
    data class Prepared(val items: List<Pair<Item, File>>, val ignored: Int)
    data class Merged(val index: ByteArray, val pages: List<Pair<File, Long>>, val added: Int, val duplicates: Int)

    fun isPage(path: String) = PAGE.matches(path)

    fun pagePath(time: Long) = PREFIX + time

    /** 索引に載り、保存ページもそろっている項目だけを取り込む。片方しかないものは除外数に数える。 */
    fun prepare(index: ByteArray?, files: Map<String, File>): Prepared {
        val pages = files.keys.filter(::isPage).toSet()
        if (index == null) return Prepared(emptyList(), pages.size)
        val items = read(index)
        val used = hashSetOf<Long>()
        val accepted = arrayListOf<Pair<Item, File>>()
        var ignored = 0
        items.forEach { item ->
            val file = files[pagePath(item.time)]
            if (file == null || !used.add(item.time)) ignored++ else accepted += item to file
        }
        ignored += pages.count { path -> path.removePrefix(PREFIX).toLong() !in used }
        return Prepared(accepted, ignored)
    }

    /**
     * 既存の項目と順序を保持し、取り込む項目を末尾へ追加する。同じ time・URL の項目は既存を保持する。
     * time が既存の項目や残っているファイルと重なる場合は、空いている次の値へ付け替える。
     */
    fun merge(current: List<Item>, prepared: Prepared, pageExists: (Long) -> Boolean): Merged {
        if (current.size + prepared.items.size > MAX_ITEMS) throw IOException("後で読むの件数が上限を超えています。")
        val used = current.map { it.time }.toHashSet()
        val existing = current.map { it.time to it.url }.toHashSet()
        val result = current.toMutableList()
        val pages = arrayListOf<Pair<File, Long>>()
        var duplicates = 0
        prepared.items.forEach { (item, file) ->
            if ((item.time to item.url) in existing) { duplicates++; return@forEach }
            var time = item.time
            while (time in used || pageExists(time)) {
                if (time == Long.MAX_VALUE) throw IOException("後で読むの番号が不足しています。")
                time++
            }
            used += time
            result += item.copy(time = time)
            pages += file to time
        }
        return Merged(encode(result), pages, pages.size, duplicates)
    }

    fun read(bytes: ByteArray): List<Item> {
        if (bytes.size > MAX_INDEX_BYTES) throw IOException("後で読むの索引が上限サイズを超えています。")
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        val items = arrayListOf<Item>()
        try { JsonReader.of(Buffer().writeUtf8(text)).use { reader ->
            if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) throw IOException("後で読むの索引が一覧ではありません。")
            reader.beginArray()
            while (reader.hasNext()) {
                if (items.size >= MAX_ITEMS) throw IOException("後で読むの件数が上限を超えています。")
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) throw IOException("後で読むの項目が不正です。")
                reader.beginObject()
                var time: Long? = null
                var url: String? = null
                var title: String? = null
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "time" -> time = if (reader.peek() == JsonReader.Token.NUMBER) reader.nextString().toLongOrNull() else { reader.skipValue(); null }
                        "url" -> url = if (reader.peek() == JsonReader.Token.STRING) reader.nextString() else { reader.skipValue(); null }
                        "title" -> title = if (reader.peek() == JsonReader.Token.STRING) reader.nextString() else { reader.skipValue(); null }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (time == null || time < 0 || url == null || title == null || url.length > 16384 || title.length > 4096 ||
                    url.contains('\u0000') || title.contains('\u0000')) {
                    throw IOException("後で読むの項目が不正です。")
                }
                items += Item(time, url, title)
            }
            reader.endArray()
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) throw IOException("後で読むの索引の末尾に余分な内容があります。")
        } } catch (error: com.squareup.moshi.JsonDataException) {
            throw IOException("後で読むの索引が JSON として不正です。", error)
        }
        return items
    }

    private fun encode(items: List<Item>): ByteArray {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writer ->
            writer.beginArray()
            items.forEach { item ->
                writer.beginObject()
                writer.name("time").value(item.time)
                writer.name("url").value(item.url)
                writer.name("title").value(item.title)
                writer.endObject()
            }
            writer.endArray()
        }
        return buffer.readByteArray()
    }
}
