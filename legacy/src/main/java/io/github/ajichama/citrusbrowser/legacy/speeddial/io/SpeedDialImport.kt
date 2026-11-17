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

package io.github.ajichama.citrusbrowser.legacy.speeddial.io

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import io.github.ajichama.citrusbrowser.core.utility.utils.BrowserSchemeCompat
import io.github.ajichama.citrusbrowser.legacy.backup.ImportSqlite
import java.io.File
import java.io.IOException

internal data class ImportedSpeedDial(val url: String, val title: String?, val icon: ByteArray?, val favicon: Boolean)

internal object SpeedDialImport {
    fun read(file: File): List<ImportedSpeedDial> {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            if (db.version !in 1..3) throw IOException("未対応のスピードダイヤル DB 版です。")
            ImportSqlite.checkTable(db, "main", "main_table", setOf("_id", "url", "title", "item_order", "icon", "favicon"))
            ImportSqlite.quickCheck(db)
            val list = arrayListOf<ImportedSpeedDial>()
            var imageBytes = 0L
            var imagePixels = 0L
            db.query("main_table", arrayOf("url", "title", "icon", "favicon"), null, null, null, null, "item_order asc, _id asc", "4097").use { cursor ->
                while (cursor.moveToNext()) {
                    if (list.size >= 4096) throw IOException("スピードダイヤルの件数が上限を超えています。")
                    val url = cursor.getString(0)?.takeIf { it.isNotBlank() && it.length <= 8192 && !it.contains('\u0000') }
                        ?: throw IOException("スピードダイヤルの URL が不正です。")
                    val icon = if (cursor.isNull(2)) null else cursor.getBlob(2)
                    if (icon != null) {
                        imageBytes += icon.size
                        if (icon.size > 256 * 1024 || imageBytes > 16 * 1024 * 1024) throw IOException("アイコン容量が上限を超えています。")
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(icon, 0, icon.size, options)
                        if (options.outWidth !in 1..512 || options.outHeight !in 1..512) throw IOException("スピードダイヤルの画像が不正または大きすぎます。")
                        imagePixels += options.outWidth.toLong() * options.outHeight
                    } else {
                        imagePixels += 200L * 200
                    }
                    if (imagePixels > 8_000_000) throw IOException("スピードダイヤル画像の合計画素数が上限を超えています。")
                    val title = cursor.getString(1)
                    if ((title?.length ?: 0) > 4096) throw IOException("スピードダイヤルの題名が長すぎます。")
                    list += ImportedSpeedDial(BrowserSchemeCompat.normalizeUrl(url), title, icon, cursor.getInt(3) == 1)
                }
            }
            return list
        }
    }
}
