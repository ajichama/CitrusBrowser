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

import android.graphics.BitmapFactory
import android.util.Base64
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.github.ajichama.citrusbrowser.core.utility.utils.BrowserSchemeCompat
import io.github.ajichama.citrusbrowser.core.utility.utils.migrateLegacyBrowserIntentUri
import io.github.ajichama.citrusbrowser.legacy.action.SingleAction
import io.github.ajichama.citrusbrowser.legacy.action.item.CustomMenuSingleAction
import io.github.ajichama.citrusbrowser.legacy.action.item.CustomSingleAction
import io.github.ajichama.citrusbrowser.legacy.action.manager.*
import okio.Buffer
import java.io.IOException
import java.lang.reflect.Modifier
import java.math.BigDecimal

internal object ImportActions {
    /** 未知の操作 ID があれば、そのファイル全体を対象外にして現設定を保持する。 */
    fun validate(path: String, bytes: ByteArray, remapIntent: (String) -> String? = { it }): ByteArray? {
        if (bytes.size > 1024 * 1024) throw IOException("操作設定が上限サイズを超えています。")
        val folder = path.substringBefore('/').removePrefix("app_")
        val id = path.substringAfter('/').removeSuffix(".dat").toIntOrNull() ?: return null
        val file = when (folder) {
            "action1_btn" -> SoftButtonActionFile(folder, id)
            "action1_sbtn_ary", "action1_custombar" -> SoftButtonActionArrayFile(folder, id)
            "action1_menu", "action1_qc" -> ActionArrayFile(folder, id)
            else -> SingleActionFile(folder, id)
        }
        val normalized = try { normalizeJson(bytes, remapIntent) } catch (_: UnresolvedReference) { return null }
        JsonReader.of(Buffer().write(normalized)).use { reader ->
            if (!file.load(reader) || reader.peek() != JsonReader.Token.END_DOCUMENT) throw IOException("操作設定の構造が不正です。")
        }
        fun soft(value: SoftButtonActionFile) = listOf(value.press, value.lpress, value.up, value.down, value.left, value.right).flatten()
        val actions = when (file) {
            is SoftButtonActionFile -> soft(file)
            is SoftButtonActionArrayFile -> file.list.flatMap(::soft)
            is ActionArrayFile -> file.list.flatten()
            is SingleActionFile -> file.action.toList()
            else -> emptyList()
        }
        val knownIds = SingleAction::class.java.fields.filter { it.type == Int::class.javaPrimitiveType && Modifier.isStatic(it.modifiers) }
            .map { it.getInt(null) }.toSet()
        fun supported(list: List<SingleAction>, depth: Int): Boolean = depth <= 32 && list.all {
            it.id in knownIds && when (it) {
                is CustomSingleAction -> supported(it.action, depth + 1)
                is CustomMenuSingleAction -> supported(it.actionList.flatten(), depth + 1)
                else -> true
            }
        }
        if (!supported(actions, 0)) return null
        val result = Buffer()
        JsonWriter.of(result).use { if (!file.write(it)) throw IOException("操作設定を保存できません。") }
        return result.readByteArray()
    }

    private class UnresolvedReference : Exception()

    private fun normalizeJson(bytes: ByteArray, remapIntent: (String) -> String?): ByteArray {
        var count = 0
        var imagePixels = 0L
        val output = Buffer()
        JsonReader.of(Buffer().write(bytes)).use { reader ->
            JsonWriter.of(output).use { writer ->
                fun copy(depth: Int, key: String? = null, intentData: Boolean = false, intentValue: Boolean = false) {
                    if (depth > 32 || ++count > 20_000) throw IOException("操作設定の階層・件数が上限を超えています。")
                    when (reader.peek()) {
                        JsonReader.Token.BEGIN_ARRAY -> {
                            reader.beginArray(); writer.beginArray()
                            var index = 0
                            var activity = false
                            while (reader.hasNext()) {
                                if (index == 0 && reader.peek() == JsonReader.Token.NUMBER) {
                                    val number = reader.peekJson().use { it.nextString() }
                                    if (number.length > 64) throw IOException("数値が長すぎます。")
                                    activity = BigDecimal(number).compareTo(BigDecimal(SingleAction.START_ACTIVITY)) == 0
                                }
                                copy(depth + 1, intentData = activity && index == 1)
                                index++
                            }
                            reader.endArray(); writer.endArray()
                        }
                        JsonReader.Token.BEGIN_OBJECT -> {
                            reader.beginObject(); writer.beginObject()
                            val names = hashSetOf<String>()
                            while (reader.hasNext()) {
                                val name = reader.nextName()
                                if (!names.add(name)) throw IOException("操作設定のキーが重複しています。")
                                writer.name(name)
                                // 名前や URL 断片を Intent と誤認せず、型が分かった操作の保存フィールドだけを扱う。
                                copy(depth + 1, name, intentValue = intentData && name == "0")
                            }
                            reader.endObject(); writer.endObject()
                        }
                        JsonReader.Token.STRING -> {
                            val value = reader.nextString()
                            if (key == "2" && value.length >= 64) {
                                val image = try { Base64.decode(value, Base64.DEFAULT) } catch (_: IllegalArgumentException) { null }
                                if (image != null) {
                                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(image, 0, image.size, options)
                                    if (options.outWidth > 0 && options.outHeight > 0) imagePixels += options.outWidth.toLong() * options.outHeight
                                    if (options.outWidth > 512 || options.outHeight > 512 || imagePixels > 2_000_000) throw IOException("操作アイコンの合計画素数が上限を超えています。")
                                }
                            }
                            val normalized = LegacyImportCompatibility.normalize(BrowserSchemeCompat.normalizeUrl(migrateLegacyBrowserIntentUri(value)))
                            writer.value(if (intentValue) remapIntent(normalized) ?: throw UnresolvedReference() else normalized)
                        }
                        JsonReader.Token.NUMBER -> { val number = reader.nextString(); if (number.length > 64) throw IOException("数値が長すぎます。"); writer.value(BigDecimal(number)) }
                        JsonReader.Token.BOOLEAN -> writer.value(reader.nextBoolean())
                        JsonReader.Token.NULL -> { reader.nextNull<Any>(); writer.nullValue() }
                        else -> throw IOException("操作 JSON が不正です。")
                    }
                }
                copy(0)
                if (reader.peek() != JsonReader.Token.END_DOCUMENT) throw IOException("操作 JSON 末尾が不正です。")
            }
        }
        return output.readByteArray()
    }
}
