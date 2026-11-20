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
package io.github.ajichama.citrusbrowser.browser

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 閉じたタブの記録（新しい順、最大 [MAX] 件）。
 * 戻る・進むの履歴を含む WebView の状態はメモリ上だけに持ち、URL・タイトル・タブの種類はファイルにも残す。
 * アプリを終了した後は、ファイルの URL から開き直す（履歴は戻らない）。プライベートモードで閉じたタブはファイルに残さない。
 */
internal class ClosedTabs(private val file: File) {
    class Entry(val state: Bundle?, val url: String, val title: String, val tabType: Int, val persist: Boolean)

    private val entries = ArrayDeque<Entry>()
    private var savedToFile = false

    init {
        load()
    }

    fun push(entry: Entry) {
        entries.addFirst(entry)
        while (entries.size > MAX) entries.removeLast()
        save()
    }

    fun poll(): Entry? = entries.removeFirstOrNull()?.also { save() }

    /** 履歴の消去などで、記録をすべて消す。 */
    fun clear() {
        entries.clear()
        save()
    }

    /** ファイルに残したはずの記録が外から消されていたら（履歴の消去）、メモリ上の記録も消す。 */
    fun dropIfFileDeleted() {
        if (savedToFile && !file.isFile) {
            entries.clear()
            savedToFile = false
        }
    }

    private fun load() {
        if (!file.isFile) return
        savedToFile = true
        try {
            val array = JSONArray(file.readText())
            for (i in 0 until minOf(array.length(), MAX)) {
                val item = array.getJSONObject(i)
                val url = item.optString(KEY_URL)
                if (url.isEmpty()) continue
                entries.addLast(Entry(null, url, item.optString(KEY_TITLE), item.optInt(KEY_TYPE), true))
            }
        } catch (e: IOException) {
            file.delete()
        } catch (e: JSONException) {
            file.delete()
        }
    }

    private fun save() {
        val array = JSONArray()
        entries.filter { it.persist }.forEach {
            array.put(JSONObject().put(KEY_URL, it.url).put(KEY_TITLE, it.title).put(KEY_TYPE, it.tabType))
        }
        try {
            if (array.length() == 0) file.delete() else file.writeText(array.toString())
            savedToFile = array.length() > 0
        } catch (e: IOException) {
            // 保存に失敗しても、メモリ上の記録で開き直せる。
        }
    }

    companion object {
        const val MAX = 20
        const val FILE_NAME = "closed_tabs.json"
        private const val KEY_URL = "url"
        private const val KEY_TITLE = "title"
        private const val KEY_TYPE = "type"
    }
}
