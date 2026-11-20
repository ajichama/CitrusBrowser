/*
 * Copyright (C) 2017-2019 Hazuki
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

package io.github.ajichama.citrusbrowser.search.presentation.settings

import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSuggestProviders
import io.github.ajichama.citrusbrowser.search.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.github.ajichama.citrusbrowser.search.domain.usecase.SearchSettingsViewUseCase
import io.github.ajichama.citrusbrowser.search.model.provider.SearchUrl
import io.github.ajichama.citrusbrowser.ui.widget.recycler.RecyclerMenu

internal class SearchSettingsViewModel(
    application: Application,
    private val useCase: SearchSettingsViewUseCase
) : AndroidViewModel(application), RecyclerMenu.OnRecyclerMoveListener {

    val removedItem = MutableLiveData<RemovedItem>()

    val list = MutableLiveData<List<SearchUrl>>()

    val onFabClick = MutableLiveData<Any?>()

    val touchHelperCallback: ItemTouchHelper.Callback
        get() = Touch()

    val size: Int
        get() = useCase.list.size

    fun init() {
        list.value = useCase.list
    }

    fun update(index: Int, url: SearchUrl) {
        useCase[index] = url
        updateList()
    }

    fun add(url: SearchUrl) {
        useCase.add(url)
        updateList()
    }

    fun remove(index: Int) {
        useCase.removeAt(index)
        updateList()
    }

    /** 検索エンジンの一覧と既定の検索 URL を、初回起動時と同じ Citrus の初期値に戻す。 */
    fun resetToDefaults(): Boolean {
        val app = getApplication<Application>()
        val urls = app.resources.getStringArray(R.array.default_search_url)
        val titles = app.resources.getStringArray(R.array.default_search_url_name)
        val colors = app.resources.getIntArray(R.array.default_search_url_color)
        val providers = SearchSuggestProviders(0, 0, ArrayList())
        urls.indices.forEach { providers.add(SearchUrl(titles[it], urls[it], colors[it])) }
        if (!useCase.replaceAll(providers.toSettings())) return false
        AppPrefs.search_url.set(urls.first())
        AppPrefs.commit(app, AppPrefs.search_url)
        removedItem.value = null
        updateList()
        return true
    }

    fun resetRemovedItem() {
        val removed = removedItem.value ?: return

        useCase.add(removed.index, removed.item)
        updateList()
    }

    fun onPause() {
        useCase.commitIfNeed()
    }

    fun onResume() {
        if (useCase.reloadIfClean()) {
            // 外部取り込み前の Undo は、新しい一覧へ適用しない。
            removedItem.value = null
            updateList()
        }
    }

    fun onFabClick() {
        onFabClick.value = null
    }

    override fun onMoveUp(position: Int) {
        useCase.moveUp(position)
        updateList()
    }

    override fun onMoveDown(position: Int) {
        useCase.moveDown(position)
        updateList()
    }

    private fun updateList() {
        list.value = useCase.list
    }

    inner class Touch : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            return if (useCase.list.size > 1) {
                makeFlag(ItemTouchHelper.ACTION_STATE_SWIPE, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) or
                    makeFlag(ItemTouchHelper.ACTION_STATE_DRAG, ItemTouchHelper.DOWN or ItemTouchHelper.UP)
            } else {
                0
            }
        }

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            useCase.move(viewHolder.adapterPosition, target.adapterPosition)
            updateList()
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.adapterPosition
            removedItem.value = RemovedItem(position, useCase.removeAt(position))
            updateList()
        }

    }

    class RemovedItem(val index: Int, val item: SearchUrl)

    class Factory(
        private val application: Application,
        private val useCase: SearchSettingsViewUseCase
    ) : ViewModelProvider.Factory {

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return SearchSettingsViewModel(application, useCase) as T
        }
    }
}
