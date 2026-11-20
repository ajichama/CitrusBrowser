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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDefaultSubscriptions
import io.github.ajichama.citrusbrowser.core.utility.log.ErrorReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 標準 WorkerFactory の constructor を保ち、依存は実行時に Hilt EntryPoint から取得する。 */
class AbpUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val manual = inputData.getBoolean(AbpUpdateScheduler.KEY_MANUAL, false)
        try {
            val dispatch = inputData.getBoolean(AbpUpdateScheduler.KEY_DISPATCH, false)
            val id = inputData.getInt(AbpUpdateScheduler.KEY_ID, 0)
            val requestedAt = inputData.getLong(AbpUpdateScheduler.KEY_REQUESTED_AT, 0)
            if (!dispatch && ((id != AbpUpdateScheduler.ALL && id <= 0) || requestedAt <= 0)) return@withContext Result.failure()
            // 自動更新が無効なら、移行や Hilt の依存解決を行わずに終了する。
            if (!manual && !AbpUpdateScheduler.automaticEnabled(applicationContext)) {
                return@withContext complete(AbpUpdateOutcome.Skipped, manual)
            }
            if (dispatch || (manual && id == AbpUpdateScheduler.ALL)) {
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext, AbpUpdateService.AbpUpdateEntryPoint::class.java,
                )
                // 有効化後の dispatch または手動全件更新で、未登録の既定購読を追加する。
                AbpDefaultSubscriptions.migrate(applicationContext, entryPoint.abpDatabase())
            }
            val updater = AbpUpdateService.createInstance(applicationContext)
            if (dispatch) {
                // dispatch の完了前に全要求を DB へ登録し、プロセス終了で取りこぼさない。
                updater.dueEntities().forEach { AbpUpdateScheduler.automatic(applicationContext, it) }
                return@withContext Result.success()
            }
            if (!manual && !AbpUpdateScheduler.wifiAllowed(applicationContext)) return@withContext Result.retry()
            val ids = if (id == AbpUpdateScheduler.ALL) updater.entityIds() else listOf(id)
            val outcomes = ids.map { updater.updateEntity(it, forceUpdate = manual, requestedAt, automatic = !manual) }
            complete(AbpUpdateOutcome.aggregate(outcomes), manual)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            ErrorReport.printAndWriteLog(error)
            complete(AbpUpdateOutcome.RetryableFailure, manual)
        } catch (error: Exception) {
            ErrorReport.printAndWriteLog(error)
            complete(AbpUpdateOutcome.PermanentFailure, manual)
        }
    }

    private fun complete(outcome: AbpUpdateOutcome, manual: Boolean): Result {
        val output = workDataOf(AbpUpdateScheduler.KEY_OUTCOME to outcome.name)
        return when (outcome) {
            AbpUpdateOutcome.RetryableFailure -> if (!manual || runAttemptCount < 4) Result.retry() else Result.failure(output)
            AbpUpdateOutcome.PermanentFailure -> Result.failure(output)
            else -> Result.success(output)
        }
    }
}
