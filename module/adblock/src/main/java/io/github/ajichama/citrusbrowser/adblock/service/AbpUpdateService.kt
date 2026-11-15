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
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import androidx.core.content.getSystemService
import androidx.core.os.BundleCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.ajichama.citrusbrowser.adblock.BROADCAST_ACTION_UPDATE_AD_BLOCK_DATA
import io.github.ajichama.citrusbrowser.adblock.filter.abp.*
import io.github.ajichama.citrusbrowser.adblock.filter.unified.getFilterDir
import io.github.ajichama.citrusbrowser.adblock.repository.AdBlockPref
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDatabase
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDao
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpFilterStore
import io.github.ajichama.citrusbrowser.core.eventbus.LocalEventBus
import io.github.ajichama.citrusbrowser.core.utility.extensions.isConnectedWifi
import io.github.ajichama.citrusbrowser.core.utility.log.ErrorReport
import io.github.ajichama.citrusbrowser.core.utility.utils.BrowserSchemeCompat
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * 広告ブロック（ABP）フィルタの非同期更新ワーカークラス。
 * 旧世代の JobIntentService を廃止し、Kotlin Coroutines (Dispatchers.IO) と Mutex による直列キューで実行する。
 */
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

    internal suspend fun executeUpdateAll(forceUpdate: Boolean, resultReceiver: ResultReceiver?) {
        var result = false
        var nextUpdateTime = Long.MAX_VALUE
        val now = System.currentTimeMillis()
        val entities = dao.getAll().filter { it.enabled }
        val assetVersion = if (entities.any { isBuiltIn(it.url) }) {
            try { readAssetVersion() } catch (error: IOException) { ErrorReport.printAndWriteLog(error); null }
        } else null
        val rebuild = entities.associate {
            it.entityId to !store.isCurrent(it.entityId, it.url, if (isBuiltIn(it.url)) assetVersion else null)
        }
        val prefs = AdBlockPref.get(appContext)
        fun rebuildDue(entity: AbpEntity) = rebuild[entity.entityId] == true &&
            (rebuildRetryAfter[entity.url] ?: 0L) <= now
        // 旧パーサーの束は通常の更新予定が未来でも再取得する。失敗時は短時間の連続取得を避ける。
        if (!forceUpdate && prefs.abpNextUpdateTime > now && entities.none(::rebuildDue)) return
        entities.forEach {
            if (forceUpdate || it.isNeedUpdate() || rebuildDue(it)) {
                val localResult = updateInternal(it, forceUpdate)
                if (!store.isCurrent(it.entityId, it.url)) {
                    rebuildRetryAfter[it.url] = now + AN_HOUR
                } else {
                    rebuildRetryAfter.remove(it.url)
                }
                result = result or localResult
            }
            val nextTime = maxOf(now + AN_HOUR, it.lastLocalUpdate + if (it.expires > 0) it.expires * AN_HOUR else A_DAY)
            if (nextTime < nextUpdateTime) nextUpdateTime = nextTime
        }

        AdBlockPref.get(appContext).abpNextUpdateTime = if (nextUpdateTime != Long.MAX_VALUE) {
            nextUpdateTime
        } else {
            System.currentTimeMillis() + A_DAY
        }
        if (result) {
            LocalEventBus.getDefault().notify(BROADCAST_ACTION_UPDATE_AD_BLOCK_DATA)
        }
        resultReceiver?.send(RESULT_CODE_UPDATE_ALL, null)
    }

    private suspend fun executeUpdateAbpEntity(entity: AbpEntity, result: ResultReceiver?) {
        if (updateInternal(entity)) {
            result?.send(RESULT_CODE_UPDATED, Bundle().apply { putParcelable(EXTRA_ABP_ENTRY, entity) })
            LocalEventBus.getDefault().notify(BROADCAST_ACTION_UPDATE_AD_BLOCK_DATA)
        } else {
            result?.send(RESULT_CODE_FAILED, Bundle().apply { putParcelable(EXTRA_ABP_ENTRY, entity) })
        }
    }

    internal suspend fun updateInternal(entity: AbpEntity, forceUpdate: Boolean = false): Boolean {
        val sourceUrl = entity.url
        return try {
            when (BrowserSchemeCompat.normalizeScheme(sourceUrl.substringBefore(':', ""))) {
                "citrus" -> if (isBuiltIn(sourceUrl)) updateAssets(entity, sourceUrl, forceUpdate) else false
                "http", "https" -> updateHttp(entity, sourceUrl, forceUpdate)
                "file" -> updateFile(entity, sourceUrl)
                else -> false
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ErrorReport.printAndWriteLog(error)
            false
        }
    }

    private suspend fun updateHttp(entity: AbpEntity, sourceUrl: String, forceUpdate: Boolean): Boolean {
        val request = try {
            Request.Builder()
                .url(sourceUrl)
                .get()
        } catch (e: IllegalArgumentException) {
            return false
        }

        val currentCache = store.isCurrent(entity.entityId, sourceUrl)
        if (!forceUpdate && currentCache) {
            entity.lastModified?.let {
                request.addHeader("If-Modified-Since", it)
            }
        }

        val call = okHttpClient.newCall(request.build())
        call.execute().use { response ->
            if (response.code == 304) {
                if (!currentCache) return false
                val checkedAt = System.currentTimeMillis()
                if (dao.markChecked(entity.entityId, sourceUrl, checkedAt) > 0) entity.lastLocalUpdate = checkedAt
                return false
            }
            if (!response.isSuccessful) return false
            response.body?.run {
                val charset = contentType()?.charset() ?: Charsets.UTF_8
                source().inputStream().bufferedReader(charset).use { reader ->
                    return decode(reader, charset, entity, sourceUrl, response.header("Last-Modified"))
                }
            }
        }
        return false
    }

    private suspend fun updateFile(entity: AbpEntity, sourceUrl: String): Boolean {
        val path = Uri.parse(sourceUrl).path ?: return false
        val file = File(path)
        if (store.isCurrent(entity.entityId, sourceUrl) && file.lastModified() < entity.lastLocalUpdate) return false

        try {
            file.inputStream().bufferedReader().use { reader ->
                return decode(reader, Charsets.UTF_8, entity, sourceUrl)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return false
    }

    private suspend fun updateAssets(entity: AbpEntity, sourceUrl: String, forceUpdate: Boolean): Boolean {
        val assetVersion = readAssetVersion()
        if (!forceUpdate && store.isCurrent(entity.entityId, sourceUrl, assetVersion)) return false
        assetReader().use {
            return decode(it, Charsets.UTF_8, entity, sourceUrl, assetVersion = assetVersion)
        }
    }

    private fun isBuiltIn(sourceUrl: String) = BrowserSchemeCompat.normalizeUrl(sourceUrl) == "citrus://adblock/filter"

    private fun readAssetVersion() = assetReader().useLines { lines ->
        lines.mapNotNull { ASSET_VERSION.matchEntire(it)?.groupValues?.get(1) }.firstOrNull()
    }

    private suspend fun decode(reader: BufferedReader, charset: Charset, entity: AbpEntity,
                               sourceUrl: String, lastModified: String? = null, assetVersion: String? = null): Boolean {
        val decoder = AbpFilterDecoder()
        if (!decoder.checkHeader(reader, charset)) return false

        val set = decoder.decode(reader, sourceUrl)

        val info = set.filterInfo
        if (dao.getById(entity.entityId)?.url != sourceUrl) return false
        store.write(entity.entityId, sourceUrl, set, assetVersion)
        val checkedAt = System.currentTimeMillis()
        if (dao.updateMetadata(entity.entityId, sourceUrl, info.title, info.lastUpdate, checkedAt,
                info.expires ?: -1, info.version, info.homePage, lastModified) == 0) return false
        val current = dao.getById(entity.entityId) ?: return false
        if (current.url != sourceUrl) return false
        entity.title = current.title
        entity.expires = current.expires
        entity.homePage = current.homePage
        entity.version = current.version
        entity.lastUpdate = current.lastUpdate
        entity.lastLocalUpdate = current.lastLocalUpdate
        entity.lastModified = current.lastModified
        entity.enabled = current.enabled
        return true
    }

    companion object {
        private const val EXTRA_ABP_ENTRY = "io.github.ajichama.citrusbrowser.adblock.service.extra.entry"

        private const val RESULT_CODE_UPDATED = 1
        private const val RESULT_CODE_FAILED = 2
        private const val RESULT_CODE_UPDATE_ALL = 3

        private const val AN_HOUR = 60 * 60 * 1000L
        private const val A_DAY = 24 * AN_HOUR

        private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val updateMutex = Mutex()
        private val rebuildRetryAfter = mutableMapOf<String, Long>()
        private val ASSET_VERSION = Regex("^!\\s*Version\\s*:\\s*(.*?)\\s*$", RegexOption.IGNORE_CASE)

        private fun createInstance(context: Context): AbpUpdateService {
            val appContext = context.applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(appContext, AbpUpdateEntryPoint::class.java)
            return AbpUpdateService(appContext, entryPoint.okHttpClient(), entryPoint.abpDatabase().abpDao())
        }

        fun updateAll(context: Context, forceUpdate: Boolean = false, result: UpdateResult? = null) {
            if (!forceUpdate) {
                if (AppPrefs.abpUpdateWifiOnly.get()) {
                    val cm = context.getSystemService<ConnectivityManager>()
                    if (cm?.isConnectedWifi() != true) return
                }
            }

            val updater = createInstance(context)
            updateScope.launch {
                updateMutex.withLock {
                    updater.executeUpdateAll(forceUpdate, result)
                }
            }
        }

        fun update(context: Context, abpEntity: AbpEntity, result: UpdateResult? = null) {
            val updater = createInstance(context)
            updateScope.launch {
                updateMutex.withLock {
                    updater.executeUpdateAbpEntity(abpEntity, result)
                }
            }
        }
    }

    abstract class UpdateResult(handler: Handler?) : ResultReceiver(handler) {

        final override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            when (resultCode) {
                RESULT_CODE_UPDATED -> onUpdated(BundleCompat.getParcelable(resultData!!, EXTRA_ABP_ENTRY, AbpEntity::class.java)!!)
                RESULT_CODE_FAILED -> onFailedUpdate(BundleCompat.getParcelable(resultData!!, EXTRA_ABP_ENTRY, AbpEntity::class.java)!!)
                RESULT_CODE_UPDATE_ALL -> onUpdateAll()
            }
        }

        abstract fun onFailedUpdate(entity: AbpEntity)

        abstract fun onUpdated(entity: AbpEntity)

        abstract fun onUpdateAll()
    }
}
