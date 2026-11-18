/*
 * Copyright 2020 Hazuki
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

package io.github.ajichama.citrusbrowser.adblock.core

import io.github.ajichama.citrusbrowser.adblock.filter.ContentFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.Tag

class FilterContainer(private val preferredFilters: FilterContainer? = null) {
    private val filters = hashMapOf<String, ContentFilter>()

    operator fun plusAssign(filter: ContentFilter) {
        val key = NetworkFilterTag.best(filter)
        this[key] = filter
    }

    private operator fun set(tag: String, filter: ContentFilter) {
        filter.next = filters[tag]
        filters[tag] = filter
    }

    operator fun get(request: ContentRequest): ContentFilter? {
        preferredFilters?.get(request)?.let { return it }
        Tag.create(request.url.toString()).distinct().forEach {
            var filter = filters[it]
            while (filter != null) {
                if (filter.isMatch(request)) {
                    return filter
                }
                filter = filter.next
            }
        }
        return null
    }
}
