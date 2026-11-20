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
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ajichama.citrusbrowser.adblock.repository.AdBlockPref
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AbpDefaultSubscriptionsIntegrationTest {
    @Test
    fun initializeUsesThePackagedDefaultsWithoutDuplicatingRowsAndRecordsItsRevision() = runBlocking {
        withIsolatedDatabase { context, database ->
            assertEquals(0, AdBlockPref.get(context).defaultSubscriptionsRevision)
            AbpDefaultSubscriptions.initialize(context, database)
            val initial = database.abpDao().getAll()
            assertEquals(setOf(CITRUS, EASY_LIST, EASY_PRIVACY, JAPANESE, UBLOCK), initial.filter { it.enabled }.map { it.url }.toSet())
            assertEquals(5, initial.count { it.enabled })
            assertEquals(initial.size, initial.map { it.url }.toSet().size)
            assertEquals(AbpDefaultSubscriptions.REVISION, AdBlockPref.get(context).defaultSubscriptionsRevision)

            AbpDefaultSubscriptions.initialize(context, database)
            val repeated = database.abpDao().getAll()
            assertEquals(initial.map { it.entityId to it.url }.toSet(), repeated.map { it.entityId to it.url }.toSet())
            assertEquals(5, repeated.count { it.enabled })
            assertEquals(AbpDefaultSubscriptions.REVISION, AdBlockPref.get(context).defaultSubscriptionsRevision)
        }
    }

    @Test
    fun migrationPreservesDisabledUnfetchedEasyListAndDoesNotRestoreADeletedSubscription() = runBlocking {
        withIsolatedDatabase { context, database ->
            val dao = database.abpDao()
            val existing = AbpEntity(
                url = EASY_LIST, title = "利用者が設定した名前", enabled = false, lastLocalUpdate = 0,
                lastUpdate = "保存済みの更新日", expires = 72, version = "保存済みの版",
                homePage = "https://user.example/help", lastModified = "Wed, 18 Nov 2026 00:00:00 GMT",
            )
            val existingId = dao.inset(existing).toInt()
            AbpDefaultSubscriptions.migrate(context, database)
            val rows = dao.getAll()
            // 版3で加えた Cookie 同意・迷惑要素のリストは、無効の購読候補として追加する。
            assertEquals(setOf(EASY_LIST, EASY_PRIVACY, JAPANESE, UBLOCK) + ANNOYANCES, rows.map { it.url }.toSet())
            assertEquals(7, rows.size)
            assertTrue(rows.filter { it.url in ANNOYANCES }.none { it.enabled })
            val unchanged = dao.getById(existingId)!!
            assertEquals(EASY_LIST, unchanged.url)
            assertFalse(unchanged.enabled)
            assertEquals(0L, unchanged.lastLocalUpdate)
            assertEquals(existing.title, unchanged.title)
            assertEquals(existing.lastUpdate, unchanged.lastUpdate)
            assertEquals(existing.expires, unchanged.expires)
            assertEquals(existing.version, unchanged.version)
            assertEquals(existing.homePage, unchanged.homePage)
            assertEquals(existing.lastModified, unchanged.lastModified)
            assertTrue(rows.single { it.url == EASY_PRIVACY }.enabled)
            assertTrue(rows.single { it.url == JAPANESE }.enabled)
            assertTrue(rows.single { it.url == UBLOCK }.enabled)
            assertEquals(AbpDefaultSubscriptions.REVISION, AdBlockPref.get(context).defaultSubscriptionsRevision)

            dao.delete(rows.single { it.url == EASY_PRIVACY })
            AbpDefaultSubscriptions.migrate(context, database)
            val afterDeletion = dao.getAll()
            assertEquals(setOf(EASY_LIST, JAPANESE, UBLOCK) + ANNOYANCES, afterDeletion.map { it.url }.toSet())
            assertEquals(6, afterDeletion.size)
            assertFalse(dao.getById(existingId)!!.enabled)
            assertEquals(AbpDefaultSubscriptions.REVISION, AdBlockPref.get(context).defaultSubscriptionsRevision)
        }
    }

    private suspend fun withIsolatedDatabase(block: suspend (Context, AbpDatabase) -> Unit) {
        val context = IsolatedPreferencesContext(ApplicationProvider.getApplicationContext())
        val database = Room.inMemoryDatabaseBuilder(context, AbpDatabase::class.java).build()
        try {
            block(context, database)
        } finally {
            try { database.close() } finally { context.deleteTestPreferences() }
        }
    }

    /** アセットは本番のまま使い、設定の名前だけをテストごとに分離する。 */
    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val prefix = "abp-default-subscriptions-test-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            names += isolatedName
            return baseContext.getSharedPreferences(isolatedName, mode)
        }

        fun deleteTestPreferences() {
            names.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }

    companion object {
        private const val CITRUS = "citrus://adblock/filter"
        private const val EASY_LIST = "https://easylist.to/easylist/easylist.txt"
        private const val EASY_PRIVACY = "https://easylist.to/easylist/easyprivacy.txt"
        private const val JAPANESE = "https://filters.adtidy.org/extension/ublock/filters/7.txt"
        private const val UBLOCK = "https://ublockorigin.github.io/uAssets/filters/filters.txt"
        private val ANNOYANCES = setOf(
            "https://ublockorigin.github.io/uAssets/filters/annoyances-cookies.txt",
            "https://ublockorigin.github.io/uAssets/filters/annoyances-others.txt",
            "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
        )
    }
}
