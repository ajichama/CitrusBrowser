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
import android.content.SharedPreferences
import io.github.ajichama.citrusbrowser.adblock.repository.AdBlockPref
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.io.IOException

class AbpDefaultSubscriptionsTest {
    private val defaults = listOf(
        File("src/main/assets/adblock/default_filter_list.txt"),
        File("module/adblock/src/main/assets/adblock/default_filter_list.txt"),
    ).first(File::isFile).readLines().filter { it.isNotBlank() }.map {
        val columns = it.split(',', limit = 3)
        AbpEntity(url = columns[2], title = columns[1], enabled = columns[0] == "1")
    }
    private val easyList = "https://easylist.to/easylist/easylist.txt"
    private val easyPrivacy = "https://easylist.to/easylist/easyprivacy.txt"
    private val japanese = "https://filters.adtidy.org/extension/ublock/filters/7.txt"
    private val ublock = "https://ublockorigin.github.io/uAssets/filters/filters.txt"

    @Test
    fun firstInitializationRegistersTheThreeNewDefaultsEnabledWithoutDownloadingLists() = runBlocking {
        val fixture = Fixture()
        fixture.installer().install(defaults, initialize = true)
        assertEquals(defaults.size, fixture.dao.entries.size)
        for (url in listOf(easyList, easyPrivacy, japanese, ublock, "citrus://adblock/filter")) {
            assertTrue(url, fixture.dao.entries.single { it.url == url }.enabled)
        }
        assertTrue(fixture.dao.entries.all { it.lastLocalUpdate == 0L })
        assertEquals(AbpDefaultSubscriptions.REVISION, fixture.revision)
    }

    @Test
    fun migrationPreservesDisabledUnfetchedEasyListAndExistingMetadata() = runBlocking {
        val original = AbpEntity(entityId = 3, url = easyList, enabled = false, title = "利用者の名前",
            lastLocalUpdate = 0, lastModified = "保存済み", version = "保存版")
        val privacy = AbpEntity(entityId = 4, url = easyPrivacy, enabled = false, lastLocalUpdate = 123)
        val fixture = Fixture(listOf(original, privacy))
        fixture.installer().install(defaults)
        assertSame(original, fixture.dao.entries.single { it.url == easyList })
        assertFalse(original.enabled)
        assertEquals("利用者の名前", original.title)
        assertEquals(0L, original.lastLocalUpdate)
        assertEquals("保存済み", original.lastModified)
        assertEquals("保存版", original.version)
        assertFalse(privacy.enabled)
        assertEquals(123L, privacy.lastLocalUpdate)
        assertEquals(setOf(easyList, easyPrivacy, japanese, ublock), fixture.dao.entries.map { it.url }.toSet())
        assertTrue(fixture.dao.entries.single { it.url == japanese }.enabled)
        assertTrue(fixture.dao.entries.single { it.url == ublock }.enabled)
    }

    @Test
    fun migrationAddsOnlyTheNewDefaultsAndDoesNotRecreateOldOptionalSubscriptions() = runBlocking {
        // EasyList は旧版の既定リストに常に（無効で）含まれていた。既存利用者に無いなら利用者が削除したものなので、
        // 移行で有効化して復活させない。追加するのは旧版に無かった EasyPrivacy・AdGuard Japanese・uBlock filters だけ。
        val fixture = Fixture()
        fixture.installer().install(defaults)
        assertEquals(setOf(easyPrivacy, japanese, ublock), fixture.dao.entries.map { it.url }.toSet())
        assertEquals(AbpDefaultSubscriptions.REVISION, fixture.revision)
    }

    @Test
    fun revisionOneUsersGetOnlyUblockFiltersAndTheirDeletedSubscriptionsStayDeleted() = runBlocking {
        // 版1で EasyPrivacy を削除した利用者に、版2の移行で EasyPrivacy を戻さない。
        val kept = AbpEntity(entityId = 7, url = japanese, enabled = false, lastLocalUpdate = 55)
        val fixture = Fixture(listOf(kept))
        fixture.revision = 1
        fixture.installer().install(defaults)
        assertEquals(setOf(japanese, ublock), fixture.dao.entries.map { it.url }.toSet())
        assertSame(kept, fixture.dao.entries.single { it.url == japanese })
        assertFalse(kept.enabled)
        assertTrue(fixture.dao.entries.single { it.url == ublock }.enabled)
        assertEquals(AbpDefaultSubscriptions.REVISION, fixture.revision)
        // 最新の版の利用者には、何も追加しない。
        fixture.dao.entries.removeAll { it.url == ublock }
        fixture.installer().install(defaults)
        assertFalse(fixture.dao.entries.any { it.url == ublock })
    }

    @Test
    fun repeatedInitializationIgnoresDuplicateUrlsAndNeverEnablesAnExistingDisabledEntry() = runBlocking {
        val original = AbpEntity(entityId = 1, url = easyList, enabled = false)
        val fixture = Fixture(listOf(original))
        repeat(2) { fixture.installer().install(defaults + defaults, initialize = true) }
        assertEquals(defaults.size, fixture.dao.entries.size)
        assertSame(original, fixture.dao.entries.single { it.url == easyList })
        assertFalse(original.enabled)
    }

    @Test
    fun migrationDoesNotResurrectAUserDeletedSubscriptionAfterInitialization() = runBlocking {
        val fixture = Fixture()
        fixture.installer().install(defaults, initialize = true)
        fixture.dao.entries.removeAll { it.url == easyList }
        fixture.installer().install(defaults)
        assertFalse(fixture.dao.entries.any { it.url == easyList })
    }

    @Test
    fun failedDatabaseTransactionDoesNotRecordMigrationAndTheNextAttemptCompletes() = runBlocking {
        val fixture = Fixture()
        fixture.dao.failUrl = easyPrivacy
        try {
            fixture.installer().install(defaults)
            fail("登録失敗を呼出側へ返す必要があります")
        } catch (_: IOException) { }
        assertEquals(0, fixture.revision)
        assertTrue(fixture.dao.entries.isEmpty())
        fixture.dao.failUrl = null
        fixture.installer().install(defaults)
        assertEquals(setOf(easyPrivacy, japanese, ublock), fixture.dao.entries.map { it.url }.toSet())
        assertEquals(AbpDefaultSubscriptions.REVISION, fixture.revision)
    }

    @Test
    fun failedRevisionWriteIsRetriedWithoutDuplicatingAlreadyCommittedRows() = runBlocking {
        val fixture = Fixture()
        fixture.failRevisionWrite = true
        try {
            fixture.installer().install(defaults)
            fail("移行版の保存失敗を呼出側へ返す必要があります")
        } catch (_: IOException) { }
        assertEquals(0, fixture.revision)
        assertEquals(3, fixture.dao.entries.size)
        fixture.failRevisionWrite = false
        fixture.installer().install(defaults)
        assertEquals(3, fixture.dao.entries.size)
        assertEquals(AbpDefaultSubscriptions.REVISION, fixture.revision)
    }

    @Test
    fun concurrentInitializationAndMigrationCannotInsertTheSameUrlTwice() = runBlocking {
        val fixture = Fixture()
        (1..12).map { index ->
            async { fixture.installer().install(defaults, initialize = index % 2 == 0) }
        }.awaitAll()
        assertEquals(defaults.size, fixture.dao.entries.size)
        assertEquals(defaults.size, fixture.dao.entries.map { it.url }.toSet().size)
        assertEquals(AbpDefaultSubscriptions.REVISION, fixture.revision)
    }

    @Test
    fun aFutureMigrationRevisionIsNeverDowngraded() = runBlocking {
        val fixture = Fixture()
        fixture.revision = AbpDefaultSubscriptions.REVISION + 1
        fixture.installer().install(defaults)
        assertTrue(fixture.dao.entries.isEmpty())
        fixture.installer().install(defaults, initialize = true)
        assertEquals(AbpDefaultSubscriptions.REVISION + 1, fixture.revision)
    }

    @Test
    fun failedPreferenceCommitRestoresTheInMemoryRevisionSoTheSameProcessCanRetry() {
        val context = Mockito.mock(Context::class.java)
        val preferences = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java)
        val values = mutableMapOf<String, Int>()
        val pending = mutableMapOf<String, Int>()
        var commitSucceeds = false
        Mockito.`when`(context.getSharedPreferences("abp", Context.MODE_PRIVATE)).thenReturn(preferences)
        Mockito.`when`(preferences.edit()).thenReturn(editor)
        Mockito.`when`(preferences.getInt(Mockito.anyString(), Mockito.anyInt())).thenAnswer {
            values[it.getArgument<String>(0)] ?: it.getArgument<Int>(1)
        }
        Mockito.`when`(editor.putInt(Mockito.anyString(), Mockito.anyInt())).thenAnswer {
            pending[it.getArgument<String>(0)] = it.getArgument(1)
            editor
        }
        Mockito.`when`(editor.commit()).thenAnswer {
            values.putAll(pending)
            commitSucceeds
        }
        Mockito.doAnswer { values.putAll(pending); null }.`when`(editor).apply()
        val pref = AdBlockPref.get(context)
        assertThrows(IOException::class.java) { pref.defaultSubscriptionsRevision = 1 }
        assertEquals(0, pref.defaultSubscriptionsRevision)
        commitSucceeds = true
        pref.defaultSubscriptionsRevision = 1
        assertEquals(1, pref.defaultSubscriptionsRevision)
    }

    private class Fixture(initial: List<AbpEntity> = emptyList()) {
        val dao = MemoryDao(initial)
        var revision = 0
        var failRevisionWrite = false

        fun installer() = AbpDefaultSubscriptionInstaller(dao,
            readRevision = { revision },
            writeRevision = {
                if (failRevisionWrite) throw IOException("移行版の保存失敗")
                revision = it
            },
            transaction = { action ->
                val previous = dao.entries.toList()
                try { action() } catch (error: Exception) {
                    dao.entries.clear()
                    dao.entries.addAll(previous)
                    throw error
                }
            },
        )
    }

    private class MemoryDao(initial: List<AbpEntity>) : AbpDao {
        val entries = initial.toMutableList()
        var failUrl: String? = null
        override suspend fun getAll(): List<AbpEntity> {
            delay(1)
            return entries.toList()
        }
        override suspend fun inset(abpEntity: AbpEntity): Long {
            if (abpEntity.url == failUrl) throw IOException("登録失敗")
            entries += abpEntity
            return entries.size.toLong()
        }
        override suspend fun inset(entities: List<AbpEntity>) = error("一括登録は使いません")
        override suspend fun delete(abpEntity: AbpEntity) = error("既存購読の削除は禁止です")
        override suspend fun update(abpEntity: AbpEntity) = error("既存購読の変更は禁止です")
        override suspend fun setEnabled(id: Int, enabled: Boolean): Int = error("有効・無効の変更は禁止です")
        override suspend fun updateUrl(id: Int, url: String): Int = error("URL の変更は禁止です")
        override suspend fun getById(id: Int): AbpEntity? = entries.firstOrNull { it.entityId == id }
        override suspend fun updateMetadata(id: Int, expectedUrl: String, title: String?, lastUpdate: String?,
            lastLocalUpdate: Long, expires: Int, version: String?, homePage: String?, lastModified: String?): Int =
            error("取得履歴の変更は禁止です")
        override suspend fun markChecked(id: Int, expectedUrl: String, time: Long): Int = error("取得履歴の変更は禁止です")
    }
}
