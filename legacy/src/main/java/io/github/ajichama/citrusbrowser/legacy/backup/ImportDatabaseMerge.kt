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

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.ajichama.citrusbrowser.core.utility.utils.BrowserSchemeCompat
import io.github.ajichama.citrusbrowser.core.utility.utils.ImageUtils
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.speeddial.WebIcon
import io.github.ajichama.citrusbrowser.legacy.speeddial.io.ImportedSpeedDial
import java.io.IOException

/** 呼出元の単一接続・既存 transaction を使い、開始・確定・接続の破棄は行わない。 */
internal object ImportDatabaseMerge {
    private const val MAX_CURRENT_ROWS = 20_000

    data class ScriptResult(val added: Int, val duplicates: Int, val idRemap: Map<Long, Long>)

    fun dial(db: SQLiteDatabase, alias: String, rows: List<ImportedSpeedDial>, context: Context): Int {
        checkConnection(db, alias, 3)
        if (rows.size > 4096) throw IOException("追加ダイヤルの件数が上限を超えています。")
        ImportSqlite.checkTable(db, alias, "main_table", setOf("_id", "url", "title", "item_order", "icon", "favicon", "last_update", "favicon_hash"))
        ImportSqlite.checkTable(db, alias, "info", setOf("_id", "name", "time"))
        val urls = hashSetOf<String>()
        var maximumOrder: Long? = null
        var currentCount = 0
        db.rawQuery("SELECT url,item_order FROM $alias.main_table ORDER BY _id LIMIT ${MAX_CURRENT_ROWS + 1}", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (++currentCount > MAX_CURRENT_ROWS || cursor.getType(0) != Cursor.FIELD_TYPE_STRING || cursor.getType(1) != Cursor.FIELD_TYPE_INTEGER) {
                    throw IOException("現在のダイヤルが多すぎるか不正です。")
                }
                urls += normalizedUrl(cursor.getString(0))
                val order = cursor.getLong(1)
                if (order !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw IOException("現在のダイヤルの順序が不正です。")
                maximumOrder = maximumOrder?.let { maxOf(it, order) } ?: order
            }
        }
        val previousTime = db.rawQuery("SELECT time FROM $alias.info WHERE name=? LIMIT 2", arrayOf("main_table")).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getType(0) != Cursor.FIELD_TYPE_INTEGER) throw IOException("ダイヤル更新情報がありません。")
            val time = cursor.getLong(0)
            if (cursor.moveToNext()) throw IOException("ダイヤル更新情報が重複しています。")
            time
        }
        var added = 0
        var fallbackIcon: ByteArray? = null
        rows.forEach { row ->
            val url = normalizedUrl(row.url)
            if (!urls.add(url)) return@forEach
            if (currentCount + added >= MAX_CURRENT_ROWS) throw IOException("統合後のダイヤル件数が上限を超えています。")
            val order = (maximumOrder ?: -1) + 1
            if (order > Int.MAX_VALUE) throw IOException("ダイヤルを末尾へ追加できません。")
            val icon = row.icon ?: fallbackIcon ?: WebIcon.createIcon(
                ImageUtils.getBitmapFromVectorDrawable(context, R.drawable.ic_public_white_24dp)).iconBytes.also { fallbackIcon = it }
            val values = ContentValues().apply {
                put("url", url)
                put("title", row.title)
                put("item_order", order.toInt())
                put("icon", icon)
                put("favicon", if (row.favicon) 1 else 0)
                put("last_update", if (row.favicon) -1L else System.currentTimeMillis())
                put("favicon_hash", 0)
            }
            db.insertOrThrow("$alias.main_table", null, values)
            maximumOrder = order
            added++
        }
        if (added != 0) {
            if (previousTime == Long.MAX_VALUE) throw IOException("ダイヤルの更新日時が不正です。")
            val values = ContentValues().apply { put("time", maxOf(System.currentTimeMillis(), previousTime + 1)) }
            if (db.update("$alias.info", values, "name=?", arrayOf("main_table")) != 1) throw IOException("ダイヤルの更新日時を保存できません。")
        }
        return added
    }

    fun scripts(db: SQLiteDatabase, alias: String, rows: List<ImportScript.Row>): ScriptResult {
        checkConnection(db, alias, 2)
        if (rows.size > 512) throw IOException("追加スクリプトの件数が上限を超えています。")
        ImportSqlite.checkTable(db, alias, "main_table1", setOf("_id", "data", "enabled"))
        val sourceIds = hashSetOf<Long>()
        var incomingBytes = 0L
        val incoming = rows.map { row ->
            if (row.sourceId < 0 || !sourceIds.add(row.sourceId)) throw IOException("スクリプトの参照 ID が不正または重複しています。")
            incomingBytes += row.code.toByteArray(Charsets.UTF_8).size
            if (incomingBytes > 4 * 1024 * 1024) throw IOException("追加スクリプトの合計容量が上限を超えています。")
            row to ImportScript.identity(row.code)
        }
        val identities = linkedMapOf<String, Long>()
        var currentCount = 0
        var currentBytes = 0L
        db.rawQuery("SELECT _id,data,enabled FROM $alias.main_table1 ORDER BY _id LIMIT ${MAX_CURRENT_ROWS + 1}", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (++currentCount > MAX_CURRENT_ROWS || cursor.getType(0) != Cursor.FIELD_TYPE_INTEGER ||
                    cursor.getType(1) != Cursor.FIELD_TYPE_STRING || cursor.getType(2) != Cursor.FIELD_TYPE_INTEGER) {
                    throw IOException("現在のスクリプトが多すぎるか不正です。")
                }
                val id = cursor.getLong(0)
                val enabled = cursor.getInt(2)
                if (id < 0 || enabled !in 0..1) throw IOException("現在のスクリプトの ID または有効状態が不正です。")
                val code = cursor.getString(1)
                currentBytes += code.toByteArray(Charsets.UTF_8).size
                if (currentBytes > 32 * 1024 * 1024) throw IOException("現在のスクリプト容量が上限を超えています。")
                val identity = ImportScript.identity(code)
                // 既存に同じ identity が複数あっても削除せず、最初の実 ID を参照先にする。
                if (!identities.containsKey(identity)) identities[identity] = id
            }
        }
        val remap = linkedMapOf<Long, Long>()
        var added = 0
        var duplicates = 0
        incoming.forEach { (row, identity) ->
            val existing = identities[identity]
            if (existing != null) {
                remap[row.sourceId] = existing
                duplicates++
            } else {
                if (currentCount + added >= MAX_CURRENT_ROWS) throw IOException("統合後のスクリプト件数が上限を超えています。")
                val values = ContentValues().apply { put("data", row.code); put("enabled", if (row.enabled) 1 else 0) }
                val id = db.insertOrThrow("$alias.main_table1", null, values)
                if (id < 0) throw IOException("スクリプトの追加 ID を取得できません。")
                identities[identity] = id
                remap[row.sourceId] = id
                added++
            }
        }
        return ScriptResult(added, duplicates, remap)
    }

    private fun checkConnection(db: SQLiteDatabase, alias: String, expectedVersion: Int) {
        if (!db.inTransaction()) throw IOException("移行用 transaction が開始されていません。")
        if (!alias.matches(Regex("[a-z][a-z0-9]*"))) throw IOException("移行先 DB 名が不正です。")
        db.rawQuery("PRAGMA $alias.user_version", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getInt(0) != expectedVersion) throw IOException("移行先 DB の版が不正です。")
        }
        db.rawQuery("PRAGMA $alias.quick_check(1)", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "ok") throw IOException("移行先 DB の整合性を確認できません。")
        }
    }

    private fun normalizedUrl(value: String): String {
        if (value.isBlank() || value.length > 8192 || value.any { it == '\u0000' || it == '\r' || it == '\n' }) throw IOException("ダイヤルの URL が不正です。")
        return BrowserSchemeCompat.normalizeUrl(value)
    }
}
