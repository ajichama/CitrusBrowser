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

package io.github.ajichama.citrusbrowser.adblock.repository.abp

import android.content.Context
import androidx.room.withTransaction
import io.github.ajichama.citrusbrowser.adblock.repository.AdBlockPref
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** URL の追加だけを行い、既存の有効・無効や取得履歴には触れない。 */
object AbpDefaultSubscriptions {
    internal const val REVISION = 2
    /**
     * 版ごとに、その版で既定へ加えた URL。移行では、利用者の記録した版より新しい版の URL だけを一度追加する。
     * 古い版の URL を再び追加しないので、利用者が削除した購読は復活しない。
     * EasyList は旧版の既定リストに常に無効で含まれていた。既存 DB に無ければ利用者が削除したものなので、
     * 移行では追加しない（新規初期化では既定リストどおり有効で登録する）。
     */
    internal val migrationUrlsByRevision = mapOf(
        1 to setOf(
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://filters.adtidy.org/extension/ublock/filters/7.txt",
        ),
        // 反広告ブロック（Ad-Shield 等）への対策が、このリストにだけ含まれる。
        2 to setOf("https://ublockorigin.github.io/uAssets/filters/filters.txt"),
    )

    internal fun migrationUrlsAfter(revision: Int): Set<String> =
        migrationUrlsByRevision.filterKeys { it > revision }.values.flatten().toSet()

    suspend fun initialize(context: Context, database: AbpDatabase) =
        install(context, database, initialize = true)

    suspend fun migrate(context: Context, database: AbpDatabase) =
        install(context, database, initialize = false)

    private suspend fun install(context: Context, database: AbpDatabase, initialize: Boolean) {
        val preferences = AdBlockPref.get(context)
        if (!initialize && preferences.defaultSubscriptionsRevision >= REVISION) return
        val defaults = context.assets.open("adblock/default_filter_list.txt").bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val columns = line.split(',', limit = 3)
                require(columns.size == 3 && columns[0] in setOf("0", "1") && columns[2].isNotBlank())
                AbpEntity(url = columns[2], title = columns[1], enabled = columns[0] == "1")
            }.toList()
        }
        AbpDefaultSubscriptionInstaller(
            database.abpDao(),
            readRevision = { preferences.defaultSubscriptionsRevision },
            writeRevision = { preferences.defaultSubscriptionsRevision = it },
            transaction = { action -> database.withTransaction { action() } },
        ).install(defaults, initialize)
    }
}

internal class AbpDefaultSubscriptionInstaller(
    private val dao: AbpDao,
    private val readRevision: () -> Int,
    private val writeRevision: (Int) -> Unit,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) {
    suspend fun install(defaults: List<AbpEntity>, initialize: Boolean = false) = registrationMutex.withLock {
        if (!initialize && readRevision() >= AbpDefaultSubscriptions.REVISION) return@withLock
        // 初期化と移行を同じロックで直列化し、DB 内の読取と登録も一括で確定する。
        val migrationUrls = AbpDefaultSubscriptions.migrationUrlsAfter(readRevision())
        transaction {
            val existingUrls = dao.getAll().mapTo(mutableSetOf()) { it.url }
            defaults.forEach { candidate ->
                if ((initialize || candidate.url in migrationUrls) &&
                    existingUrls.add(candidate.url)) {
                    dao.inset(candidate)
                }
            }
        }
        // 新規利用者も移行済みにし、後で削除した購読を次回起動で復活させない。
        writeRevision(maxOf(readRevision(), AbpDefaultSubscriptions.REVISION))
    }

    companion object {
        private val registrationMutex = Mutex()
    }
}
