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

import android.net.Uri
import io.github.ajichama.citrusbrowser.adblock.filter.ContentFilter

data class Blocker(
    internal val allowPages: FilterContainer,
    private val allows: FilterContainer,
    private val denys: FilterContainer,
    private val important: FilterContainer = FilterContainer(),
) {

    fun isBlock(request: ContentRequest): ContentFilter? {
        val pageRequest = request.copy(url = request.pageUrl)

        var filter = allowPages[pageRequest]
        if (filter != null) return null

        filter = important[request]
        if (filter != null) {
            if (filter.contentType and ContentRequest.REDIRECT_MASK != 0) return filter
            // redirect は遮断の付加処理なので、別ルールによる important 遮断にも適用する。
            val redirect = denys[request]?.contentType?.and(ContentRequest.REDIRECT_MASK) ?: 0
            return if (redirect != 0) RedirectedBlockFilter(filter, redirect) else filter
        }

        filter = allows[request]
        if (filter != null) return null

        return denys[request]
    }

    fun isWhitePage(url: Uri): Boolean {
        val request = ContentRequest(url, url, ContentRequest.TYPE_DOCUMENT, false)
        return allowPages[request] != null
    }

    private class RedirectedBlockFilter(block: ContentFilter, redirect: Int) : ContentFilter by block {
        override val contentType: Int = block.contentType or redirect
        override var next: ContentFilter? = null
    }
}
