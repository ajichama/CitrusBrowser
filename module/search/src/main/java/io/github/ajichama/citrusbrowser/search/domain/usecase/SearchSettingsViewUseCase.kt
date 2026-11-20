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

package io.github.ajichama.citrusbrowser.search.domain.usecase

import io.github.ajichama.citrusbrowser.search.domain.ISearchUrlRepository
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSuggestProviders
import io.github.ajichama.citrusbrowser.search.model.provider.SearchSettings
import io.github.ajichama.citrusbrowser.search.model.provider.SearchUrl
import java.util.Collections.swap

internal class SearchSettingsViewUseCase(
    private val repository: ISearchUrlRepository
) {
    private var modified = false

    private var baseline = repository.load().snapshot()
    private var providers = SearchSuggestProviders(baseline.snapshot())

    val list: List<SearchUrl>
        get() = providers.items

    fun move(fromPosition: Int, toPosition: Int) {
        modified = true
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                swap(providers.items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                swap(providers.items, i, i - 1)
            }
        }
    }

    fun moveUp(index: Int): Boolean {
        if (index <= 0) return false

        modified = true
        swap(providers.items, index, index - 1)
        return true
    }

    fun moveDown(index: Int): Boolean {
        if (index >= providers.items.lastIndex) return false

        modified = true
        swap(providers.items, index, index + 1)
        return true
    }

    operator fun get(index: Int) = providers.items[index]

    operator fun set(index: Int, url: SearchUrl) {
        modified = true
        providers.items[index] = url
    }

    fun add(url: SearchUrl) {
        modified = true
        providers.add(url)
    }

    fun add(index: Int, url: SearchUrl) {
        modified = true
        providers.add(index, url)
    }

    fun removeAt(index: Int): SearchUrl {
        modified = true
        return providers.removeAt(index)
    }

    fun commitIfNeed(): Boolean = !modified || commit()

    fun commit(): Boolean {
        // 保存失敗時は編集状態を残し、再開時に古いディスク値で編集中の一覧を置き換えない。
        // 別画面の取り込みが先に完了していた場合も、古い編集内容を自動で上書きしない。
        if (repository.load() != baseline) return false
        val settings = providers.toSettings()
        if (!repository.save(settings)) return false
        baseline = settings.snapshot()
        modified = false
        return true
    }

    /** 編集中の内容を捨てて、渡した一覧で置き換える（初期値に戻す）。保存できた場合だけ画面の一覧も替える。 */
    fun replaceAll(settings: SearchSettings): Boolean {
        if (!repository.save(settings)) return false
        providers = SearchSuggestProviders(settings.snapshot())
        baseline = settings.snapshot()
        modified = false
        return true
    }

    fun reloadIfClean(): Boolean {
        if (modified) return false
        val latest = repository.load()
        if (latest == providers.toSettings()) return false
        providers = SearchSuggestProviders(latest)
        baseline = latest.snapshot()
        return true
    }

    private fun SearchSettings.snapshot() = copy(items = items.map { it.copy() })
}
