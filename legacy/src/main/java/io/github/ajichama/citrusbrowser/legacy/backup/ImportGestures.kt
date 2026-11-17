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

import android.database.sqlite.SQLiteDatabase
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer
import java.io.File
import java.io.IOException
import java.math.BigDecimal

internal object ImportGestures {
    const val MULTI = "files/multiFingerGes_1.dat"
    val paths = setOf(MULTI) + (0..1).flatMap { listOf("app_gestures1/$it.lib.dat", "app_gestures1/$it.list.dat") }
    data class PairData(val type: Int, val entries: List<ImportGestureFormat.Entry>, val actions: Map<Long, ByteArray>, val ignored: Int)
    data class Multi(val fingers: Int, val traces: List<Int>, val action: ByteArray) {
        val key get() = fingers to traces
    }
    data class Merged(val bytes: ByteArray, val added: Int, val duplicates: Int, val ignored: Int)

    fun pair(type: Int, library: ByteArray, database: File): PairData {
        val entries = ImportGestureFormat.decode(library)
        val actions = linkedMapOf<Long, ByteArray>()
        val ids = hashSetOf<Long>()
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            if (db.version != 1) throw IOException("未対応のジェスチャー操作 DB です。")
            ImportSqlite.checkTable(db, "main", "main_table1", setOf("_id", "action"))
            ImportSqlite.quickCheck(db)
            db.rawQuery("SELECT _id,action FROM main_table1 ORDER BY _id LIMIT 513", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (ids.size >= 512 || cursor.isNull(1)) throw IOException("ジェスチャー操作の件数・内容が不正です。")
                    val id = cursor.getLong(0)
                    ids += id
                    val bytes = cursor.getString(1).toByteArray(Charsets.UTF_8)
                    ImportActions.validate("app_action1_hbtn/0.dat", bytes)?.let { actions[id] = it }
                }
            }
        }
        if (entries.any { it.actionId !in ids }) throw IOException("ジェスチャーの対応する操作がありません。")
        return PairData(type, entries, actions, entries.count { it.actionId !in actions })
    }

    fun merge(pair: PairData, current: List<ImportGestureFormat.Entry>, remap: (String) -> String?, addAction: (ByteArray) -> Long): Merged {
        val entries = current.toMutableList()
        val shapes = current.flatMap { it.samples }.map(ImportGestureFormat::shape).toHashSet()
        val used = current.flatMap { it.samples }.map { it.id }.toHashSet()
        var next = 0L
        fun newId(): Long { while (!used.add(next)) { if (next == Long.MAX_VALUE) throw IOException("ジェスチャー ID が不足しています。"); next++ }; return next }
        var added = 0
        var duplicates = 0
        var ignored = 0
        pair.entries.forEach { entry ->
            val raw = pair.actions[entry.actionId]
            val action = raw?.let { ImportActions.validate("app_action1_hbtn/0.dat", it, remap) }
            if (action == null) { ignored++; return@forEach }
            val samples = entry.samples.filter { sample ->
                if (shapes.add(ImportGestureFormat.shape(sample))) true else { duplicates++; false }
            }.map { it.copy(id = newId()) }
            if (samples.isNotEmpty()) { entries += ImportGestureFormat.Entry(addAction(action), samples); added += samples.size }
        }
        return Merged(ImportGestureFormat.encode(entries), added, duplicates, ignored)
    }

    fun multi(bytes: ByteArray, remap: (String) -> String? = { it }, strict: Boolean = false): Pair<List<Multi>, Int> {
        if (bytes.size > 2 * 1024 * 1024) throw IOException("マルチタッチ設定が大きすぎます。")
        var tokens = 0
        JsonReader.of(Buffer().write(bytes)).use { reader ->
            fun value(depth: Int): Any? {
                if (depth > 32 || ++tokens > 20_000) throw IOException("マルチタッチ設定の階層・件数が上限を超えています。")
                return when (reader.peek()) {
                    JsonReader.Token.BEGIN_ARRAY -> { reader.beginArray(); arrayListOf<Any?>().also { list -> while (reader.hasNext()) list += value(depth + 1); reader.endArray() } }
                    JsonReader.Token.BEGIN_OBJECT -> { reader.beginObject(); linkedMapOf<String, Any?>().also { map -> while (reader.hasNext()) { val key = reader.nextName(); if (map.containsKey(key)) throw IOException("重複したキーです。"); map[key] = value(depth + 1) }; reader.endObject() } }
                    JsonReader.Token.STRING -> reader.nextString()
                    JsonReader.Token.NUMBER -> reader.nextString().let { if (it.length > 64) throw IOException("数値が長すぎます。"); BigDecimal(it) }
                    JsonReader.Token.BOOLEAN -> reader.nextBoolean()
                    JsonReader.Token.NULL -> reader.nextNull<Any>()
                    else -> throw IOException("不正な JSON です。")
                }
            }
            val list = value(0) as? List<*> ?: throw IOException("マルチタッチ一覧がありません。")
            if (list.size > 512 || reader.peek() != JsonReader.Token.END_DOCUMENT) throw IOException("マルチタッチ設定が不正です。")
            var ignored = 0
            val result = list.mapNotNull { raw ->
                val map = raw as? Map<*, *> ?: throw IOException("不正なマルチタッチ項目です。")
                if (map.keys != setOf("0", "1", "2")) throw IOException("マルチタッチ設定の項目が不正です。")
                val fingers = (map["0"] as? BigDecimal)?.intValueExact() ?: throw IOException("指の本数が不正です。")
                val traces = (map["1"] as? List<*>)?.map { (it as? BigDecimal)?.intValueExact() ?: throw IOException("軌跡が不正です。") }
                    ?: throw IOException("軌跡がありません。")
                if (fingers !in 1..10 || traces.size > 64 || traces.any { it !in 1..4 }) throw IOException("マルチタッチ設定が上限を超えています。")
                val action = ImportActions.validate("app_action1_hbtn/0.dat", json(map["2"]), remap)
                if (action == null) { if (strict) throw IOException("現在の操作設定を読み取れません。"); ignored++; null }
                else Multi(fingers, traces, action)
            }
            return result to ignored
        }
    }

    fun mergeMulti(current: List<Multi>, incoming: List<Multi>): Merged {
        val keys = current.map { it.key }.toHashSet()
        val additions = incoming.filter { keys.add(it.key) }
        if (current.size + additions.size > 512) throw IOException("統合後のマルチタッチ設定が上限を超えています。")
        val bytes = Buffer()
        JsonWriter.of(bytes).use { writer ->
            writer.beginArray()
            (current + additions).forEach { item ->
                writer.beginObject(); writer.name("0").value(item.fingers)
                writer.name("1").beginArray(); item.traces.forEach { writer.value(it) }; writer.endArray()
                writer.name("2"); JsonReader.of(Buffer().write(item.action)).use { writer.jsonValue(it.readJsonValue()) }
                writer.endObject()
            }
            writer.endArray()
        }
        return Merged(bytes.readByteArray(), additions.size, incoming.size - additions.size, 0)
    }

    private fun json(value: Any?): ByteArray = Buffer().also { buffer -> JsonWriter.of(buffer).use { it.jsonValue(value) } }.readByteArray()
}
