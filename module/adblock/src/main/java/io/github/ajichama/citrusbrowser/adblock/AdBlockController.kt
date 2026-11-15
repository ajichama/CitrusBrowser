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

package io.github.ajichama.citrusbrowser.adblock

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import io.github.ajichama.citrusbrowser.adblock.core.*
import io.github.ajichama.citrusbrowser.adblock.filter.ContentFilter
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_ALLOW
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_DENY
import io.github.ajichama.citrusbrowser.adblock.filter.abp.ABP_PREFIX_DISABLE_ELEMENT_PAGE
import io.github.ajichama.citrusbrowser.adblock.filter.unified.element.ElementContainer
import io.github.ajichama.citrusbrowser.adblock.filter.unified.getFilterDir
import io.github.ajichama.citrusbrowser.adblock.repository.abp.AbpDao
import io.github.ajichama.citrusbrowser.adblock.repository.original.AdBlockManager
import io.github.ajichama.citrusbrowser.core.utility.extensions.getNoCacheResponse
import io.github.ajichama.citrusbrowser.core.utility.utils.IOUtils
import io.github.ajichama.citrusbrowser.core.utility.utils.getMimeType
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayInputStream

class AdBlockController(private val context: Context, private val abpDao: AbpDao) {
    private val dummyImage: ByteArray = IOUtils.readByte(context.resources.assets.open("blank.png"))
    private val dummy = WebResourceResponse("text/plain", "UTF-8", EmptyInputStream())

    private val manager: AdBlockManager = AdBlockManager(context)
    @Volatile private var adBlocker: Blocker? = null
    @Volatile private var elementBlocker: CosmeticFiltering? = null
    private val loadGate = AdBlockLoadGate()

    init {
        update()
    }

    fun update() {
        val ticket = loadGate.begin()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val loaded = AbpLoader(context.getFilterDir(), abpDao.getAll()).use { abpLoader ->
                    // 子の読込処理がすべて完了・取消されてから ZIP のファイル記述子を閉じる。
                    coroutineScope {
                        val deny = async {
                            FilterContainer().also { abpLoader.loadAll(ABP_PREFIX_DENY).forEach(it::plusAssign) }
                        }
                        val allow = async {
                            FilterContainer().also { abpLoader.loadAll(ABP_PREFIX_ALLOW).forEach(it::plusAssign) }
                        }
                        val allowPage = async {
                            FilterContainer().also {
                                manager.getCachedMatcherList(AdBlockManager.ALLOW_PAGE_TABLE_NAME).forEach(it::plusAssign)
                            }
                        }
                        val cosmetic = if (AppPrefs.isAbpUseElementHide.get()) {
                            val disableCosmetic = async {
                                FilterContainer().also { abpLoader.loadAll(ABP_PREFIX_DISABLE_ELEMENT_PAGE).forEach(it::plusAssign) }
                            }
                            val elementFilter = async {
                                ElementContainer().also { abpLoader.loadAllElementFilter().forEach(it::plusAssign) }
                            }
                            CosmeticFiltering(disableCosmetic.await(), elementFilter.await())
                        } else null
                        Pair(Blocker(allowPage.await(), allow.await(), deny.await()), cosmetic)
                    }
                }
                loadGate.publish(ticket) {
                    adBlocker = loaded.first
                    elementBlocker = loaded.second
                }
            } finally {
                loadGate.finish(ticket)
            }
        }
    }

    fun isWhitePage(pageUrl: Uri): Boolean {
        loadGate.await()
        return adBlocker?.isWhitePage(pageUrl) ?: false
    }

    fun isBlock(contentRequest: ContentRequest): ContentFilter? {
        loadGate.await()
        return adBlocker?.isBlock(contentRequest)
    }

    fun createDummy(uri: Uri): WebResourceResponse {
        val mimeType = getMimeType(uri.toString())
        return if (mimeType.startsWith("image/")) {
            WebResourceResponse("image/png", null, ByteArrayInputStream(dummyImage))
        } else {
            dummy
        }
    }

    fun createMainFrameDummy(context: Context, uri: Uri, pattern: String): WebResourceResponse {
        val builder = StringBuilder("<meta charset=utf-8>" +
            "<meta content=\"width=device-width,initial-scale=1,minimum-scale=1\"name=viewport>" +
            "<style>body{padding:5px 15px;background:#fafafa}body,p{text-align:center}p{margin:20px 0 0}" +
            "pre{margin:5px 0;padding:5px;background:#ddd}</style><title>")
            .append(context.getText(R.string.pref_ad_block))
            .append("</title><p>")
            .append(context.getText(R.string.ad_block_blocked_page))
            .append("<pre>")
            .append(uri)
            .append("</pre><p>")
            .append(context.getText(R.string.ad_block_blocked_filter))
            .append("<pre>")
            .append(pattern)
            .append("</pre>")

        return getNoCacheResponse("text/html", builder)
    }

    fun loadScript(url: Uri): String? {
        val cosmetic = elementBlocker ?: return null

        return cosmetic.loadScript(url, AppPrefs.isAbpIgnoreGenericElement.get())
    }
}
