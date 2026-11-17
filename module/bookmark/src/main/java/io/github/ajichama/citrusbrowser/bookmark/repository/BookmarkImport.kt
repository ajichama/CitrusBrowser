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

package io.github.ajichama.citrusbrowser.bookmark.repository

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkFolder
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkItem
import io.github.ajichama.citrusbrowser.bookmark.item.BookmarkSite
import io.github.ajichama.citrusbrowser.bookmark.util.BookmarkIdGenerator
import io.github.ajichama.citrusbrowser.core.utility.utils.BrowserSchemeCompat
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Element
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 外部ブックマークは live root に触れず全件検証し、ID を現データから独立させる。 */
object BookmarkImport {
    data class Item(val title: String, val url: String? = null, val children: List<Item>? = null, val sourceId: Long? = null)
    data class Merged(val bytes: ByteArray, val idRemap: Map<Long, Long>)
    private const val MAX_ITEMS = 20_000
    private const val MAX_DEPTH = 32
    private fun text(bytes: ByteArray): String {
        if (bytes.size > 8 * 1024 * 1024) throw IOException("ブックマークのサイズが上限を超えています。")
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }

    fun json(bytes: ByteArray): List<Item> {
        var count = 0
        val sourceIds = hashSetOf<Long>()
        fun read(reader: JsonReader, depth: Int): Item {
            if (depth > MAX_DEPTH || ++count > MAX_ITEMS) throw IOException("ブックマークの件数・階層が上限を超えています。")
            reader.beginObject()
            var type: Int? = null
            var title = ""
            var url: String? = null
            var children: List<Item>? = null
            var sourceId: Long? = null
            val keys = hashSetOf<String>()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (!keys.add(key)) throw IOException("ブックマークに重複キーがあります。")
                when (key) {
                    "0" -> type = reader.nextInt()
                    "1" -> title = if (reader.peek() == JsonReader.Token.NULL) reader.nextNull<String>() ?: "" else reader.nextString()
                    "3" -> sourceId = reader.nextLong().also { if (!sourceIds.add(it)) throw IOException("ブックマーク ID が重複しています。") }
                    "2" -> when (reader.peek()) {
                        JsonReader.Token.STRING -> url = reader.nextString()
                        JsonReader.Token.BEGIN_ARRAY -> {
                            reader.beginArray()
                            val list = arrayListOf<Item>()
                            while (reader.hasNext()) list += read(reader, depth + 1)
                            reader.endArray()
                            children = list
                        }
                        else -> throw IOException("ブックマークの内容が不正です。")
                    }
                    else -> throw IOException("未対応のブックマーク項目です。")
                }
            }
            reader.endObject()
            if (title.length > 4096 || (url?.length ?: 0) > 8192) throw IOException("ブックマークの文字列が長すぎます。")
            return when {
                type == 1 && children != null && url == null -> Item(title, children = children, sourceId = sourceId)
                type == 2 && !url.isNullOrBlank() && children == null -> Item(title, BrowserSchemeCompat.normalizeUrl(url!!), sourceId = sourceId)
                else -> throw IOException("ブックマークの種別または必須項目が不正です。")
            }
        }
        JsonReader.of(Buffer().writeUtf8(text(bytes))).use { reader ->
            reader.beginArray()
            val items = arrayListOf<Item>()
            while (reader.hasNext()) items += read(reader, 0)
            reader.endArray()
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) throw IOException("ブックマーク末尾に余分なデータがあります。")
            return items
        }
    }

    fun html(bytes: ByteArray): List<Item> {
        val html = text(bytes)
        if (html.count { it == '<' } > 40_000) throw IOException("HTML の要素数が上限を超えています。")
        val document = Jsoup.parse(html)
        val type = document.childNodes().filterIsInstance<DocumentType>().firstOrNull()?.name()
        if (!"netscape-bookmark-file-1".equals(type, true)) throw IOException("Netscape ブックマーク HTML ではありません。")
        var count = 0
        fun read(list: Element, depth: Int): List<Item> {
            if (depth > MAX_DEPTH) throw IOException("ブックマークの階層が上限を超えています。")
            val items = arrayListOf<Item>()
            list.children().filter { it.tagName() == "dt" }.forEach { node ->
                if (++count > MAX_ITEMS) throw IOException("ブックマークの件数が上限を超えています。")
                val folder = node.children().firstOrNull { it.tagName() == "h3" }
                if (folder != null) {
                    val nested = node.children().firstOrNull { it.tagName() == "dl" } ?: throw IOException("フォルダの内容がありません。")
                    items += Item(folder.text(), children = read(nested, depth + 1))
                } else {
                    val link = node.children().firstOrNull { it.tagName() == "a" } ?: throw IOException("不正なリンクです。")
                    val url = link.attr("href").takeIf { it.isNotBlank() } ?: throw IOException("空のリンクです。")
                    if (url.length > 8192 || link.text().length > 4096) throw IOException("リンクの文字列が長すぎます。")
                    items += Item(link.text(), BrowserSchemeCompat.normalizeUrl(url))
                }
            }
            return items
        }
        document.getElementsByTag("p").remove()
        return read(document.body().children().firstOrNull { it.tagName() == "dl" } ?: throw IOException("ブックマーク一覧がありません。"), 0)
    }

    fun count(items: List<Item>): Int = items.sumOf { 1 + (it.children?.let(::count) ?: 0) }

    /** 既存項目は保持し、新しいフォルダと配下の項目だけを新 ID で追加する。 */
    fun merged(root: BookmarkFolder, imported: List<Item>, label: String): ByteArray = mergedWithIds(root, imported, label).bytes

    fun mergedWithIds(root: BookmarkFolder, imported: List<Item>, label: String): Merged {
        val used = hashSetOf<Long>()
        fun collect(folder: BookmarkFolder) {
            folder.itemList.forEach { used += it.id; if (it is BookmarkFolder) collect(it) }
        }
        collect(root)
        fun id(): Long { while (true) { val value = BookmarkIdGenerator.getNewId(); if (used.add(value)) return value } }
        val folder = BookmarkFolder(label, root, id())
        val remap = linkedMapOf<Long, Long>()
        fun create(item: Item, parent: BookmarkFolder): BookmarkItem {
            val newId = id()
            item.sourceId?.let { remap[it] = newId }
            return if (item.children != null) {
                BookmarkFolder(item.title, parent, newId).also { child -> item.children.forEach { child.add(create(it, child)) } }
            } else BookmarkSite(item.title, item.url!!, newId)
        }
        imported.forEach { folder.add(create(it, folder)) }
        val output = Buffer()
        JsonWriter.of(output).use { writer ->
            writer.beginArray()
            root.itemList.forEach { if (!it.write(writer)) throw IOException("既存ブックマークを保存できません。") }
            if (!folder.write(writer)) throw IOException("インポートフォルダを保存できません。")
            writer.endArray()
        }
        return Merged(output.readByteArray(), remap)
    }
}
