/*
 * Copyright (C) 2017-2021 Hazuki
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
import android.net.Uri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.ajichama.citrusbrowser.adblock.BROADCAST_ACTION_UPDATE_AD_BLOCK_DATA
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterDecoder
import io.github.ajichama.citrusbrowser.adblock.filter.abp.AbpFilterPreprocessor
import io.github.ajichama.citrusbrowser.adblock.filter.unified.getFilterDir
import io.github.ajichama.citrusbrowser.adblock.repository.AdBlockPref
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDatabase
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDao
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpFilterStore
import io.github.ajichama.citrusbrowser.core.eventbus.LocalEventBus
import io.github.ajichama.citrusbrowser.core.utility.log.ErrorReport
import io.github.ajichama.citrusbrowser.core.utility.utils.BrowserSchemeCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID

/** 実処理は呼出側の Coroutine に属し、永続キューから完了まで待機できる。 */
class AbpUpdateService internal constructor(
    private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val dao: AbpDao,
    private val store: AbpFilterStore = AbpFilterStore(appContext.getFilterDir()),
    private val assetReader: () -> BufferedReader = { appContext.assets.open("adblock/citrus_filter.txt").bufferedReader() },
) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface AbpUpdateEntryPoint {
        fun okHttpClient(): OkHttpClient
        fun abpDatabase(): AbpDatabase
    }

    internal suspend fun dueEntities(): List<AbpEntity> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entities = dao.getAll().filter { it.enabled }
        val due = entities.filter { needsUpdate(it, now) }
        AdBlockPref.get(appContext).abpNextUpdateTime = entities.minOfOrNull {
            maxOf(now + AN_HOUR, it.lastLocalUpdate + interval(it))
        } ?: now + A_DAY
        due
    }

    private fun needsUpdate(entity: AbpEntity, now: Long): Boolean {
        val assetVersion = if (isBuiltIn(entity.url)) readAssetVersion() else null
        // グローバル予定が未来でも、旧 parser/asset の cache は再生成対象にする。
        return now - entity.lastLocalUpdate >= interval(entity) ||
            !store.isCurrent(entity.entityId, entity.url, assetVersion)
    }

    internal suspend fun entityIds(): List<Int> = dao.getAll().filter { it.enabled }.map { it.entityId }

    internal suspend fun updateEntity(id: Int, forceUpdate: Boolean, requestedAt: Long,
                                     automatic: Boolean = false): AbpUpdateOutcome =
        withContext(Dispatchers.IO) {
            updateMutex.withLock {
                val current = dao.getById(id)?.takeIf { it.enabled } ?: return@withLock AbpUpdateOutcome.Skipped
                if (automatic && !AbpUpdateScheduler.automaticEnabled(appContext)) return@withLock AbpUpdateOutcome.Skipped
                if (automatic && !AbpUpdateScheduler.wifiAllowed(appContext)) return@withLock AbpUpdateOutcome.RetryableFailure
                // バルク再試行やプロセス復元時に、同じ要求で成功した購読を再取得しない。
                if (current.lastLocalUpdate >= requestedAt && !needsUpdate(current, System.currentTimeMillis())) return@withLock AbpUpdateOutcome.NotModified
                if (automatic && !needsUpdate(current, System.currentTimeMillis())) return@withLock AbpUpdateOutcome.NotModified
                val outcome = updateInternal(current, forceUpdate)
                if (outcome == AbpUpdateOutcome.Changed) {
                    LocalEventBus.getDefault().notify(BROADCAST_ACTION_UPDATE_AD_BLOCK_DATA)
                }
                outcome
            }
        }

    internal suspend fun updateInternal(entity: AbpEntity, forceUpdate: Boolean = false): AbpUpdateOutcome {
        // 再実行には購読IDだけを用い、待機中に変更された購読情報を採用する。
        val fresh = dao.getById(entity.entityId)?.takeIf { it.enabled && it.url == entity.url }
            ?: return AbpUpdateOutcome.Skipped
        val sourceUrl = fresh.url
        return try {
            when (BrowserSchemeCompat.normalizeScheme(sourceUrl.substringBefore(':', ""))) {
                "citrus" -> if (isBuiltIn(sourceUrl)) updateAssets(fresh, sourceUrl, forceUpdate) else AbpUpdateOutcome.PermanentFailure
                "http", "https" -> updateHttp(fresh, sourceUrl, forceUpdate)
                "file" -> updateFile(fresh, sourceUrl, forceUpdate)
                else -> AbpUpdateOutcome.PermanentFailure
            }.also {
                val latest = dao.getById(entity.entityId)
                if (latest?.url == sourceUrl) copyMetadata(latest, entity)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: FileNotFoundException) {
            ErrorReport.printAndWriteLog(error)
            AbpUpdateOutcome.PermanentFailure
        } catch (error: IOException) {
            ErrorReport.printAndWriteLog(error)
            AbpUpdateOutcome.RetryableFailure
        } catch (error: Exception) {
            ErrorReport.printAndWriteLog(error)
            AbpUpdateOutcome.PermanentFailure
        }
    }

    private suspend fun updateHttp(entity: AbpEntity, sourceUrl: String, forceUpdate: Boolean): AbpUpdateOutcome {
        var failure: IOException? = null
        val primary = try {
            attemptHttp(entity, sourceUrl, sourceUrl, forceUpdate)
        } catch (error: IOException) {
            failure = error
            null
        }
        if (primary != null && primary != AbpUpdateOutcome.RetryableFailure) return primary
        // 主な配布元が一時的に使えないときだけ、同じ内容を公開している公式の予備から取得する。
        // 購読の識別・保存は元の URL のまま行う。404 等の恒久的な失敗では予備を使わない。
        for (mirror in AbpListMirrors.of(sourceUrl)) {
            currentCoroutineContext().ensureActive()
            val outcome = try {
                attemptHttp(entity, sourceUrl, mirror, forceUpdate = true)
            } catch (error: IOException) {
                failure?.addSuppressed(error) ?: run { failure = error }
                null
            }
            if (outcome != null && outcome != AbpUpdateOutcome.RetryableFailure) return outcome
        }
        return primary ?: throw failure ?: IOException("フィルタを取得できませんでした。")
    }

    private suspend fun attemptHttp(entity: AbpEntity, sourceUrl: String, fetchUrl: String, forceUpdate: Boolean): AbpUpdateOutcome = coroutineScope {
        val request = Request.Builder().url(fetchUrl).get()
        val currentCache = store.isCurrent(entity.entityId, sourceUrl)
        if (!forceUpdate && currentCache && fetchUrl == sourceUrl) entity.lastModified?.let { request.addHeader("If-Modified-Since", it) }
        val httpRequest = request.build()
        val call = okHttpClient.newCall(httpRequest)
        // ヘッダー受信後の body 読み込み中にも、Worker 停止を Call.cancel へ伝える。
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.awaitResponse().use { response ->
                if (response.code == 304) {
                    return@coroutineScope if (currentCache && httpRequest.header("If-Modified-Since") != null) {
                        markChecked(entity, sourceUrl)
                    } else AbpUpdateOutcome.RetryableFailure
                }
                if (!response.isSuccessful) return@coroutineScope AbpUpdateOutcome.forHttpError(response.code)
                val body = response.body ?: return@coroutineScope AbpUpdateOutcome.PermanentFailure
                val charset = body.contentType()?.charset() ?: Charsets.UTF_8
                body.source().inputStream().bufferedReader(charset).use { reader ->
                    // 予備から取得した更新日時は、主な配布元への条件付き取得に使わない。
                    decode(reader, charset, entity, sourceUrl, response.header("Last-Modified").takeIf { fetchUrl == sourceUrl },
                        includeBaseUrl = response.request.url.toString())
                }
            }
        } finally {
            cancellation.cancelAndJoin()
        }
    }

    private suspend fun fetchInclude(target: HttpUrl, parent: HttpUrl): AbpFilterPreprocessor.Resource {
        // 自動リダイレクトの前に境界を検証する。別ホストへの要求を送ってから拒否しない。
        val client = okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var current = target
        repeat(6) {
            currentCoroutineContext().ensureActive()
            if (!AbpFilterPreprocessor.isAllowedTarget(parent, current)) throw IOException("include の取得先が許可範囲外です。")
            val resource = coroutineScope {
                val call = client.newCall(Request.Builder().url(current).get().build())
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.awaitResponse().use { response ->
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            val location = response.header("Location") ?: throw IOException("include の転送先がありません。")
                            val next = current.resolve(location) ?: throw IOException("include の転送先が不正です。")
                            if (!AbpFilterPreprocessor.isAllowedTarget(parent, next)) throw IOException("include の転送先が許可範囲外です。")
                            current = next
                            null
                        } else {
                            if (!response.isSuccessful) throw IOException("include の取得に失敗しました: ${response.code}")
                            val body = response.body ?: throw IOException("include の本文がありません。")
                            val charset = body.contentType()?.charset() ?: Charsets.UTF_8
                            val contents = body.source().inputStream().bufferedReader(charset).use { AbpFilterPreprocessor.readBounded(it) }
                            AbpFilterPreprocessor.Resource(current, contents)
                        }
                    }
                } finally {
                    cancellation.cancelAndJoin()
                }
            }
            if (resource != null) return resource
        }
        throw IOException("include の転送回数が上限を超えました。")
    }

    private suspend fun updateFile(entity: AbpEntity, sourceUrl: String, forceUpdate: Boolean): AbpUpdateOutcome {
        val path = Uri.parse(sourceUrl).path ?: return AbpUpdateOutcome.PermanentFailure
        val file = File(path)
        if (!forceUpdate && store.isCurrent(entity.entityId, sourceUrl) && file.lastModified() < entity.lastLocalUpdate) {
            return markChecked(entity, sourceUrl)
        }
        return file.inputStream().bufferedReader().use { decode(it, Charsets.UTF_8, entity, sourceUrl) }
    }

    private suspend fun updateAssets(entity: AbpEntity, sourceUrl: String, forceUpdate: Boolean): AbpUpdateOutcome {
        val assetVersion = readAssetVersion()
        if (!forceUpdate && store.isCurrent(entity.entityId, sourceUrl, assetVersion)) return markChecked(entity, sourceUrl)
        return assetReader().use { decode(it, Charsets.UTF_8, entity, sourceUrl, assetVersion = assetVersion) }
    }

    private suspend fun markChecked(entity: AbpEntity, sourceUrl: String): AbpUpdateOutcome =
        if (dao.markChecked(entity.entityId, sourceUrl, System.currentTimeMillis()) > 0) AbpUpdateOutcome.NotModified else AbpUpdateOutcome.Skipped

    private fun isBuiltIn(sourceUrl: String) = BrowserSchemeCompat.normalizeUrl(sourceUrl) == "citrus://adblock/filter"

    private fun readAssetVersion() = assetReader().useLines { lines ->
        lines.mapNotNull { ASSET_VERSION.matchEntire(it)?.groupValues?.get(1) }.firstOrNull()
    }

    private suspend fun decode(reader: BufferedReader, charset: Charset, entity: AbpEntity,
                               sourceUrl: String, lastModified: String? = null, assetVersion: String? = null,
                               includeBaseUrl: String = sourceUrl): AbpUpdateOutcome {
        val decoder = AbpFilterDecoder()
        // readLine による巨大な先頭行の確保より先に、本文全体の上限を検査する。
        val boundedReader = AbpFilterPreprocessor.readBounded(reader).reader().buffered()
        if (!decoder.checkHeader(boundedReader, charset)) return AbpUpdateOutcome.PermanentFailure
        val expanded = AbpFilterPreprocessor(::fetchInclude).process(boundedReader.readText(), includeBaseUrl)
        val set = decoder.decode(expanded.text.reader().buffered(), sourceUrl)
        // 親の304だけでは子の更新を証明できないため、include購読は次回も本文を取得する。
        val storedLastModified = if (expanded.hasIncludes) null else lastModified
        currentCoroutineContext().ensureActive()
        val info = set.filterInfo
        val current = dao.getById(entity.entityId)
        if (current?.url != sourceUrl || !current.enabled) return AbpUpdateOutcome.Skipped
        currentCoroutineContext().ensureActive()
        // OS による停止要求は公開前に受け入れ、公開後の最小区間は DB の確定まで完了する。
        return withContext(NonCancellable) {
            store.beginUpdate(entity.entityId, sourceUrl, set, assetVersion).use { publication ->
                try {
                    if (dao.updateMetadata(entity.entityId, sourceUrl, info.title, info.lastUpdate, System.currentTimeMillis(),
                            info.expires ?: -1, info.version, info.homePage, storedLastModified) == 0) {
                        // 無効化では旧束を保持し、削除・URL 変更では自己所有の新束だけを破棄する。
                        if (dao.getById(entity.entityId)?.url == sourceUrl) publication.rollback() else publication.discard()
                        AbpUpdateOutcome.Skipped
                    } else AbpUpdateOutcome.Changed
                } catch (error: Exception) {
                    try { publication.rollback() } catch (rollback: Exception) { error.addSuppressed(rollback) }
                    throw error
                }
            }
        }
    }

    private fun copyMetadata(current: AbpEntity, entity: AbpEntity) {
        entity.title = current.title
        entity.expires = current.expires
        entity.homePage = current.homePage
        entity.version = current.version
        entity.lastUpdate = current.lastUpdate
        entity.lastLocalUpdate = current.lastLocalUpdate
        entity.lastModified = current.lastModified
        entity.enabled = current.enabled
    }

    companion object {
        internal const val AN_HOUR = 60 * 60 * 1000L
        private const val A_DAY = 24 * AN_HOUR
        private val updateMutex = Mutex()
        private val ASSET_VERSION = Regex("^!\\s*Version\\s*:\\s*(.*?)\\s*$", RegexOption.IGNORE_CASE)

        private fun interval(entity: AbpEntity) = if (entity.expires > 0) entity.expires * AN_HOUR else A_DAY

        internal fun createInstance(context: Context): AbpUpdateService {
            val appContext = context.applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(appContext, AbpUpdateEntryPoint::class.java)
            return AbpUpdateService(appContext, entryPoint.okHttpClient(), entryPoint.abpDatabase().abpDao())
        }

        fun updateAll(context: Context, forceUpdate: Boolean = false): UUID? =
            if (forceUpdate) AbpUpdateScheduler.manual(context) else {
                AbpUpdateScheduler.checkNow(context)
                null
            }

        fun update(context: Context, abpEntity: AbpEntity): UUID = AbpUpdateScheduler.manual(context, abpEntity.entityId)
    }
}
