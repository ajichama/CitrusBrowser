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

package io.github.ajichama.citrusbrowser.adblock.ui.abp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** 保存するのは scope と実際の Work ID だけで、画面や購読 entity は保持しない。 */
internal class AbpWorkViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    val pending = savedState.getStateFlow(KEY, arrayListOf<String>())
    private val submitting = mutableSetOf<Int>()
    private val enqueueErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val errors = enqueueErrors.asSharedFlow()

    fun enqueue(scope: Int, create: suspend () -> UUID): Boolean {
        val scopes = submitting + pending.value.map { it.substringBefore(':').toInt() }
        if (scopes.any { it == ALL || scope == ALL || it == scope }) return false
        submitting += scope
        viewModelScope.launch {
            try { adopt(scope, create()) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { enqueueErrors.tryEmit(Unit) }
            finally { submitting -= scope }
        }
        return true
    }

    fun adopt(scope: Int, id: UUID) {
        val token = "$scope:$id"
        if (id.toString() in (savedState.get<ArrayList<String>>(DELIVERED) ?: emptyList())) return
        if (token !in pending.value) savedState[KEY] = ArrayList(pending.value + token)
    }

    fun finish(token: String): Boolean {
        if (token !in pending.value) return false
        val delivered = savedState.get<ArrayList<String>>(DELIVERED) ?: arrayListOf()
        savedState[DELIVERED] = ArrayList((delivered + token.substringAfter(':')).takeLast(64))
        savedState[KEY] = ArrayList(pending.value - token)
        return true
    }

    companion object {
        private const val KEY = "abp.pending.work"
        private const val DELIVERED = "abp.delivered.work"
        private const val ALL = -1
    }
}
