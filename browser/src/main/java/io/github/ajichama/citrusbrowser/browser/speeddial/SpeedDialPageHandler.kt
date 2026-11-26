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

package io.github.ajichama.citrusbrowser.browser.speeddial

import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.appcompat.app.AlertDialog
import io.github.ajichama.citrusbrowser.core.utility.utils.BrowserSchemeCompat
import io.github.ajichama.citrusbrowser.legacy.R
import io.github.ajichama.citrusbrowser.legacy.browser.BrowserController
import io.github.ajichama.citrusbrowser.legacy.speeddial.SpeedDial
import io.github.ajichama.citrusbrowser.legacy.speeddial.SpeedDialManager
import io.github.ajichama.citrusbrowser.legacy.speeddial.SpeedDialOrder
import io.github.ajichama.citrusbrowser.legacy.speeddial.view.SpeedDialSettingActivity
import io.github.ajichama.citrusbrowser.legacy.utils.WebUtils
import io.github.ajichama.citrusbrowser.legacy.utils.extensions.setClipboardWithToast
import io.github.ajichama.citrusbrowser.legacy.webkit.TabType
import io.github.ajichama.citrusbrowser.webview.CustomWebView
import kotlin.concurrent.thread

/**
 * スピードダイヤルのページで、タイルを長押ししたときの依頼を受ける（#39）。
 *
 * ページの script は、citrus:speeddial/… への移動で依頼を伝える。
 * - `lift`: タイルを持ち上げた（指に振動で知らせる）
 * - `order?ids=3,1,2`: 並べ替えた後の順番を保存する
 * - `menu?id=3`: そのタイルのメニューを出す
 *
 * ほかのページから同じ移動をされても、依頼の元がスピードダイヤルのページでなければ何もしない。
 */
class SpeedDialPageHandler(private val controller: BrowserController) {

    private val activity get() = controller.activity
    private val manager by lazy(LazyThreadSafetyMode.NONE) { SpeedDialManager(activity.applicationContext) }

    /** スピードダイヤルのページからの依頼なら処理して true を返す（移動は取り消す） */
    fun handle(web: CustomWebView, uri: Uri): Boolean {
        val request = parseRequest(uri) ?: return false
        if (!isSpeedDialUrl(web.url)) return true

        when (request.first) {
            "lift" -> web.view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            "order" -> saveOrder(SpeedDialOrder.parseIds(request.second["ids"].orEmpty()))
            "menu" -> request.second["id"]?.toIntOrNull()?.let { showMenu(it) }
        }
        return true
    }

    /**
     * スクリプトが動かないとき（JavaScript を切っているときなど）の長押し。
     * 並べ替えはできないが、同じメニューを出す。
     *
     * @param imageUrl タイルの画像の URL（citrus:speeddial/img/<番号>?…）
     * @param linkUrl タイルのリンク先（画像を出さない設定のとき）
     */
    fun showMenuFromLongPress(imageUrl: String?, linkUrl: String?) {
        val id = imageUrl?.let { IMAGE_ID.find(BrowserSchemeCompat.normalizeUrl(it))?.groupValues?.get(1)?.toIntOrNull() }
        if (id != null) {
            showMenu(id)
            return
        }
        if (linkUrl.isNullOrEmpty()) return
        manager.indexData.firstOrNull { it.url == linkUrl }?.let { showMenu(it.id) }
    }

    private fun saveOrder(ids: List<Int>) {
        if (ids.isEmpty()) return
        thread {
            val current = manager.all
            SpeedDialOrder.reorder(current, ids) { it.id }?.let { manager.updateOrder(it) }
        }
    }

    private fun showMenu(id: Int) {
        val speedDial = manager[id] ?: return
        val url = speedDial.url ?: return
        val items = listOf(
            R.string.open_new to { controller.openInNewTab(url, TabType.WINDOW) },
            R.string.open_bg to { controller.openInBackground(url, TabType.WINDOW) },
            R.string.open_private_tab to { controller.openInNewPrivateTab(url) },
            R.string.speeddial_edit to { edit(speedDial) },
            R.string.delete to { confirmDelete(speedDial) },
            R.string.copy_link_url to { activity.setClipboardWithToast(url) },
            R.string.share_link to { WebUtils.shareWeb(activity, url, speedDial.title) },
        )
        AlertDialog.Builder(activity)
            .setTitle(speedDial.title?.takeIf { it.isNotEmpty() } ?: url)
            .setItems(items.map { activity.getText(it.first) }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun edit(speedDial: SpeedDial) {
        val intent = Intent(activity, SpeedDialSettingActivity::class.java).apply {
            action = SpeedDialSettingActivity.ACTION_EDIT_SPEED_DIAL
            putExtra(SpeedDialSettingActivity.EXTRA_SPEED_DIAL_ID, speedDial.id)
        }
        controller.startActivity(intent, BrowserController.REQUEST_SPEED_DIAL)
    }

    private fun confirmDelete(speedDial: SpeedDial) {
        AlertDialog.Builder(activity)
            .setTitle(speedDial.title?.takeIf { it.isNotEmpty() } ?: speedDial.url)
            .setMessage(R.string.confirm_delete_speedDial)
            .setPositiveButton(R.string.delete) { _, _ ->
                thread {
                    manager.delete(speedDial.id)
                    activity.runOnUiThread { reloadPages(controller) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val PREFIX = "speeddial/"
        private val IMAGE_ID = Regex("^citrus:speeddial/img/(\\d+)", RegexOption.IGNORE_CASE)

        fun isSpeedDialUrl(url: String?): Boolean =
            url != null && BrowserSchemeCompat.normalizeUrl(url).equals("citrus:speeddial", ignoreCase = true)

        /**
         * citrus:speeddial/<依頼>?<引数> を、依頼の名前と引数に分ける。依頼でなければ null。
         * citrus: の URL は階層を持たないので、? より後ろも schemeSpecificPart に入る。
         */
        internal fun parseRequest(uri: Uri): Pair<String, Map<String, String>>? {
            if (!"citrus".equals(BrowserSchemeCompat.normalizeScheme(uri.scheme), ignoreCase = true)) return null
            val part = uri.schemeSpecificPart ?: return null
            if (!part.startsWith(PREFIX, ignoreCase = true)) return null
            val rest = part.substring(PREFIX.length)
            val name = rest.substringBefore('?').lowercase()
            if (name != "lift" && name != "order" && name != "menu") return null
            val query = rest.substringAfter('?', "")
            val params = query.split('&').filter { it.isNotEmpty() }.associate {
                it.substringBefore('=') to it.substringAfter('=', "")
            }
            return name to params
        }

        /** 開いているスピードダイヤルのページを読み直す（項目を変えた・消した後） */
        fun reloadPages(controller: BrowserController) {
            for (tab in controller.tabManager.loadedData) {
                if (isSpeedDialUrl(tab.originalUrl ?: tab.url)) tab.mWebView.reload()
            }
        }
    }
}
