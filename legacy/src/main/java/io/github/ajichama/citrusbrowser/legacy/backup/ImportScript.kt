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
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** コードは実行せず保存する。利用者が専用カテゴリを選択した場合だけ適用する。 */
internal object ImportScript {
    const val PATH = "databases/userjs1.db"
    data class Row(val code: String, val enabled: Boolean, val sourceId: Long)

    fun identity(code: String): String {
        if (code.toByteArray(Charsets.UTF_8).size > 512 * 1024 || code.contains('\u0000')) throw IOException("スクリプトが大きすぎるか不正です。")
        var name: String? = null
        var namespace = ""
        if (code.lineSequence().firstOrNull()?.matches(Regex("\\s*//\\s*==UserScript==\\s*", RegexOption.IGNORE_CASE)) == true) {
            var ended = false
            code.lineSequence().drop(1).take(129).forEachIndexed { index, line ->
                if (ended) return@forEachIndexed
                if (index >= 128 || line.length > 2048) throw IOException("スクリプトのメタデータが上限を超えています。")
                if (line.matches(Regex("\\s*//\\s*==/UserScript==\\s*", RegexOption.IGNORE_CASE))) { ended = true; return@forEachIndexed }
                val match = Regex("\\s*//\\s*@(\\S+)(?:\\s+(.*))?").matchEntire(line)
                if (match != null) when (match.groupValues[1].lowercase(java.util.Locale.ROOT)) {
                    "name" -> name = match.groupValues[2]
                    "namespace" -> namespace = match.groupValues[2]
                }
            }
            if (!ended) throw IOException("スクリプトのメタデータが閉じていません。")
        }
        return if (!name.isNullOrBlank()) "name:$namespace\u0000$name"
        else "sha:" + MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun read(file: File): List<Row> = SQLiteDatabase.openDatabase(file.path, null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
        if (db.version !in 1..2) throw IOException("未対応のスクリプト DB です。")
        val column = if (db.version == 1) "jsdata" else "data"
        ImportSqlite.checkTable(db, "main", "main_table1", setOf("_id", column, "enabled"))
        ImportSqlite.quickCheck(db)
        val rows = arrayListOf<Row>()
        var total = 0L
        db.rawQuery("SELECT $column,enabled,_id FROM main_table1 ORDER BY _id LIMIT 513", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (rows.size >= 512 || cursor.getType(0) != android.database.Cursor.FIELD_TYPE_STRING ||
                    cursor.getType(1) != android.database.Cursor.FIELD_TYPE_INTEGER || cursor.getType(2) != android.database.Cursor.FIELD_TYPE_INTEGER ||
                    cursor.getLong(2) < 0) throw IOException("スクリプトの件数または内容が不正です。")
                val code = cursor.getString(0)
                total += code.toByteArray(Charsets.UTF_8).size
                if (total > 4 * 1024 * 1024) throw IOException("スクリプトの合計サイズが上限を超えています。")
                identity(code)
                val enabled = cursor.getInt(1)
                if (enabled !in 0..1) throw IOException("スクリプトの有効状態が不正です。")
                rows += Row(code, enabled == 1, cursor.getLong(2))
            }
        }
        rows
    }
}
