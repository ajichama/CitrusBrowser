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

package io.github.ajichama.citrusbrowser.legacy.backup

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.search.repository.SearchDefaults
import io.github.ajichama.citrusbrowser.ui.BrowserApplication
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed class PortableImportState {
    data object Idle : PortableImportState()
    data object Preparing : PortableImportState()
    data class Ready(val preview: ImportPreview) : PortableImportState()
    data object Applying : PortableImportState()
    data class Complete(val result: ImportResult) : PortableImportState()
    data class Failed(val applying: Boolean, val recovery: Boolean) : PortableImportState()
}

/** SAF の選択後に private cache へ staging し、画面回転では計画を保持する。 */
internal class PortableImportViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow<PortableImportState>(PortableImportState.Idle)
    val state = mutableState.asStateFlow()
    private var plan: PortableImport? = null

    fun prepare(uri: Uri) {
        if (state.value is PortableImportState.Preparing || state.value is PortableImportState.Applying) return
        cancelPreview()
        mutableState.value = PortableImportState.Preparing
        viewModelScope.launch {
            var candidate: PortableImport? = null
            try {
                withContext(Dispatchers.IO) { candidate = PortableImport.prepare(getApplication(), uri) }
                plan = candidate
                mutableState.value = PortableImportState.Ready(candidate!!.preview)
                candidate = null
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                // 画面には失敗した段階だけを示すので、理由はログに残す（例外の文は固定で、取り込むデータの中身を含まない）。
                Log.w(TAG, "取り込みの準備に失敗しました", error)
                mutableState.value = PortableImportState.Failed(applying = false, recovery = false)
            }
            finally { candidate?.close() }
        }
    }

    fun confirm(selected: Set<ImportCategory>) {
        val prepared = plan ?: return
        if (state.value !is PortableImportState.Ready || selected.isEmpty()) return
        plan = null
        mutableState.value = PortableImportState.Applying
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(Dispatchers.IO) {
                    importMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        // 確認後の適用と rollback は画面終了で途中キャンセルしない。
                        withContext(NonCancellable) {
                            val result = prepared.apply(selected, getApplication<Application>().getString(R.string.legacy_import_folder))
                            val normalized = if (result.restart) runCatching {
                                AppPrefs.load(getApplication())
                                val browser = getApplication<Application>() as? BrowserApplication
                                if (browser != null) SearchDefaults.migrateLegacyDefaults(getApplication(), browser.moshi)
                            }.isSuccess else true
                            result.copy(normalizationWarning = !normalized)
                        }
                    }
                }
                mutableState.value = PortableImportState.Complete(result)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                Log.w(TAG, "取り込みの適用に失敗しました", error)
                mutableState.value = PortableImportState.Failed(applying = true, recovery = prepared.recoveryRequired)
            }
            finally { prepared.close() }
        }
    }

    fun cancelPreview() {
        if (state.value is PortableImportState.Applying || state.value is PortableImportState.Preparing) return
        plan?.close()
        plan = null
        mutableState.value = PortableImportState.Idle
    }

    fun acknowledge() { mutableState.value = PortableImportState.Idle }

    override fun onCleared() { plan?.close(); plan = null; super.onCleared() }

    companion object {
        private const val TAG = "PortableImport"
        private val importMutex = Mutex()
    }
}
