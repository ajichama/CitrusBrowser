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

package io.github.ajichama.citrusbrowser.geckopreview

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.ajichama.citrusbrowser.gecko.GeckoEngineHost
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** 本体の設定やタブを読み替えない、実 uBO 内蔵動作の独立プレビュー。 */
class PreviewActivity : Activity(), GeckoEngineHost.Listener {
    private lateinit var host: GeckoEngineHost
    private lateinit var root: LinearLayout
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var back: Button
    private lateinit var forward: Button
    private lateinit var tabButton: Button
    private lateinit var popupButton: Button
    private lateinit var optionsButton: Button
    private lateinit var enabled: Switch
    private lateinit var pageView: GeckoView
    private lateinit var extensionView: GeckoView
    private lateinit var extensionPanel: LinearLayout
    private lateinit var extensionTitle: TextView
    private var updatingControls = false
    private var backCallback: OnBackInvokedCallback? = null
    private var licenseLoadInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 設定変更では Runtime/セッションをそのまま引き継ぎ、フォーム入力を壊さない。
        @Suppress("DEPRECATION")
        host = lastNonConfigurationInstance as? GeckoEngineHost ?: GeckoEngineHost(applicationContext, launchUrl(intent))
        buildContent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = OnBackInvokedCallback { navigateBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        host.attach(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchUrl(intent)?.let { host.openExternalUrl(it) }
    }

    private fun launchUrl(intent: Intent): String? =
        if (intent.action == Intent.ACTION_VIEW) intent.dataString else null

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any = host

    override fun onResume() {
        super.onResume()
        host.setForeground(true)
    }

    override fun onPause() {
        host.setForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        host.detach(this)
        pageView.releaseSession()
        extensionView.releaseSession()
        if (!isChangingConfigurations) host.close()
        super.onDestroy()
    }

    @Deprecated("API 33 以降は OnBackInvokedDispatcher を使う")
    override fun onBackPressed() = navigateBack()

    private fun navigateBack() {
        if (address.hasFocus()) {
            address.clearFocus()
            hideKeyboard()
            return
        }
        val overlay = host.extensionPage
        if (overlay != null) {
            if (overlay.canGoBack) overlay.session.goBack() else host.closeExtensionPage()
            return
        }
        val page = host.selectedPage
        if (page?.canGoBack == true) page.session.goBack() else finish()
    }

    private fun buildContent() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setBackgroundColor(Color.WHITE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else {
            root.fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Citrus Gecko Preview · 内蔵 uBlock Origin"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(dp(12), dp(10), dp(12), dp(4))
            setOnClickListener { showPreviewDetails() }
        }, matchWrap())
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(dp(12), 0, dp(12), dp(4))
            setOnClickListener {
                if (host.extensionState.error != null && !host.extensionState.loading) host.ublock.start()
            }
        }
        root.addView(status, matchWrap())

        val inputRow = row()
        address = EditText(this).apply {
            hint = "URL または検索語"
            contentDescription = "URL または検索語"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 15f
            setTextColor(Color.BLACK)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    submitAddress()
                    true
                } else false
            }
        }
        inputRow.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(button("開く") { submitAddress() })
        root.addView(inputRow, matchWrap())
        val navigation = row()
        back = button("戻る") { (host.extensionPage ?: host.selectedPage)?.session?.goBack() }
        forward = button("進む") { (host.extensionPage ?: host.selectedPage)?.session?.goForward() }
        tabButton = button("タブ") { showTabs() }
        listOf(back, forward, button("更新") { host.reload() }, tabButton).forEach {
            navigation.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        root.addView(navigation, matchWrap())
        val extensionActions = row()
        enabled = Switch(this).apply {
            text = "uBO"
            contentDescription = "内蔵 uBlock Origin の有効状態"
            setTextColor(Color.BLACK)
            setPadding(dp(8), 0, dp(8), 0)
            setOnCheckedChangeListener { _, value ->
                if (!updatingControls) host.ublock.setEnabled(value)
            }
        }
        extensionActions.addView(enabled, LinearLayout.LayoutParams(0, dp(44), 1f))
        popupButton = button("uBO 操作") { host.selectedPage?.let { host.ublock.showPopup(it.session) } }
        optionsButton = button("uBO 設定") { host.ublock.showOptions() }
        extensionActions.addView(popupButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        extensionActions.addView(optionsButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(extensionActions, matchWrap())
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val content = FrameLayout(this)
        pageView = GeckoView(this)
        content.addView(pageView, FrameLayout.LayoutParams(-1, -1))
        extensionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        val header = row()
        extensionTitle = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(dp(12), 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(extensionTitle, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(button("閉じる") { host.closeExtensionPage() })
        extensionPanel.addView(header, matchWrap())
        extensionView = GeckoView(this)
        extensionPanel.addView(extensionView, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(extensionPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
    }

    override fun onHostChanged() {
        val state = host.extensionState
        updatingControls = true
        enabled.isChecked = state.enabled
        enabled.isEnabled = state.ready && !state.loading
        updatingControls = false
        status.text = when {
            state.error != null -> "uBO の準備に失敗しました。タップで再試行: ${state.error}"
            state.loading -> "内蔵 uBlock Origin を準備しています…"
            state.ready && state.enabled -> "uBlock Origin ${state.version.orEmpty()} · 有効"
            state.ready -> "uBlock Origin ${state.version.orEmpty()} · 無効（遮断しません）"
            else -> "内蔵 uBlock Origin の準備待ち"
        }
        address.isEnabled = state.ready
        popupButton.isEnabled = state.ready && state.enabled && !state.loading && host.selectedPage != null
        optionsButton.isEnabled = state.ready && state.enabled && !state.loading
        val page = host.selectedPage
        if (!address.hasFocus() && page != null && address.text.toString() != page.url) address.setText(page.url)
        val visiblePage = host.extensionPage ?: page
        back.isEnabled = visiblePage?.canGoBack == true
        forward.isEnabled = visiblePage?.canGoForward == true
        tabButton.text = "タブ ${host.tabs.size}"
        bind(pageView, page?.session)
        val overlay = host.extensionPage
        bind(extensionView, overlay?.session)
        pageView.visibility = if (overlay == null) View.VISIBLE else View.INVISIBLE
        extensionPanel.visibility = if (overlay == null) View.GONE else View.VISIBLE
        extensionTitle.text = host.overlayTitle
        val currentProgress = (overlay ?: page)?.progress ?: 100
        progress.progress = currentProgress
        progress.visibility = if (currentProgress in 0..99) View.VISIBLE else View.INVISIBLE
    }

    override fun onMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun bind(view: GeckoView, session: GeckoSession?) {
        if (view.session === session) return
        view.releaseSession()
        if (session != null && session.isOpen) view.setSession(session)
    }

    private fun submitAddress() {
        if (!host.extensionState.ready) return
        val raw = address.text.toString().trim()
        if (raw.isEmpty()) return
        val explicitScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw)
        val url = when {
            raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true) -> raw
            raw == "about:blank" || raw == "about:license" -> raw
            explicitScheme -> {
                onMessage("URL は http または https を指定してください。")
                return
            }
            !raw.any(Char::isWhitespace) && (raw.contains('.') || raw.startsWith("localhost", ignoreCase = true)) -> "https://$raw"
            else -> "https://www.google.com/search?q=${URLEncoder.encode(raw, "UTF-8")}"
        }
        address.clearFocus()
        root.requestFocus()
        hideKeyboard()
        host.load(url)
    }

    private fun showTabs() {
        val tabs = host.tabs
        val labels = tabs.mapIndexed { index, page ->
            val mark = if (page === host.selectedPage) "✓ " else ""
            "$mark${index + 1}. ${page.title}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("プレビューのタブ")
            .setItems(labels) { _, which -> host.closeExtensionPage(); host.selectTab(tabs[which]) }
            .setPositiveButton("新しいタブ") { _, _ -> host.closeExtensionPage(); host.newTab() }
            .setNeutralButton("現在のタブを閉じる") { _, _ -> host.selectedPage?.let { host.closePage(it.session) } }
            .setNegativeButton("戻る", null)
            .show()
    }

    private fun showPreviewDetails() {
        AlertDialog.Builder(this)
            .setTitle("独立プレビュー")
            .setMessage("本物の uBlock Origin を内蔵した Gecko の検証版です。CitrusBrowser 本体の設定・タブ・Cookie とは独立しています。\n\n本体のブックマーク、保存・ダウンロード、印刷、ユーザースクリプト、独自ツールバー等の統合は今後の段階です。画面終了後も uBO の設定はこのアプリ内に保持します。")
            .setPositiveButton("閉じる", null)
            .setNeutralButton("ライブラリのライセンス") { _, _ -> showLibraryLicenses() }
            .setNegativeButton("uBO・Gecko 通知") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("ライセンス通知")
                    .setItems(arrayOf("uBlock Origin の通知", "Gecko の通知")) { _, which ->
                        if (which == 0) showUblockNotices() else {
                            host.closeExtensionPage()
                            if (host.newTab("about:license") == null) onMessage("uBO の準備完了後にエンジンの通知を開けます。")
                        }
                    }
                    .setNegativeButton("閉じる", null)
                    .show()
            }
            .show()
    }

    private fun showLibraryLicenses() {
        if (licenseLoadInProgress) return
        licenseLoadInProgress = true
        val appAssets = applicationContext.assets
        Thread({
            val result = runCatching {
                appAssets.open("licenses.compressed").use { readBoundedLicenseGzip(it) }
            }
            runOnUiThread {
                licenseLoadInProgress = false
                if (!isDestroyed && !isFinishing) {
                    result.fold(host::showLibraryLicenses) {
                        onMessage("ライブラリのライセンス通知を読み込めませんでした。")
                    }
                }
            }
        }, "gecko-preview-licenses").apply { isDaemon = true }.start()
    }

    private fun showUblockNotices() {
        val notices = runCatching {
            listOf("ublock-provenance.txt", "ublock/LICENSE.txt").joinToString("\n\n") { path ->
                assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }.getOrElse {
            onMessage("同梱の uBO ライセンス通知を読み込めませんでした。")
            return
        }
        val text = TextView(this).apply {
            this.text = notices
            textSize = 14f
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("uBlock Origin · ライセンス通知")
            .setView(ScrollView(this).apply { addView(text, ViewGroup.LayoutParams(-1, -2)) })
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { action() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /** 展開後 16 MiB を上限とし、壊れた gzip でも通知画面の読込量を無制限にしない。 */
        internal fun readBoundedLicenseGzip(compressed: InputStream, maximumBytes: Int = 16 * 1024 * 1024): ByteArray {
            require(maximumBytes > 0)
            return GZIPInputStream(compressed).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > maximumBytes - output.size()) throw IOException("通知 HTML の展開上限を超えています。")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }
}
