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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.ElementReader
import io.github.ajichama.citrusbrowser.adblock.filter.unified.io.FilterReader
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile

/** 外部HTTPやユーザー購読ではなく、固有の端末内HTTP購読だけを本体Hiltで更新する。 */
@RunWith(AndroidJUnit4::class)
class AbpHttpSubscriptionIntegrationTest {
    @Test fun workerPublishesHttp200ThenHiltNonForcedUpdaterUsesConditional304WithoutReplacingBody() = runBlocking {
        withHttpSubscription { fixture ->
            publish(fixture)
            val before = fixture.saved()
            val bytes = fixture.bundle.readBytes()
            val requests = fixture.server.exchanges.size
            assertEquals("NotModified", fixture.conditionalUpdate())
            withTimeout(3000) { while (fixture.server.exchanges.size <= requests) delay(10) }
            val response = fixture.server.exchanges.last()
            assertEquals(304, response.status)
            assertEquals(fixture.server.modified, response.headers["if-modified-since"])
            assertArrayEquals(bytes, fixture.bundle.readBytes())
            val after = fixture.saved()
            assertEquals(metadata(before), metadata(after))
            assertTrue(after.lastLocalUpdate >= before.lastLocalUpdate)
            assertCacheBody(fixture)
        }
    }

    @Test fun realWorkerRetries503AndKeepsPreviouslyPublishedCacheAndMetadata() = runBlocking {
        withHttpSubscription { fixture ->
            publish(fixture)
            val before = fixture.saved()
            val bytes = fixture.bundle.readBytes()
            fixture.server.unavailable = true
            val work = fixture.awaitRetry(fixture.enqueueManual())
            assertEquals(WorkInfo.State.ENQUEUED, work.state)
            assertTrue(work.runAttemptCount >= 1)
            withTimeout(3000) { while (fixture.server.exchanges.none { it.status == 503 }) delay(10) }
            val after = fixture.saved()
            assertArrayEquals(bytes, fixture.bundle.readBytes())
            assertEquals(metadata(before), metadata(after))
            assertEquals(before.lastLocalUpdate, after.lastLocalUpdate)
            assertCacheBody(fixture)
            // retryの登録までを検証し、次回バックオフ後の成功は主張しない。固有Workはfinallyで取消す。
        }
    }

    private suspend fun publish(fixture: HttpSubscriptionFixture) {
        val info = fixture.awaitFinished(fixture.enqueueManual())
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertTrue(info.outputData.getString("outcome") in setOf("Changed", "NotModified"))
        withTimeout(3000) { while (fixture.server.exchanges.none { it.status == 200 }) delay(10) }
        val saved = fixture.saved()
        assertEquals(fixture.server.title, saved.title)
        assertEquals(fixture.server.version, saved.version)
        assertEquals(fixture.server.modified, saved.lastModified)
        assertTrue(saved.lastLocalUpdate > 0)
        assertCacheBody(fixture)
    }

    private fun metadata(value: AbpEntity): List<Any?> = listOf(value.entityId, value.url, value.title, value.version,
        value.lastUpdate, value.expires, value.homePage, value.lastModified, value.enabled)

    private fun assertCacheBody(fixture: HttpSubscriptionFixture) {
        ZipFile(fixture.bundle).use { zip ->
            assertEquals(setOf("b_", "w_", "wp_", "e_", "manifest.properties"), zip.entries().asSequence().map { it.name }.toSet())
            zip.getInputStream(zip.getEntry("b_")).use { input ->
                val reader = FilterReader(input)
                assertTrue(reader.checkHeader())
                assertEquals(fixture.server.host, reader.readAll().single().pattern)
            }
            zip.getInputStream(zip.getEntry("e_")).use { input ->
                val reader = ElementReader(input)
                assertTrue(reader.checkHeader())
                assertEquals(".citrus-http", reader.readAll().single().selector)
            }
        }
    }
}
