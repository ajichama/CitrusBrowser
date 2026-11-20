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

package io.github.ajichama.citrusbrowser.adblock.core

import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_ELEMENT
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_ALLOW
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_DENY
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_DISABLE_ELEMENT_PAGE
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.ElementReader
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterReader
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpFilterStore
import io.github.ajichama.citrusbrowser.core.utility.log.ErrorReport
import java.io.File
import java.io.IOException
import java.io.Closeable

internal class AbpLoader(
    private val abpDir: File,
    entityList: List<AbpEntity>,
    store: AbpFilterStore = AbpFilterStore(abpDir),
) : Closeable {
    // 全カテゴリを同じ公開世代から読む。壊れた束がある場合は旧ファイルへ混ぜて戻さない。
    private val entries = entityList.filter { it.enabled }.mapNotNull {
        try {
            Entry(it.entityId, store.open(it.entityId, it.url))
        } catch (error: IOException) {
            ErrorReport.printAndWriteLog(error)
            null
        }
    }

    private class Entry(val entityId: Int, val snapshot: AbpFilterStore.Snapshot?)

    private fun Entry.open(prefix: String) = snapshot?.open(prefix)
        ?: File(abpDir, prefix + entityId).takeIf { it.exists() }?.inputStream()

    fun loadAll(prefix: String) = sequence {
        entries.forEach {
            try {
                val input = it.open(prefix) ?: return@forEach
                input.buffered().use { ins ->
                    val reader = FilterReader(ins)
                    if (reader.checkHeader()) {
                        yieldAll(reader.readAll())
                    }
                }
            } catch (e: IOException) {
                ErrorReport.printAndWriteLog(e)
            }
        }
    }

    fun loadAllElementFilter() = sequence {
        entries.forEach {
            try {
                val input = it.open(ABP_PREFIX_ELEMENT) ?: return@forEach
                input.buffered().use { ins ->
                    val reader = ElementReader(ins)
                    if (reader.checkHeader()) {
                        yieldAll(reader.readAll())
                    }
                }
            } catch (e: IOException) {
                ErrorReport.printAndWriteLog(e)
            }
        }
    }

    fun loadNetworkRules() = NetworkFilterRules(
        loadAll(ABP_PREFIX_DENY).toList(),
        loadAll(ABP_PREFIX_ALLOW).toList(),
        loadAll(ABP_PREFIX_DISABLE_ELEMENT_PAGE).toList(),
    )

    override fun close() {
        entries.forEach { it.snapshot?.close() }
    }
}
