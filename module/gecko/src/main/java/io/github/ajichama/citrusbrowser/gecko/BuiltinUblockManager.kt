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

import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.util.IdentityHashMap

/**
 * 公式 XPI の無改造資産を、Gecko の組込み拡張として管理する独立検証用の窓口。
 * 拡張の実装・リスト・storage は uBO 自身が所有し、本体の ABP キャッシュとは混ぜない。
 * この runtime の拡張管理 delegate は、Host と同じ寿命の本インスタンスだけが所有する。
 */
class BuiltinUblockManager(
    private val runtime: GeckoRuntime,
    private val sessionFactory: () -> GeckoSession,
    private val listener: Listener,
) {
    data class State(
        val loading: Boolean = false,
        val ready: Boolean = false,
        val enabled: Boolean = false,
        val version: String? = null,
        val error: String? = null,
    )

    interface Listener {
        fun onStateChanged(state: State)
        fun onOpenExtensionPage(session: GeckoSession, popup: Boolean)
        fun onCloseExtensionPage(session: GeckoSession)
        fun onNewTab(session: GeckoSession, active: Boolean)
        fun onCloseTab(session: GeckoSession)
        /** URL のロードは ALLOW 後に Gecko が行う。ここでは表示と選択だけを更新する。 */
        fun onUpdateTab(session: GeckoSession, url: String?, active: Boolean?)
    }

    var state: State = State()
        private set
    private val controller = runtime.webExtensionController
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = linkedSetOf<GeckoSession>()
    private val actions = IdentityHashMap<GeckoSession, WebExtension.Action>()
    private var extension: WebExtension? = null
    private var extensionBaseUrl: String? = null
    private var optionsPageUrl: String? = null
    private var defaultAction: WebExtension.Action? = null
    private var activeSession: GeckoSession? = null
    private var popupSession: GeckoSession? = null
    private var started = false
    private var closed = false

    private val actionDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(source: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
            if (closed || source.id != EXTENSION_ID) return
            if (session == null) defaultAction = action else if (session in sessions) actions[session] = action
        }

        override fun onTogglePopup(source: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? {
            if (!canUse(source)) return null
            popupSession?.let {
                popupSession = null
                listener.onCloseExtensionPage(it)
                return null
            }
            return createPopup()
        }

        override fun onOpenPopup(source: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? {
            if (!canUse(source)) return null
            popupSession?.takeIf { it in sessions && it.isOpen }?.let { return GeckoResult.fromValue(it) }
            return createPopup()
        }
    }

    private val tabDelegate = object : WebExtension.TabDelegate {
        override fun onNewTab(source: WebExtension, details: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
            if (!canUse(source)) return null
            // 未実装のコンテナを通常タブとして誤って開かない。
            if (!details.cookieStoreId.isNullOrBlank() && details.cookieStoreId != "firefox-default") return null
            val session = sessionFactory()
            attachSession(session)
            val base = extensionBaseUrl
            if (base != null && details.url?.startsWith(base) == true) {
                listener.onOpenExtensionPage(session, false)
            } else {
                listener.onNewTab(session, details.active != false)
            }
            // 要求された URL は Gecko が返却後にロードする。
            return GeckoResult.fromValue(session)
        }

        override fun onOpenOptionsPage(source: WebExtension) {
            if (canUse(source)) showOptions()
        }
    }

    private val sessionTabDelegate = object : WebExtension.SessionTabDelegate {
        override fun onCloseTab(source: WebExtension?, session: GeckoSession): GeckoResult<AllowOrDeny> {
            if (closed || source?.id != EXTENSION_ID || session !in sessions) return GeckoResult.deny()
            listener.onCloseTab(session)
            return GeckoResult.allow()
        }

        override fun onUpdateTab(
            source: WebExtension,
            session: GeckoSession,
            details: WebExtension.UpdateTabDetails,
        ): GeckoResult<AllowOrDeny> {
            if (!canUse(source) || session !in sessions) return GeckoResult.deny()
            listener.onUpdateTab(session, details.url, details.active)
            return GeckoResult.allow()
        }
    }

    private val addonDelegate = object : WebExtensionController.AddonManagerDelegate {
        override fun onReady(source: WebExtension) = receive(source, ready = true)
        override fun onEnabled(source: WebExtension) = receive(source)
        override fun onDisabled(source: WebExtension) = receive(source)
        override fun onInstallationFailed(source: WebExtension?, error: WebExtension.InstallException) {
            if (!closed && source?.id == EXTENSION_ID) fail("組込み uBlock Origin を準備できませんでした。")
        }
    }

    fun start() {
        checkMainThread()
        if (closed || state.loading || (started && state.ready)) return
        started = true
        publish(state.copy(loading = true, error = null))
        controller.setAddonManagerDelegate(addonDelegate)
        // 動的 install の署名検証とは異なる、APK 内の組込み拡張専用 API。
        // 同じ版は再導入せず、ユーザーが保存した無効状態も有効へ戻さない。
        controller.ensureBuiltIn(ASSET_URI, EXTENSION_ID).withHandler(mainHandler).accept(
            { source -> if (source != null) receive(source) else fail("組込み uBlock Origin が見つかりません。") },
            { fail("組込み uBlock Origin を準備できませんでした。") },
        )
    }

    fun attachSession(session: GeckoSession) {
        checkMainThread()
        if (closed) return
        sessions += session
        extension?.let { bindSession(session, it) }
    }

    fun detachSession(session: GeckoSession) {
        checkMainThread()
        if (!sessions.remove(session)) return
        extension?.let { source ->
            session.webExtensionController.setActionDelegate(source, null)
            session.webExtensionController.setTabDelegate(source, null)
        }
        actions.remove(session)
        if (popupSession === session) popupSession = null
        if (activeSession === session) {
            if (session.isOpen) controller.setTabActive(session, false)
            activeSession = null
        }
    }

    fun setActiveSession(session: GeckoSession) {
        checkMainThread()
        if (closed) return
        attachSession(session)
        if (activeSession !== session) activeSession?.takeIf { it.isOpen }?.let { controller.setTabActive(it, false) }
        activeSession = session
        if (session.isOpen) controller.setTabActive(session, true)
    }

    fun setEnabled(enabled: Boolean) {
        checkMainThread()
        val source = extension ?: return
        if (closed || !state.ready || state.loading || state.enabled == enabled) return
        publish(state.copy(loading = true, error = null))
        val result = if (enabled) controller.enable(source, WebExtensionController.EnableSource.USER)
            else controller.disable(source, WebExtensionController.EnableSource.USER)
        result.withHandler(mainHandler).accept(
            { updated -> if (updated != null) receive(updated) else fail("uBlock Origin の状態を変更できませんでした。") },
            { fail("uBlock Origin の状態を変更できませんでした。") },
        )
    }

    fun showPopup(session: GeckoSession) {
        checkMainThread()
        if (closed || !state.ready || !state.enabled || state.loading || session !in sessions) return
        setActiveSession(session)
        val action = actions[session]?.let { specific -> defaultAction?.let(specific::withDefault) ?: specific } ?: defaultAction
        if (action == null) {
            fail("uBlock Origin のパネルはまだ準備できていません。")
        } else {
            action.click()
        }
    }

    fun showOptions() {
        checkMainThread()
        val source = extension ?: return
        if (!canUse(source)) return
        val url = optionsPageUrl
        if (url.isNullOrBlank()) {
            fail("uBlock Origin の設定画面はまだ準備できていません。")
            return
        }
        val session = sessionFactory()
        attachSession(session)
        listener.onOpenExtensionPage(session, false)
        session.loadUri(url)
    }

    /** Host の所有セッションを閉じる前に呼ぶ。拡張の storage・導入状態は消さない。 */
    fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        sessions.toList().forEach(::detachSession)
        extension?.let {
            it.setActionDelegate(null)
            it.setTabDelegate(null)
        }
        controller.setAddonManagerDelegate(null)
        extension = null
        extensionBaseUrl = null
        optionsPageUrl = null
        defaultAction = null
        actions.clear()
        popupSession = null
        activeSession = null
    }

    private fun receive(source: WebExtension, ready: Boolean = false) {
        if (closed || source.id != EXTENSION_ID) return
        if (source.metaData.version != EXTENSION_VERSION) {
            fail("組込み uBlock Origin の版が一致しません。")
            return
        }
        // URL は onReady が先行した場合も保持するが、有効状態は必ず最新 metadata を使う。
        val knownReady = state.ready && state.enabled && extension?.metaData?.version == source.metaData.version
        extension = source
        source.metaData.baseUrl?.takeIf { it.isNotBlank() }?.let { extensionBaseUrl = it }
        source.metaData.optionsPageUrl?.takeIf { it.isNotBlank() }?.let { optionsPageUrl = it }
        val current = source
        current.setActionDelegate(actionDelegate)
        current.setTabDelegate(tabDelegate)
        sessions.toList().forEach { bindSession(it, current) }
        // 保存済み無効状態は起動通知を待たず操作可能にする。有効化時は起動準備を再確認する。
        val isReady = !current.metaData.enabled || ready || knownReady || !current.metaData.baseUrl.isNullOrBlank()
        publish(State(loading = !isReady, ready = isReady, enabled = current.metaData.enabled,
            version = current.metaData.version))
    }

    private fun bindSession(session: GeckoSession, source: WebExtension) {
        session.webExtensionController.setActionDelegate(source, actionDelegate)
        session.webExtensionController.setTabDelegate(source, sessionTabDelegate)
    }

    private fun createPopup(): GeckoResult<GeckoSession> {
        val session = sessionFactory()
        attachSession(session)
        popupSession = session
        listener.onOpenExtensionPage(session, true)
        // Gecko 160 の Action が popup 専用設定と URI を適用する。ここでは二重ロードしない。
        return GeckoResult.fromValue(session)
    }

    private fun canUse(source: WebExtension): Boolean = !closed && source.id == EXTENSION_ID &&
        state.ready && state.enabled && !state.loading

    private fun publish(value: State) {
        if (closed) return
        state = value
        listener.onStateChanged(value)
    }

    private fun fail(message: String) {
        if (!closed) publish(state.copy(loading = false, error = message))
    }

    private fun checkMainThread() = check(Looper.myLooper() === Looper.getMainLooper()) {
        "拡張管理はメインスレッドで呼び出してください。"
    }

    companion object {
        const val EXTENSION_ID = "uBlock0@raymondhill.net"
        const val EXTENSION_VERSION = "1.76.4"
        const val ASSET_URI = "resource://android/assets/ublock/"
    }
}
