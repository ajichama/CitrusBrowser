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

package io.github.ajichama.citrusbrowser

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.ElementReader
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterReader
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDao
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import io.github.ajichama.citrusbrowser.adblock.service.AbpUpdateService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile

/** 本体の Application、Hilt graph、標準 WorkerFactory を置換せずに通す。 */
@RunWith(AndroidJUnit4::class)
class AbpUpdateIntegrationTest {
    @Test
    fun manualLocalSubscriptionUsesTheRealHiltGraphAndPublishesMetadataAndAllCacheEntries() = runBlocking {
        withFixture(enabled = true) { fixture ->
            val result = fixture.update()
            assertEquals(WorkInfo.State.SUCCEEDED, result.state)
            // 本体の定期処理が先行した場合の NotModified も正しい成功結果となる。
            assertTrue(result.outputData.getString("outcome") in setOf("Changed", "NotModified"))
            val saved = fixture.dao.getById(fixture.entity.entityId)!!
            assertEquals(fixture.entity.url, saved.url)
            assertEquals(fixture.title, saved.title)
            assertEquals(fixture.version, saved.version)
            assertTrue(saved.lastLocalUpdate > 0)
            assertTrue(saved.enabled)

            ZipFile(fixture.bundle).use { zip ->
                assertEquals(setOf("b_", "w_", "wp_", "e_", "manifest.properties"), zip.entries().asSequence().map { it.name }.toSet())
                val manifest = Properties().apply { zip.getInputStream(zip.getEntry("manifest.properties")).use(::load) }
                assertEquals("1", manifest.getProperty("format"))
                assertTrue(manifest.getProperty("parserRevision").toInt() > 0)
                assertEquals(fixture.entity.url, manifest.getProperty("sourceUrl"))
                UUID.fromString(manifest.getProperty("commitId"))
                assertNull(manifest.getProperty("assetVersion"))
                for (prefix in listOf("b_", "w_", "wp_")) {
                    zip.getInputStream(zip.getEntry(prefix)).use { input ->
                        val reader = FilterReader(input)
                        assertTrue(reader.checkHeader())
                        val filters = reader.readAll().toList()
                        if (prefix == "b_") assertEquals(fixture.host, filters.single().pattern)
                        else assertTrue(filters.isEmpty())
                    }
                }
                zip.getInputStream(zip.getEntry("e_")).use { input ->
                    val reader = ElementReader(input)
                    assertTrue(reader.checkHeader())
                    val filter = reader.readAll().single()
                    assertEquals(fixture.host, filter.domain)
                    assertEquals(".citrus-integration", filter.selector)
                }
            }
            assertTrue(fixture.temporaryCacheFiles().isEmpty())
        }
    }

    @Test
    fun disabledLocalSubscriptionIsSkippedWithoutChangingMetadataOrCreatingACache() = runBlocking {
        withFixture(enabled = false) { fixture ->
            val before = fixture.dao.getById(fixture.entity.entityId)!!
            val result = fixture.update()
            assertEquals(WorkInfo.State.SUCCEEDED, result.state)
            assertEquals("Skipped", result.outputData.getString("outcome"))
            val saved = fixture.dao.getById(fixture.entity.entityId)!!
            assertFalse(saved.enabled)
            assertEquals(before.url, saved.url)
            assertEquals(before.title, saved.title)
            assertEquals(before.version, saved.version)
            assertEquals(before.lastLocalUpdate, saved.lastLocalUpdate)
            assertEquals(before.lastModified, saved.lastModified)
            assertFalse(fixture.bundle.exists())
            assertTrue(fixture.temporaryCacheFiles().isEmpty())
        }
    }

    private suspend fun withFixture(enabled: Boolean, test: suspend (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertTrue(context is CitrusBrowserApplication)
        val application = context as CitrusBrowserApplication
        // Application に本番 Hilt が注入した、実際の DB を利用する。
        val dao = application.abpDatabase.abpDao()
        val token = UUID.randomUUID().toString()
        val file = File.createTempFile("citrus-abp-integration-", ".txt", context.cacheDir)
        val uri = Uri.fromFile(file)
        assertEquals("file", uri.scheme)
        assertTrue(uri.toString().startsWith("file://"))
        val title = "Citrus integration $token"
        val version = "integration-$token"
        val host = "fixture-$token.invalid"
        var entity: AbpEntity? = null
        var fixture: Fixture? = null
        var failure: Throwable? = null
        try {
            // file:// は updater の File.inputStream 分岐を通り、HTTP クライアントへ渡されない。
            file.writeText("[Adblock Plus 2.0]\n! Title: $title\n! Version: $version\n! Expires: 1 day\n||$host^\n$host##.citrus-integration\n")
            val row = AbpEntity(url = uri.toString(), title = "fixture-before", version = "before", lastLocalUpdate = 0,
                lastModified = "fixture-before", enabled = enabled)
            row.entityId = dao.inset(row).toInt()
            entity = row
            val created = Fixture(context, dao, row, title, version, host)
            fixture = created
            test(created)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    // CANCELLED だけでは公開中の NonCancellable 区間が終了したと断定しない。
                    val naturallyFinished = fixture?.settleOwnWork() ?: true
                    val current = entity?.let { dao.getById(it.entityId) }
                    if (current != null) {
                        assertEquals("fixture の URL が変更されたため削除を中止します。", uri.toString(), current.url)
                        dao.delete(current)
                    }
                    if (!naturallyFinished) throw AssertionError("Work の実終了を確認できないため、fixture cache の削除を保留します。")
                    fixture?.deleteOwnCache()
                    assertTrue(!file.exists() || file.delete())
                } catch (cleanup: Throwable) {
                    failure?.addSuppressed(cleanup) ?: throw cleanup
                }
            }
        }
    }

    private class Fixture(
        val context: Context,
        val dao: AbpDao,
        val entity: AbpEntity,
        val title: String,
        val version: String,
        val host: String,
    ) {
        private val manager = WorkManager.getInstance(context)
        private val workIds = mutableListOf<UUID>()
        // 保存形式を外側から検証し、internal Store をテスト用に公開しない。
        private val cacheDirectory = context.getDir("adblock_filter", Context.MODE_PRIVATE)
        val bundle get() = File(cacheDirectory, "abp_${entity.entityId}.zip")

        suspend fun update(): WorkInfo {
            val id = AbpUpdateService.update(context, entity)
            workIds += id
            return awaitTerminal(id)
        }

        private suspend fun awaitTerminal(id: UUID): WorkInfo = withTimeout(30_000) {
            manager.getWorkInfoByIdFlow(id).first { it?.state?.isFinished == true }!!
        }

        suspend fun settleOwnWork(): Boolean {
            var naturallyFinished = true
            for (id in workIds) {
                val beforeCancel = withTimeoutOrNull(30_000) { awaitTerminal(id) }
                if (beforeCancel == null || beforeCancel.state == WorkInfo.State.CANCELLED) naturallyFinished = false
                withTimeout(15_000) { manager.cancelWorkById(id).await() }
                awaitTerminal(id)
            }
            return naturallyFinished
        }

        fun temporaryCacheFiles(): List<File> = cacheDirectory.listFiles().orEmpty().filter {
            it.name.startsWith("abp-${entity.entityId}-") && it.extension in setOf("tmp", "rollback")
        }

        fun deleteOwnCache() {
            AtomicFile(bundle).delete()
            val legacy = listOf("b_", "w_", "wp_", "e_").map { File(cacheDirectory, "$it${entity.entityId}") }
            (legacy + temporaryCacheFiles()).forEach { assertTrue(!it.exists() || it.delete()) }
            assertFalse(bundle.exists())
            assertFalse(File(bundle.path + ".bak").exists())
            assertFalse(File(bundle.path + ".new").exists())
        }
    }
}
