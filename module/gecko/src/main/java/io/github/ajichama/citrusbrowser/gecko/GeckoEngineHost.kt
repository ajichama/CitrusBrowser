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

package io.github.ajichama.citrusbrowser.gecko

import android.content.Context
import android.net.Uri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.Locale

/**
 * 独立プレビューのセッション所有者。Activity は保持せず、画面再生成時も同じ DOM を使う。
 * 本体の WebView、CookieManager、タブ保存形式には接続しない。
 */
class GeckoEngineHost(context: Context, initialUrl: String? = null) : BuiltinUblockManager.Listener {
    interface Listener {
        fun onHostChanged()
        fun onMessage(message: String)
    }

    class Page internal constructor(val session: GeckoSession) {
        var title: String = "新しいタブ"
            internal set
        var url: String = "about:blank"
            internal set
        var progress: Int = 100
            internal set
        var canGoBack: Boolean = false
            internal set
        var canGoForward: Boolean = false
            internal set
        var crashed: Boolean = false
            internal set
        internal var extensionPage = false
        internal var addonPage = false
    }

    private val preferences = context.applicationContext.getSharedPreferences("gecko_preview", Context.MODE_PRIVATE)
    // 通常は GeckoRuntime.getDefault と同じ。計測が予約した場合だけ使い捨て profile の Runtime になる。
    val runtime: GeckoRuntime = GeckoRuntimeHolder.obtain(context.applicationContext)
    private val pages = linkedMapOf<GeckoSession, Page>()
    private var listener: Listener? = null
    private var foreground = false
    private var closed = false
    private var started = false
    private var pendingExternalUrl = initialUrl?.takeIf(::isExternalUrl)

    val ublock = BuiltinUblockManager(runtime, ::createSession, this)
    var extensionState = BuiltinUblockManager.State()
        private set
    val tabs: List<Page> get() = pages.values.filterNot { it.extensionPage }
    var selectedPage: Page? = null
        private set
    var extensionPage: Page? = null
        private set
    var extensionPopup = false
        private set
    var overlayTitle = "uBlock Origin"
        private set

    fun attach(listener: Listener) {
        this.listener = listener
        listener.onHostChanged()
        if (!started) {
            started = true
            ublock.start()
        }
    }

    fun detach(listener: Listener) {
        if (this.listener === listener) this.listener = null
    }

    fun setForeground(value: Boolean) {
        foreground = value
        updateVisibility()
    }

    /** 拡張から要求されたセッションも同じ所有集合で管理し、終了漏れを防ぐ。 */
    fun createSession(): GeckoSession = makeSession(open = true)

    private fun makeSession(open: Boolean): GeckoSession {
        check(!closed)
        val session = GeckoSession()
        val page = Page(session)
        pages[session] = page
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url != null) page.url = url
                if (!page.extensionPage && session === selectedPage?.session) rememberSelectedPage()
                changed()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                page.canGoBack = canGoBack
                changed()
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                page.canGoForward = canGoForward
                changed()
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val scheme = Uri.parse(request.uri).scheme?.lowercase(Locale.ROOT)
                if (scheme in setOf("http", "https", "about", "moz-extension", "blob", "data")) return null
                listener?.onMessage("このプレビューでは外部アプリへのリンクを開けません。")
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (!extensionState.ready || closed) return null
                // window.open は未 open のセッションを返し、Gecko 自身に関連付けを任せる。
                val child = makeSession(open = false)
                selectPage(pages.getValue(child), remember = false)
                return GeckoResult.fromValue(child)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                listener?.onMessage("ページを読み込めませんでした。")
                return null
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                page.progress = 0
                updateVisibility()
                changed()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                page.progress = progress.coerceIn(0, 100)
                changed()
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                page.progress = 100
                changed()
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                page.title = title?.takeIf { it.isNotBlank() } ?: page.url
                changed()
            }

            override fun onCloseRequest(session: GeckoSession) = closePage(session)

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                // 本体 DL へ誤った WebView Cookie を渡さず、未統合であることを明示して応答を解放する。
                listener?.onMessage("このプレビューにはダウンロード機能がまだ統合されていません。")
                Thread({ runCatching { response.body?.close() } }, "gecko-preview-close-response").start()
            }

            override fun onCrash(session: GeckoSession) {
                page.crashed = true
                changed()
                listener?.onMessage("ページの処理が停止しました。再読み込みで開き直せます。")
            }
        }
        ublock.attachSession(session)
        if (open) session.open(runtime)
        return session
    }

    fun newTab(url: String = "about:blank"): Page? {
        if (!extensionState.ready || closed) return null
        val page = pages.getValue(createSession())
        selectPage(page, remember = false)
        page.session.loadUri(url)
        return page
    }

    fun load(url: String) {
        if (!extensionState.ready || closed) return
        closeExtensionPage()
        val page = selectedPage ?: newTab() ?: return
        if (page.crashed) reopen(page)
        page.session.loadUri(url)
    }

    /** 外部 IPC の入口。拡張ページや javascript/file URL は、内部ナビゲーションと分けて拒否する。 */
    fun openExternalUrl(url: String): Boolean {
        if (closed || !isExternalUrl(url)) return false
        if (extensionState.ready) load(url) else pendingExternalUrl = url
        return true
    }

    fun reload() {
        val page = extensionPage ?: selectedPage ?: return
        if (page.crashed) {
            reopen(page)
            page.session.loadUri(page.url)
        } else {
            page.session.reload()
        }
    }

    private fun reopen(page: Page) {
        if (!page.session.isOpen) page.session.open(runtime)
        page.crashed = false
        updateVisibility()
    }

    /** 利用者のタブ切替。既存ページの URL を次回起動用に保存する。 */
    fun selectTab(page: Page) = selectPage(page, remember = true)

    /**
     * 読込前の新規タブや拡張・window.open 由来の選択では、初期値 about:blank 等で
     * 保存済み URL を上書きしないよう remember=false とし、保存は onLocationChange に任せる。
     */
    private fun selectPage(page: Page, remember: Boolean) {
        if (page.extensionPage || pages[page.session] !== page) return
        selectedPage = page
        if (remember) rememberSelectedPage()
        ublock.setActiveSession(page.session)
        updateVisibility()
        changed()
    }

    fun closeExtensionPage() {
        extensionPage?.let { closePage(it.session) }
    }

    fun closePage(session: GeckoSession) {
        val page = pages.remove(session) ?: return
        ublock.detachSession(session)
        if (extensionPage === page) extensionPage = null
        if (selectedPage === page) {
            selectedPage = tabs.lastOrNull()
            rememberSelectedPage()
            selectedPage?.let { ublock.setActiveSession(it.session) }
        }
        if (session.isOpen) {
            session.setActive(false)
            session.close()
        }
        if (!closed && selectedPage == null && extensionState.ready) newTab()
        updateVisibility()
        changed()
    }

    private fun updateVisibility() {
        pages.forEach { (session, page) ->
            if (session.isOpen) {
                val visible = foreground && (page === extensionPage || (extensionPage == null && page === selectedPage))
                session.setActive(visible)
                session.setFocused(visible)
            }
        }
    }

    override fun onStateChanged(state: BuiltinUblockManager.State) {
        if (closed) return
        extensionState = state
        if (state.ready && !state.enabled && !state.loading && extensionPage?.addonPage == true) closeExtensionPage()
        if (state.ready && selectedPage == null) {
            val url = pendingExternalUrl ?: preferences.getString("last_url", null)?.takeIf(::isExternalUrl) ?: "https://www.google.com/"
            pendingExternalUrl = null
            newTab(url)
        } else if (state.ready) {
            pendingExternalUrl?.let {
                pendingExternalUrl = null
                load(it)
            }
        }
        changed()
    }

    override fun onOpenExtensionPage(session: GeckoSession, popup: Boolean) {
        if (closed) {
            if (session.isOpen) session.close()
            return
        }
        val page = pages[session] ?: return
        page.addonPage = true
        showOverlay(page, popup, if (popup) "uBlock Origin" else "uBlock Origin 設定")
    }

    /** 署名済み APK 内で生成した通知 HTML 専用。外部 Intent の URI 許可範囲には接続しない。 */
    fun showLibraryLicenses(html: ByteArray) {
        if (closed) return
        val page = pages.getValue(createSession())
        showOverlay(page, false, "ライブラリのライセンス")
        page.session.load(GeckoSession.Loader().data(html, "text/html;charset=utf-8"))
    }

    private fun showOverlay(page: Page, popup: Boolean, title: String) {
        extensionPage?.takeIf { it !== page }?.let { closePage(it.session) }
        page.extensionPage = true
        extensionPage = page
        extensionPopup = popup
        overlayTitle = title
        updateVisibility()
        changed()
    }

    override fun onCloseExtensionPage(session: GeckoSession) = closePage(session)

    override fun onNewTab(session: GeckoSession, active: Boolean) {
        val page = pages[session] ?: return
        if (active || selectedPage == null) {
            closeExtensionPage()
            selectPage(page, remember = false)
        } else changed()
    }

    override fun onCloseTab(session: GeckoSession) = closePage(session)

    override fun onUpdateTab(session: GeckoSession, url: String?, active: Boolean?) {
        val page = pages[session] ?: return
        // TabDelegate の ALLOW 後に Gecko が読み込む。ここで loadUri すると二重読込になる。
        if (url != null) page.url = url
        if (active == true) {
            closeExtensionPage()
            selectPage(page, remember = false)
        }
        changed()
    }

    fun close() {
        closed = true
        listener = null
        ublock.close()
        pages.keys.toList().forEach(::closePage)
        // Runtime と拡張 storage はアプリの寿命に従う。画面終了で消去・再インストールしない。
    }

    private fun changed() {
        if (!closed) listener?.onHostChanged()
    }

    private fun rememberSelectedPage() {
        // 選択だけで startup URL を更新する。既存ページの DOM を再読込せず、空白タブも保持する。
        // close() 中は残りのタブを順に閉じるため、別タブの URL で上書きしないよう保存しない。
        if (closed) return
        selectedPage?.url?.takeIf(::isExternalUrl)?.let { preferences.edit().putString("last_url", it).apply() }
    }

    private fun isExternalUrl(url: String): Boolean {
        if (url == "about:blank") return true
        val uri = Uri.parse(url)
        return uri.isHierarchical && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
    }
}
