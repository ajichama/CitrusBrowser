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
import android.content.SharedPreferences
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.work.*
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpEntity
import io.github.ajichama.citrusbrowser.core.utility.extensions.isConnectedWifi
import io.github.ajichama.citrusbrowser.core.utility.log.ErrorReport
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

/** WorkManager の標準初期化を用い、Activity や結果 receiver を保持しない。 */
object AbpUpdateScheduler {
    internal const val KEY_ID = "subscriptionId"
    internal const val KEY_MANUAL = "manual"
    internal const val KEY_REQUESTED_AT = "requestedAt"
    internal const val KEY_DISPATCH = "dispatch"
    internal const val KEY_OUTCOME = "outcome"
    internal const val ALL = -1
    internal const val AUTO_TAG = "citrus.abp.automatic"
    internal const val MANUAL_TAG = "citrus.abp.manual"
    internal const val SCOPE_TAG = "citrus.abp.scope."
    private const val PERIODIC = "citrus.abp.periodic"
    private const val CHECK = "citrus.abp.check"
    private const val WIFI_KEY = "abpUpdateWifiOnly"

    private val schedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferenceMutex = Mutex()
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Synchronized
    fun initialize(context: Context) {
        val app = context.applicationContext
        if (listener == null) {
            listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == WIFI_KEY || key == "ad_block") schedulingScope.launch {
                    try {
                        preferenceMutex.withLock {
                            // 制約変更で待機中の自動要求だけを再調整し、手動要求は取り消さない。
                            WorkManager.getInstance(app).cancelAllWorkByTag(AUTO_TAG).await()
                            enqueueCheck(app)
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { ErrorReport.printAndWriteLog(error) }
                }
            }
            AppPrefs.getPreference(app).registerOnSharedPreferenceChangeListener(listener)
        }
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AbpUpdateWorker>(1, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_DISPATCH to true))
                .build(),
        )
        checkNow(app)
    }

    fun checkNow(context: Context) {
        schedulingScope.launch {
            try { preferenceMutex.withLock { enqueueCheck(context.applicationContext) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { ErrorReport.printAndWriteLog(error) }
        }
    }

    private suspend fun enqueueCheck(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CHECK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AbpUpdateWorker>()
                .setInputData(workDataOf(KEY_DISPATCH to true)).build(),
        ).await()
    }

    fun manual(context: Context, entityId: Int = ALL): UUID {
        val request = request(entityId, manual = true)
        // KEEP で破棄される ID を UI に返さず、手動要求は必ず実際に登録する。
        WorkManager.getInstance(context.applicationContext).enqueue(request)
        return request.id
    }

    internal suspend fun manualAwait(context: Context, entityId: Int = ALL): UUID {
        val request = request(entityId, manual = true)
        WorkManager.getInstance(context.applicationContext).enqueue(request).await()
        return request.id
    }

    internal suspend fun automatic(context: Context, entity: AbpEntity) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "citrus.abp.subscription.${entity.entityId}", ExistingWorkPolicy.KEEP,
            request(entity.entityId, manual = false, network = entity.url.startsWith("http:") || entity.url.startsWith("https:")),
        ).await()
    }

    internal fun request(entityId: Int, manual: Boolean, network: Boolean = false): OneTimeWorkRequest {
        require(entityId == ALL || entityId > 0)
        return OneTimeWorkRequestBuilder<AbpUpdateWorker>()
            .setInputData(workDataOf(KEY_ID to entityId, KEY_MANUAL to manual, KEY_REQUESTED_AT to System.currentTimeMillis()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (network) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply {
                if (manual) { addTag(MANUAL_TAG); addTag("$SCOPE_TAG$entityId") }
                else addTag(AUTO_TAG)
            }
            .build()
    }

    internal fun wifiAllowed(context: Context): Boolean =
        !AppPrefs.getPreference(context).getBoolean(WIFI_KEY, false) ||
            context.getSystemService<ConnectivityManager>()?.isConnectedWifi() == true

    internal fun automaticEnabled(context: Context): Boolean =
        AppPrefs.getPreference(context).getBoolean("ad_block", false)
}
