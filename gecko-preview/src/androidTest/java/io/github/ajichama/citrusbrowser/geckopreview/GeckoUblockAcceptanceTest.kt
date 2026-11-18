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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 実 uBO 原本と既定リストを使う Android 計測。ensureBuiltIn/onReady 自体を遮断成功としない。
 * リスト取得の外部通信は uBO 自身が行う。fixture の通信と結果回収は端末の loopback のみ。
 * 既存 profile のリスト選択・ユーザー規則は変更しない。規則の由来は初回導入記録とも照合する。
 * MyFilters、procedural、scriptlet、OS プロセス再起動は検証範囲に含めない。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GeckoUblockAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test(timeout = 240_000)
    fun a_knownEasyListRules_firstEnabledNavigation_blocksNetworkAndCosmetic_inTopAndChildFrames() {
        recordProfileBeforeLaunch()
        val preferences = PreviewPreferenceSnapshot(context)
        LoopbackGeckoFixture().use { fixture ->
            var scenario: ActivityScenario<PreviewActivity>? = null
            var originalEnabled: Boolean? = null
            var failure: Throwable? = null
            try {
                val active = launchBlank().also { scenario = it }
                originalEnabled = awaitEnabledState(active)
                Log.i(TAG, "defaultListInitialEnabled=$originalEnabled; filterReadyはfixtureで観測する")
                setEnabled(active, true)
                val on = fixture.page(advertising = true)
                navigate(active, on.url)
                val onReports = listOf("top", "child").associateWith { frame ->
                    // 正常な script と本文の成功を先に必須とする。全通信停止を広告遮断と誤認しない。
                    val first = fixture.awaitReport(on, frame) { it.controlLoaded && it.controlVisible }
                    // CSS は非同期に届き得るが、初回 JS/HTTP の漏れは後から消せないため別に保持する。
                    val observed = runCatching {
                        fixture.awaitReport(on, frame, 10_000) { it.controlLoaded && it.controlVisible && it.cosmeticHidden }
                    }.getOrElse { first }
                    Log.i(TAG, "firstEnabledNavigation frame=$frame, networkHits=${fixture.count(on, "/microad.js", frame)}, observed=$observed")
                    observed
                }
                val onHits = listOf("top", "child").associateWith { fixture.count(on, "/microad.js", it) }

                setEnabled(active, false)
                val off = fixture.page(advertising = true)
                navigate(active, off.url)
                val offReports = listOf("top", "child").associateWith { frame ->
                    fixture.awaitReport(off, frame) {
                        it.controlLoaded && it.controlVisible && it.adLoaded && !it.cosmeticHidden
                    }
                }

                listOf("top", "child").forEach { frame ->
                    val blocked = onReports.getValue(frame)
                    val unblocked = offReports.getValue(frame)
                    assertTrue("ON の正常 script が成功する: $frame", fixture.count(on, "/control.js", frame) > 0)
                    assertEquals("ON の最初の文書で広告 script がサーバーへ漏れない: $frame", 0, onHits.getValue(frame))
                    assertFalse("ON の広告 script は実行されない: $frame", blocked.adLoaded)
                    assertTrue("ON の既定 cosmetic が実際に隠す: $frame", blocked.cosmeticHidden)
                    assertTrue("OFF の広告 script がサーバーへ届く: $frame", fixture.count(off, "/microad.js", frame) > 0)
                    assertTrue("OFF の広告 script は実行される: $frame", unblocked.adLoaded)
                    assertFalse("OFF の cosmetic 対象は見える: $frame", unblocked.cosmeticHidden)
                }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                cleanup(scenario, originalEnabled, preferences, failure)
            }
        }
    }

    @Test(timeout = 240_000)
    fun b_recreationKeepsSessionDom_andSameProcessRelaunchKeepsDisabledState() {
        recordProfileBeforeLaunch()
        val preferences = PreviewPreferenceSnapshot(context)
        LoopbackGeckoFixture().use { fixture ->
            var scenario: ActivityScenario<PreviewActivity>? = null
            var originalEnabled: Boolean? = null
            var failure: Throwable? = null
            try {
                val active = launchBlank().also { scenario = it }
                originalEnabled = awaitEnabledState(active)
                setEnabled(active, false)
                val load = fixture.page(advertising = false)
                navigate(active, load.url)
                val before = fixture.awaitReport(load) { it.controlLoaded && it.controlVisible && it.ticks >= 2 }
                val originalSession = session(active)
                active.recreate()
                assertSame("Activity 再生成で同じ native session を使う", originalSession, session(active))
                val after = fixture.awaitReport(load) { it.documentNonce == before.documentNonce && it.ticks > before.ticks }
                assertEquals("fixture JS が変更した input.value を保持する（IME 操作の試験ではない）", before.input, after.input)
                assertEquals("フォームは初期 HTML の値へ戻らない", "edited-${load.token}-top", after.input)
                assertEquals("DOM の同一性を保持する", before.documentNonce, after.documentNonce)
                assertEquals("文書を再読込しない", 1, fixture.count(load, "/page"))

                // singleTask の通常 IPC を通し、拡張 URL や任意 JavaScript を外から実行させない。
                listOf(
                    "javascript:document.getElementById('fixture-input').value='unsafe-ipc'",
                    "file:///citrus-fixture-rejected",
                    "moz-extension://not-owned/fixture.html",
                ).forEach { rejected ->
                    context.startActivity(blankIntent().setData(Uri.parse(rejected)))
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                }
                val retained = fixture.awaitReport(load) { it.documentNonce == before.documentNonce && it.ticks > after.ticks }
                assertEquals("拒否した IPC は入力内容を変更しない", after.input, retained.input)
                assertSame(originalSession, session(active))
                assertEquals("拒否した IPC は文書を置換しない", 1, fixture.count(load, "/page"))

                val pid = Process.myPid()
                closeScenario(active)
                scenario = null
                val relaunched = launchBlank().also { scenario = it }
                assertFalse("同プロセス内の画面終了・再起動でユーザー無効状態を維持", awaitEnabledState(relaunched))
                assertEquals("OS プロセス再起動の試験ではない", pid, Process.myPid())
                Log.i(TAG, "sameProcessActivityRelaunch=true; OS再起動/force-stopは未検証")
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                cleanup(scenario, originalEnabled, preferences, failure)
            }
        }
    }

    @Test
    fun c_libraryNoticeGzip_preservesUtf8_andRejectsOversizeOrTruncatedData() {
        val expected = "<html><meta charset=utf-8><body>Citrus 日本語</body></html>".toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream().let { output ->
            GZIPOutputStream(output).use { it.write(expected) }
            output.toByteArray()
        }
        assertArrayEquals(expected, PreviewActivity.readBoundedLicenseGzip(ByteArrayInputStream(compressed), expected.size))
        fun rejects(bytes: ByteArray, maximum: Int) {
            try {
                PreviewActivity.readBoundedLicenseGzip(ByteArrayInputStream(bytes), maximum)
                fail("壊れた通知・上限超過を成功扱いしない")
            } catch (_: IOException) {
                // 展開上限と gzip の完全性検査をそれぞれ確認する。
            }
        }
        rejects(compressed, expected.size - 1)
        rejects(compressed.copyOf(compressed.size - 4), expected.size)
    }

    private fun launchBlank(): ActivityScenario<PreviewActivity> = ActivityScenario.launch(blankIntent())

    private fun blankIntent() = Intent(context, PreviewActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse("about:blank")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun navigate(scenario: ActivityScenario<PreviewActivity>, url: String) {
        scenario.onActivity { activity ->
            val address = requireNotNull(find(activity.window.decorView, EditText::class.java))
            address.setText(url)
            address.onEditorAction(EditorInfo.IME_ACTION_GO)
        }
    }

    private fun session(scenario: ActivityScenario<PreviewActivity>): GeckoSession {
        var result: GeckoSession? = null
        scenario.onActivity { activity ->
            result = requireNotNull(find(activity.window.decorView, GeckoView::class.java)).session
        }
        return requireNotNull(result)
    }

    private fun awaitEnabledState(scenario: ActivityScenario<PreviewActivity>, expected: Boolean? = null): Boolean {
        var result: Boolean? = null
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                val toggle = requireNotNull(find(activity.window.decorView, Switch::class.java))
                if (toggle.isEnabled && (expected == null || toggle.isChecked == expected)) result = toggle.isChecked
            }
            result?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("uBO 操作 UI の状態待機が期限切れです（filter-ready の判定ではありません）。")
    }

    private fun setEnabled(scenario: ActivityScenario<PreviewActivity>, value: Boolean) {
        val current = awaitEnabledState(scenario)
        if (current != value) {
            scenario.onActivity { activity ->
                requireNotNull(find(activity.window.decorView, Switch::class.java)).performClick()
            }
        }
        assertEquals(value, awaitEnabledState(scenario, value))
    }

    private fun closeScenario(scenario: ActivityScenario<PreviewActivity>) {
        // singleTask の onNewIntent は Activity.intent を更新する。拒否動作の検証後だけ元へ戻し、
        // Scenario が元の起動 Intent と照合して DESTROYED 通知を受け取れるようにする。
        // ナビゲーションや DOM は変更しない。IPC 拒否・入力保持の assert 自体はこの前に行う。
        scenario.onActivity { it.intent = blankIntent() }
        scenario.close()
    }

    private fun cleanup(
        scenario: ActivityScenario<PreviewActivity>?,
        originalEnabled: Boolean?,
        preferences: PreviewPreferenceSnapshot,
        primaryFailure: Throwable?,
    ) {
        var cleanupFailure: Throwable? = null
        fun attempt(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let { error ->
                cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
            }
        }
        var cleanupScenario = scenario
        // 終了後の再 launch 自体が失敗した場合も、元の有効状態へ戻す機会を一度だけ設ける。
        if (cleanupScenario == null && originalEnabled != null) attempt { cleanupScenario = launchBlank() }
        cleanupScenario?.let { active ->
            attempt { if (originalEnabled != null) setEnabled(active, originalEnabled) }
            attempt { closeScenario(active) }
        }
        attempt { preferences.restore() }
        cleanupFailure?.let { if (primaryFailure != null) primaryFailure.addSuppressed(it) else throw it }
    }

    private fun recordProfileBeforeLaunch() {
        // Gecko の既定保存場所の存在と、この計測内での前回起動を別々に記録する。
        // 存在しない場合も fresh install と断定しない。実 cold は親側の初回導入記録と照合する。
        val filesMozilla = File(context.filesDir, "mozilla").isDirectory
        val appMozilla = File(context.applicationInfo.dataDir, "app_mozilla").isDirectory
        val alreadyLaunched = launchedInInstrumentation.getAndSet(true)
        Log.i(TAG, "profileBeforeLaunch: filesMozilla=$filesMozilla, appMozilla=$appMozilla, priorLaunchInThisInstrumentation=$alreadyLaunched")
    }

    private fun <T : View> find(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            find(root.getChildAt(index), type)?.let { return it }
        }
        return null
    }

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

    companion object {
        private const val TAG = "GeckoUblockAcceptance"
        private val launchedInInstrumentation = AtomicBoolean(false)
    }
}
