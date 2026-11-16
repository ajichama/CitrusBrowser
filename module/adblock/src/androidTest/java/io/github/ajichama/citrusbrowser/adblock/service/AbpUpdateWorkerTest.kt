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

package io.github.ajichama.citrusbrowser.adblock.service

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDatabase
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import io.github.ajichama.citrusbrowser.adblock.ui.abp.AbpWorkViewModel
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AbpUpdateWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun columnOnlyToggleKeepsConcurrentMetadataAndUrlEditsKeepDisabledState() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AbpDatabase::class.java).build()
        try {
            val dao = db.abpDao()
            val id = dao.inset(AbpEntity(url = "https://old.example/list", version = "old", lastLocalUpdate = 100)).toInt()
            val stale = dao.getById(id)!!
            dao.updateMetadata(id, stale.url, "new title", "new date", 200, 48, "new", null, "new last-modified")
            dao.setEnabled(stale.entityId, false)
            val disabled = dao.getById(id)!!
            assertFalse(disabled.enabled)
            assertEquals(200L, disabled.lastLocalUpdate)
            assertEquals("new", disabled.version)
            assertEquals("new last-modified", disabled.lastModified)
            assertEquals(0, dao.updateMetadata(id, stale.url, "stale", null, 300, 1, "stale", null, null))
            dao.updateUrl(id, "https://edited.example/list")
            val edited = dao.getById(id)!!
            assertEquals("https://edited.example/list", edited.url)
            assertFalse(edited.enabled)
            assertEquals(0L, edited.lastLocalUpdate)
            assertNull(edited.lastModified)
        } finally { db.close() }
    }

    @Test
    fun defaultWorkerFactoryRejectsInvalidInputBeforeResolvingDependencies() = runBlocking {
        val manager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<AbpUpdateWorker>()
            .setInputData(workDataOf(AbpUpdateScheduler.KEY_MANUAL to true, AbpUpdateScheduler.KEY_ID to 0,
                AbpUpdateScheduler.KEY_REQUESTED_AT to System.currentTimeMillis())).build()
        manager.enqueue(request).await()
        val info = withTimeout(15_000) { manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true } }!!
        assertEquals(WorkInfo.State.FAILED, info.state)
    }

    @Test
    fun disabledAutomaticDispatcherCompletesAsSkippedInsteadOfRetryingOrReportingChange() = runBlocking {
        val prefs = AppPrefs.getPreference(context)
        val prior = prefs.all["ad_block"]
        prefs.edit().putBoolean("ad_block", false).commit()
        try {
            val manager = WorkManager.getInstance(context)
            val request = OneTimeWorkRequestBuilder<AbpUpdateWorker>()
                .setInputData(workDataOf(AbpUpdateScheduler.KEY_DISPATCH to true)).build()
            manager.enqueue(request).await()
            val info = withTimeout(15_000) { manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true } }!!
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            assertEquals("Skipped", info.outputData.getString(AbpUpdateScheduler.KEY_OUTCOME))
        } finally {
            prefs.edit().apply { if (prior is Boolean) putBoolean("ad_block", prior) else remove("ad_block") }.commit()
        }
    }

    @Test
    fun manualRequestsHaveDistinctPersistableIdsAndCarryNoEntityOrReceiver() {
        val single = AbpUpdateScheduler.request(7, manual = true)
        val all = AbpUpdateScheduler.request(AbpUpdateScheduler.ALL, manual = true)
        assertNotEquals(single.id, all.id)
        assertTrue(single.tags.contains(AbpUpdateScheduler.MANUAL_TAG))
        assertFalse(single.tags.contains(AbpUpdateScheduler.AUTO_TAG))
        assertEquals(setOf(AbpUpdateScheduler.KEY_ID, AbpUpdateScheduler.KEY_MANUAL, AbpUpdateScheduler.KEY_REQUESTED_AT), single.workSpec.input.keyValueMap.keys)
        assertEquals(7, single.workSpec.input.getInt(AbpUpdateScheduler.KEY_ID, 0))
        assertTrue(single.workSpec.input.getBoolean(AbpUpdateScheduler.KEY_MANUAL, false))
    }

    @Test
    fun savedWorkIdsReconnectAndAStaleTagCannotDeliverTheSameCompletionTwice() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val state = SavedStateHandle()
            val id = UUID.randomUUID()
            AbpWorkViewModel(state).adopt(7, id)
            val restored = SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) })
            val model = AbpWorkViewModel(restored)
            val token = model.pending.value.single()
            assertTrue(token.endsWith(id.toString()))
            assertTrue(model.finish(token))
            assertFalse(model.finish(token))
            model.adopt(7, id)
            assertTrue(model.pending.value.isEmpty())
        }
    }
}
