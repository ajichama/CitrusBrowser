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

internal object ImportSqlite {
    fun checkTable(db: SQLiteDatabase, alias: String, table: String, columns: Set<String>) {
        // alias/table は内部定数のみ。view や virtual table を実行しない。
        db.rawQuery("SELECT type,sql FROM $alias.sqlite_master WHERE name=?", arrayOf(table)).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "table" ||
                !cursor.getString(1).trimStart().startsWith("CREATE TABLE", true)) throw IOException("通常の構成テーブルではありません。")
        }
        val found = hashSetOf<String>()
        db.rawQuery("PRAGMA $alias.table_info($table)", null).use { cursor -> while (cursor.moveToNext()) found += cursor.getString(1) }
        if (!found.containsAll(columns)) throw IOException("構成テーブルの列が不足しています。")
        db.rawQuery("SELECT 1 FROM $alias.sqlite_master WHERE type='trigger' LIMIT 1", null).use {
            if (it.moveToFirst()) throw IOException("外部 DB のトリガーは受け付けません。")
        }
    }

    fun quickCheck(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA quick_check", null).use { if (!it.moveToFirst() || it.getString(0) != "ok") throw IOException("DB の整合性を確認できません。") }
    }

    /** main も実ファイルにし、選択した DB を単一接続の通常トランザクションにまとめる。 */
    fun <T> transaction(scratch: File, targets: Map<String, File>, block: (SQLiteDatabase, () -> Unit) -> T): T {
        SQLiteDatabase.openOrCreateDatabase(scratch, null).use { db ->
            targets.forEach { (alias, file) ->
                if (!alias.matches(Regex("[a-z][a-z0-9]*"))) throw IOException("不正な DB 名です。")
                db.execSQL("ATTACH DATABASE ? AS $alias", arrayOf(file.path))
            }
            (listOf("main") + targets.keys).forEach { alias ->
                db.rawQuery("PRAGMA $alias.journal_mode", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getString(0).lowercase() !in setOf("delete", "truncate", "persist"))
                        throw IOException("DB のジャーナル方式を安全に統合できません。")
                }
            }
            db.beginTransaction()
            var committed = false
            try {
                return block(db) {
                    check(!committed)
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    committed = true
                }.also { check(committed) }
            } finally {
                if (db.inTransaction()) db.endTransaction()
            }
        }
    }
}
