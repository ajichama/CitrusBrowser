/*
 * Copyright (C) 2017-2019 Hazuki
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

package io.github.ajichama.citrusbrowser.search.repository

import android.content.Context
import android.util.AtomicFile
import com.squareup.moshi.Moshi
import io.github.ajichama.citrusbrowser.search.domain.ISearchUrlRepository
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSettings
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException

class SearchUrlManager(context: Context, private val moshi: Moshi) : ISearchUrlRepository {

    val file = File(context.filesDir, NAME)
    private val storage = AtomicFile(file)

    override fun load(): SearchSettings {
        try {
            val items = storage.openRead().source().buffer().use {
                val adapter = moshi.adapter(SearchSettings::class.java)
                adapter.fromJson(it)
            }
            if (items?.items != null) {
                val normalized = SearchImport.normalizeLegacyIds(SearchDefaults.normalize(items))
                if (normalized != items) save(normalized)
                return normalized
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return SearchSettings(0, 0, listOf())
    }

    override fun save(settings: SearchSettings): Boolean {
        var output: java.io.FileOutputStream? = null
        try {
            val json = moshi.adapter(SearchSettings::class.java).toJson(settings).toByteArray(Charsets.UTF_8)
            output = storage.startWrite()
            output.write(json)
            storage.finishWrite(output)
            output = null
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } finally {
            // 移行中の書込失敗・中断でも以前の検索設定を残す。
            output?.let { storage.failWrite(it) }
        }
    }

    companion object {
        private const val NAME = "searchUrl.json"
    }
}
