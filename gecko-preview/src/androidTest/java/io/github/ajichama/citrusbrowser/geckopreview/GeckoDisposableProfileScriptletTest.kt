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

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ajichama.citrusbrowser.gecko.GeckoRuntimeHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoRuntimeSettings
import java.io.File
import java.util.UUID

/**
 * 使い捨て Gecko profile で、原本 uBO 1.76.4 の scriptlet（set-constant）と procedural（:has-text）を
 * 親文書・同一 origin 子フレームで ON/OFF 比較する計測。
 *
 * - GeckoRuntime はプロセス内で1回しか作れないため、既存計測とは別の `am instrument -e class` で実行する。
 *   同じプロセスで既に Runtime がある場合は失敗させ、利用者の保存済み profile を絶対に使わない。
 * - フィルタは uBO 自身の購読確認ページ（abp:subscribe と同じ asset-viewer）で「購読」を押して取り込む。
 *   private RPC・拡張 storage・原本資産は変更しない。
 * - 新規 profile の初回 cold 起動は、uBO の有効表示ではなく、同梱 EasyList 規則の実遮断で準備完了を判定する。
 */
@RunWith(AndroidJUnit4::class)
class GeckoDisposableProfileScriptletTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test(timeout = 540_000)
    fun disposableProfile_coldStart_subscribedList_appliesScriptletAndProcedural_inTopAndChild() {
        val profile = prepareDisposableProfile()
        reserveDisposableRuntime(profile)
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        val preferences = PreviewPreferenceSnapshot(context)
        var scenario: ActivityScenario<PreviewActivity>? = null
        var primary: Throwable? = null
        try {
            // Gecko の a11y は AccessibilityManager の有効化に追従する。DOM id を view id として受け取る。
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            ScriptletProceduralFixture().use { fixture ->
                val launchedAt = SystemClock.elapsedRealtime()
                val active = ActivityScenario.launch<PreviewActivity>(blankIntent()).also { scenario = it }
                awaitProfileInUse(profile)

                // 有効表示は UI 操作可能の確認だけ。準備完了の証拠には使わない。
                val initialEnabled = awaitEnabledState(active)
                Log.i(TAG, "coldStart: uiReadyMs=${SystemClock.elapsedRealtime() - launchedAt}, initialEnabled=$initialEnabled")
                assertTrue("新規 profile の内蔵 uBO は既定で有効", initialEnabled)

                val cold = awaitFilteringReady(fixture, launchedAt, 180_000, requireFixtureList = false) {
                    navigate(active, it.url)
                }
                assertTrue("購読前は fixture の sentinel 規則が存在しない", cold.fixtureSentinel > 0)

                val base = fixture.page("base")
                navigate(active, base.url)
                val baseReports = frames.associateWith { frame ->
                    fixture.awaitReport(base, frame, 30_000) { it.textChanged && it.ticks >= 20 }
                }
                frames.forEach { frame -> assertRuleAbsent("購読前", baseReports.getValue(frame), frame) }

                subscribe(fixture, active, automation)
                // 購読ページの応答連鎖（applyFilterListSelection→reloadAllFilters）を切らないよう、
                // ページを開いたまま背面のタブで sentinel の遮断を確認してから閉じる。
                val subscribedAt = SystemClock.elapsedRealtime()
                awaitFilteringReady(fixture, subscribedAt, 120_000, requireFixtureList = true) { ready ->
                    active.onActivity { activity -> requireNotNull(activity.host.selectedPage).session.loadUri(ready.url) }
                }
                Log.i(TAG, "subscribedListReadyMs=${SystemClock.elapsedRealtime() - subscribedAt}, listFetches=${fixture.listFetches()}")
                active.onActivity { it.host.closeExtensionPage() }

                val on = fixture.page("on")
                navigate(active, on.url)
                frames.forEach { frame ->
                    val first = fixture.awaitReport(on, frame, 30_000) { it.textChanged }
                    val observed = runCatching {
                        fixture.awaitReport(on, frame, 15_000) { it.textChanged && it.proceduralHidden && it.ticks >= 10 }
                    }.getOrElse { fixture.lastReport(on, frame) ?: first }
                    Log.i(TAG, "on frame=$frame observed=$observed")
                    assertEquals("ON の文書は再読込されない: $frame", first.documentNonce, observed.documentNonce)
                    assertEquals("ON の文書要求は1回: $frame", 1, fixture.pageRequests(on, frame))
                    assertTrue("set-constant がページの最初の読取より前に true を定義する: $frame", observed.initialTrue)
                    assertTrue("set-constant はページの false 再代入後も true を返す: $frame", observed.assignmentStayedTrue)
                    assertTrue("照合文字列が入る前の対象は表示される: $frame", observed.initialProceduralVisible)
                    assertTrue(":has-text が動的に一致した対象を隠す: $frame", observed.proceduralHidden)
                    assertTrue("同じ class の非一致 control は表示される: $frame", observed.controlVisible)
                    assertTrue("通常のページ JS は動作する: $frame", observed.normalJs)
                }

                setEnabled(active, false)
                val off = fixture.page("off")
                navigate(active, off.url)
                frames.forEach { frame ->
                    val report = fixture.awaitReport(off, frame, 30_000) { it.textChanged && it.ticks >= 20 }
                    Log.i(TAG, "off frame=$frame observed=$report")
                    assertEquals("OFF の文書要求は1回: $frame", 1, fixture.pageRequests(off, frame))
                    assertRuleAbsent("uBO 無効", report, frame)
                }
            }
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            cleanup(scenario, preferences, automation, originalFlags, primary)
        }
    }

    /** 購読前・uBO 無効時は、scriptlet も procedural も作用せず、ページ自身は正常に動く。 */
    private fun assertRuleAbsent(phase: String, report: ScriptletProceduralFixture.Report, frame: String) {
        assertFalse("$phase は set-constant が作用しない: $frame", report.initialTrue)
        assertFalse("$phase はページの false 再代入がそのまま残る: $frame", report.assignmentStayedTrue)
        assertTrue("$phase の対象は最初から表示される: $frame", report.initialProceduralVisible)
        assertFalse("$phase は照合文字列の後も対象が隠れない: $frame", report.proceduralHidden)
        assertTrue("$phase の control は表示される: $frame", report.controlVisible)
        assertTrue("$phase の通常 JS は動作する: $frame", report.normalJs)
    }

    /**
     * 準備確認ページを期限内に繰り返し読み、実際の遮断で準備完了を判定する。
     * 正常 script の到達を必須とし、全通信の失敗を遮断と誤認しない。
     */
    private fun awaitFilteringReady(
        fixture: ScriptletProceduralFixture,
        since: Long,
        timeoutMillis: Long,
        requireFixtureList: Boolean,
        open: (ScriptletProceduralFixture.Readiness) -> Unit,
    ): ScriptletProceduralFixture.ReadinessResult {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var attempts = 0
        var last: ScriptletProceduralFixture.ReadinessResult? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            attempts++
            val ready = fixture.readiness()
            open(ready)
            val left = deadline - SystemClock.elapsedRealtime()
            val result = if (left > 0) fixture.awaitReadiness(ready, minOf(left, 20_000)) else null
            if (result != null) {
                last = result
                val ok = result.control > 0 && result.easyListSentinel == 0 && (!requireFixtureList || result.fixtureSentinel == 0)
                if (ok) {
                    Log.i(TAG, "filteringReady: fixtureList=$requireFixtureList, elapsedMs=${SystemClock.elapsedRealtime() - since}, attempts=$attempts")
                    return result
                }
            }
            SystemClock.sleep(500)
        }
        throw AssertionError("フィルタ準備の確認が期限切れです: fixtureList=$requireFixtureList, attempts=$attempts, last=$last")
    }

    /** uBO 標準の購読確認ページを開き、利用者と同じく「購読」ボタンを押す。 */
    private fun subscribe(
        fixture: ScriptletProceduralFixture,
        scenario: ActivityScenario<PreviewActivity>,
        automation: UiAutomation,
    ) {
        var opened = false
        scenario.onActivity { opened = it.host.ublock.showSubscribePage(fixture.listUrl, "Citrus disposable fixture") }
        assertTrue("uBO の購読確認ページを開ける", opened)
        // asset-viewer はリスト本文の取得後に click handler を登録する。取得前の click は無効なので先に待つ。
        val fetchDeadline = SystemClock.elapsedRealtime() + 60_000
        while (fixture.listFetches() == 0) {
            if (SystemClock.elapsedRealtime() > fetchDeadline) throw AssertionError("uBO が fixture リストを取得しませんでした。")
            SystemClock.sleep(100)
        }
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var clicks = 0
        var seen = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val button = findById(automation, "subscribeButton")
            if (button == null) {
                // 押下後は uBO がボタンの節を隠す。押す前の不在は表示待ちとして扱う。
                if (clicks > 0 && seen) {
                    Log.i(TAG, "subscribeClicked: clicks=$clicks")
                    return
                }
            } else {
                seen = true
                if (button.isVisibleToUser && button.isEnabled) {
                    val accepted = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (accepted) clicks++
                }
            }
            SystemClock.sleep(1_000)
        }
        throw AssertionError("購読ボタンの操作が期限切れです: seen=$seen, clicks=$clicks")
    }

    private fun findById(automation: UiAutomation, id: String): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        return find(root) { node ->
            val value = node.viewIdResourceName
            value == id || value?.endsWith(":id/$id") == true
        }
    }

    private fun find(node: AccessibilityNodeInfo, check: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (check(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            find(child, check)?.let { return it }
        }
        return null
    }

    /** アプリ専用 cache 配下に、このテストが作ったと分かる空の profile を用意する。 */
    private fun prepareDisposableProfile(): File {
        val parent = File(context.cacheDir, PROFILE_PARENT).canonicalFile
        check(parent.isDirectory || parent.mkdirs()) { "使い捨て profile の親を作成できません。" }
        // 以前の実行で作った profile（目印つき）だけを片付ける。対象外のファイルには触れない。
        parent.listFiles().orEmpty().filter { candidate ->
            candidate.isDirectory && candidate.name.startsWith(RUN_PREFIX) &&
                candidate.canonicalFile.parentFile == parent && File(candidate, MARKER).isFile
        }.forEach { stale -> check(stale.deleteRecursively()) { "以前の使い捨て profile を片付けられません。" } }
        val profile = File(parent, RUN_PREFIX + UUID.randomUUID().toString().replace("-", ""))
        check(profile.mkdirs()) { "使い捨て profile を作成できません。" }
        check(File(profile, MARKER).createNewFile()) { "使い捨て profile の目印を作成できません。" }
        return profile
    }

    private fun reserveDisposableRuntime(profile: File) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            failure = runCatching {
                // configFilePath("") で /data/local/tmp の debug 設定 YAML を読まず、引数を固定する。
                val settings = GeckoRuntimeSettings.Builder()
                    .arguments(arrayOf("-profile", profile.absolutePath))
                    .configFilePath("")
                    .build()
                GeckoRuntimeHolder.reserveFirstRuntimeSettings(settings)
            }.exceptionOrNull()
        }
        failure?.let {
            throw AssertionError("このプロセスでは既に Gecko が起動済みです。既存計測と分け、-e class で単独実行してください。", it)
        }
    }

    /** Gecko が指定 profile を実際に使い始めたことを、目印以外のファイル生成で確認する。 */
    private fun awaitProfileInUse(profile: File) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val names = profile.list().orEmpty().filter { it != MARKER }
            if (names.isNotEmpty()) {
                Log.i(TAG, "disposableProfileInUse: entries=${names.size}, compatibilityIni=${"compatibility.ini" in names}, " +
                    "prefs=${"prefs.js" in names}, extensionsJson=${"extensions.json" in names}")
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Gecko が使い捨て profile を使用しませんでした。")
    }

    private fun blankIntent() = Intent(context, PreviewActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("about:blank")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun navigate(scenario: ActivityScenario<PreviewActivity>, url: String) {
        scenario.onActivity { activity ->
            val address = requireNotNull(findView(activity.window.decorView, EditText::class.java))
            address.setText(url)
            address.onEditorAction(EditorInfo.IME_ACTION_GO)
        }
    }

    private fun awaitEnabledState(scenario: ActivityScenario<PreviewActivity>, expected: Boolean? = null): Boolean {
        var result: Boolean? = null
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                val toggle = requireNotNull(findView(activity.window.decorView, Switch::class.java))
                if (toggle.isEnabled && (expected == null || toggle.isChecked == expected)) result = toggle.isChecked
            }
            result?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("uBO 操作 UI の状態待機が期限切れです（filter-ready の判定ではありません）。")
    }

    private fun setEnabled(scenario: ActivityScenario<PreviewActivity>, value: Boolean) {
        if (awaitEnabledState(scenario) != value) {
            scenario.onActivity { activity ->
                requireNotNull(findView(activity.window.decorView, Switch::class.java)).performClick()
            }
        }
        assertEquals(value, awaitEnabledState(scenario, value))
    }

    private fun cleanup(
        scenario: ActivityScenario<PreviewActivity>?,
        preferences: PreviewPreferenceSnapshot,
        automation: UiAutomation,
        originalFlags: Int,
        primary: Throwable?,
    ) {
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        // 使い捨て profile 内の uBO 状態は復旧しない。利用者領域の SharedPreferences だけを元へ戻す。
        attempt { scenario?.close() }
        attempt { preferences.restore() }
        attempt { automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags } }
        failure?.let { if (primary != null) primary.addSuppressed(it) else throw it }
    }

    private fun <T : View> findView(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            findView(root.getChildAt(index), type)?.let { return it }
        }
        return null
    }

    /** GeckoEngineHost が書く前回 URL は Gecko profile 外にあるため、既存計測と同じく元へ戻す。 */
    private class PreviewPreferenceSnapshot(context: Context) {
        private val preferences = context.getSharedPreferences("gecko_preview", Context.MODE_PRIVATE)
        private val existed = preferences.contains("last_url")
        private val value = preferences.getString("last_url", null)

        fun restore() {
            val editor = preferences.edit()
            if (existed) editor.putString("last_url", value) else editor.remove("last_url")
            check(editor.commit()) { "プレビューの前回 URL を復旧できませんでした。" }
        }
    }

    private companion object {
        const val TAG = "GeckoDisposableProfile"
        const val PROFILE_PARENT = "citrus-disposable-gecko-profile"
        const val RUN_PREFIX = "run-"
        const val MARKER = ".citrus-disposable-profile"
        val frames = listOf("top", "child")
    }
}
