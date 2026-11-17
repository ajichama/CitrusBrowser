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

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ajichama.citrusbrowser.legacy.speeddial.io.ImportedSpeedDial
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** 実データに触れず、実ファイルの main と二つの ATTACH を同一接続で検証する。 */
@RunWith(AndroidJUnit4::class)
class ImportDatabaseMergeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun dialKeepsExistingRowsAndAppendsUniqueUrlsWithoutCommittingTheCallerTransaction() = fixture { db ->
        db.execSQL("INSERT INTO dial.main_table(_id,url,title,item_order,icon,favicon,last_update,favicon_hash) VALUES(5,?,'保持',7,X'01',0,9,12)", arrayOf("citrus:home"))
        db.beginTransaction()
        try {
            val added = ImportDatabaseMerge.dial(db, "dial", listOf(
                ImportedSpeedDial("citrus:home", "上書きしない", null, true),
                ImportedSpeedDial("https://example.test/new", "追加", null, true),
                ImportedSpeedDial("https://example.test/new", "重複", null, false)), context)
            assertEquals(1, added)
            assertTrue(db.inTransaction())
            db.rawQuery("SELECT title,item_order,icon,last_update,favicon_hash FROM dial.main_table WHERE _id=5", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("保持", it.getString(0))
                assertEquals(7, it.getInt(1))
                assertArrayEquals(byteArrayOf(1), it.getBlob(2))
                assertEquals(9L, it.getLong(3))
                assertEquals(12L, it.getLong(4))
            }
            db.rawQuery("SELECT title,item_order,icon,favicon,last_update FROM dial.main_table WHERE url=?", arrayOf("https://example.test/new")).use {
                assertTrue(it.moveToFirst())
                assertEquals("追加", it.getString(0))
                assertEquals(8, it.getInt(1))
                val iconBytes = it.getBlob(2)
                val icon = requireNotNull(BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size))
                icon.recycle()
                assertEquals(1, it.getInt(3))
                assertEquals(-1L, it.getLong(4))
            }
            assertTrue(number(db, "SELECT time FROM dial.info WHERE name='main_table'") > 10)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        assertEquals(2L, number(db, "SELECT COUNT(*) FROM dial.main_table"))
    }

    @Test fun scriptsKeepDisabledExistingIdentitiesAndReturnEverySourceIdMapping() = fixture { db ->
        val original = script("same", "元のコード")
        db.execSQL("INSERT INTO scripts.main_table1(_id,data,enabled) VALUES(10,?,0)", arrayOf(original))
        db.beginTransaction()
        try {
            val result = ImportDatabaseMerge.scripts(db, "scripts", listOf(
                ImportScript.Row(script("same", "更新しないコード"), true, 100),
                ImportScript.Row("void 0;", true, 101),
                ImportScript.Row("void 0;", false, 102),
                ImportScript.Row(script("new", "追加コード"), false, 103)))
            assertEquals(2, result.added)
            assertEquals(2, result.duplicates)
            assertEquals(10L, result.idRemap[100])
            assertEquals(result.idRemap[101], result.idRemap[102])
            assertNotEquals(10L, result.idRemap[103])
            assertEquals(setOf(100L, 101L, 102L, 103L), result.idRemap.keys)
            assertTrue(db.inTransaction())
            db.rawQuery("SELECT data,enabled FROM scripts.main_table1 WHERE _id=10", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(original, it.getString(0))
                assertEquals(0, it.getInt(1))
            }
            assertEquals(0L, number(db, "SELECT enabled FROM scripts.main_table1 WHERE _id=${result.idRemap.getValue(103)}"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        assertEquals(3L, number(db, "SELECT COUNT(*) FROM scripts.main_table1"))
    }

    @Test fun aFailureAfterBothMergesRollsBackBothAttachedDatabasesAndDialMetadata() = fixture { db ->
        db.execSQL("INSERT INTO scripts.main_table1(_id,data,enabled) VALUES(10,'original()',0)")
        assertThrows(IOException::class.java) {
            db.beginTransaction()
            try {
                ImportDatabaseMerge.dial(db, "dial", listOf(ImportedSpeedDial("https://example.test/", "追加", null, false)), context)
                val result = ImportDatabaseMerge.scripts(db, "scripts", listOf(ImportScript.Row("incoming()", true, 7)))
                assertEquals(1, result.added)
                throw IOException("後段の保存失敗を模擬")
            } finally { db.endTransaction() }
        }
        assertEquals(0L, number(db, "SELECT COUNT(*) FROM dial.main_table"))
        assertEquals(10L, number(db, "SELECT time FROM dial.info WHERE name='main_table'"))
        assertEquals(1L, number(db, "SELECT COUNT(*) FROM scripts.main_table1"))
        assertEquals(0L, number(db, "SELECT enabled FROM scripts.main_table1 WHERE _id=10"))
    }

    @Test fun corruptCurrentValuesDuplicateSourceIdsAndUnsafeAliasesFailWithoutOwningTheTransaction() = fixture { db ->
        assertThrows(IOException::class.java) { ImportDatabaseMerge.scripts(db, "scripts", emptyList()) }
        db.execSQL("INSERT INTO scripts.main_table1(_id,data,enabled) VALUES(1,'original()',2)")
        db.beginTransaction()
        try {
            assertThrows(IOException::class.java) {
                ImportDatabaseMerge.scripts(db, "scripts", listOf(ImportScript.Row("incoming()", true, 7)))
            }
            assertThrows(IOException::class.java) { ImportDatabaseMerge.scripts(db, "scripts;DROP", emptyList()) }
            db.execSQL("UPDATE scripts.main_table1 SET enabled=0 WHERE _id=1")
            assertThrows(IOException::class.java) {
                ImportDatabaseMerge.scripts(db, "scripts", listOf(ImportScript.Row("a()", true, 7), ImportScript.Row("b()", true, 7)))
            }
            assertTrue(db.inTransaction())
            assertEquals(1L, number(db, "SELECT COUNT(*) FROM scripts.main_table1"))
            // 成功を宣言せず、呼出元が最後に既存値まで戻す。
        } finally { db.endTransaction() }
        assertEquals(2L, number(db, "SELECT enabled FROM scripts.main_table1 WHERE _id=1"))
    }

    private fun fixture(block: (SQLiteDatabase) -> Unit) {
        val directory = File(context.cacheDir, "import-merge-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            SQLiteDatabase.openOrCreateDatabase(File(directory, "main.db"), null).use { db ->
                db.execSQL("ATTACH DATABASE ? AS dial", arrayOf(File(directory, "dial.db").path))
                db.execSQL("ATTACH DATABASE ? AS scripts", arrayOf(File(directory, "scripts.db").path))
                db.execSQL("PRAGMA dial.user_version=3")
                db.execSQL("PRAGMA scripts.user_version=2")
                db.execSQL("CREATE TABLE dial.main_table(_id INTEGER PRIMARY KEY,url TEXT NOT NULL,title TEXT,item_order INTEGER,icon BLOB,favicon INTEGER DEFAULT 0,last_update INTEGER DEFAULT 0,favicon_hash INTEGER DEFAULT 0)")
                db.execSQL("CREATE TABLE dial.info(_id INTEGER PRIMARY KEY,name TEXT NOT NULL,time INTEGER DEFAULT 0)")
                db.execSQL("INSERT INTO dial.info(name,time) VALUES('main_table',10)")
                db.execSQL("CREATE TABLE scripts.main_table1(_id INTEGER PRIMARY KEY,data TEXT NOT NULL,enabled INTEGER DEFAULT 1)")
                try { block(db) }
                finally { if (db.inTransaction()) db.endTransaction() }
            }
        } finally {
            check(directory.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            directory.deleteRecursively()
        }
    }

    private fun number(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use {
        check(it.moveToFirst()); it.getLong(0)
    }

    private fun script(name: String, code: String) = "// ==UserScript==\n// @name $name\n// @namespace fixture\n// ==/UserScript==\n$code"
}
