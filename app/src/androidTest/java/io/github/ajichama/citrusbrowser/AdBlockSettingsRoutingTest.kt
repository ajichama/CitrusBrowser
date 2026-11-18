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

package io.github.ajichama.citrusbrowser

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.ajichama.citrusbrowser.adblock.ui.original.AdBlockActivity
import io.github.ajichama.citrusbrowser.browser.BrowserActivity
import io.github.ajichama.citrusbrowser.legacy.settings.activity.MainSettingsActivity
import io.github.ajichama.citrusbrowser.legacy.webkit.TabType
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** 保存値だけでなく、内部リンクから戻った本体の遮断 Controller が更新されることを確認する。 */
@RunWith(AndroidJUnit4::class)
class AdBlockSettingsRoutingTest {
    @Test fun internalSettingsLinksReloadAdBlockStateWithoutRestartingTheBrowser() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = AppPrefs.getPreference(instrumentation.targetContext)
        val hadPreference = preferences.contains("ad_block")
        val originalValue = preferences.getBoolean("ad_block", false)
        var originalLiveValue: Boolean? = null
        var originalControllerValue: Boolean? = null
        var opened: Activity? = null
        var ownTabId: Long? = null
        var originalTabId: Long? = null
        var originalTabIds = emptySet<Long>()
        var preferenceChanged = false
        var failure: Throwable? = null
        val browser = AtomicReference<BrowserActivity>()
        val createdBrowser = AtomicReference<BrowserActivity>()
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is BrowserActivity && stage != Stage.DESTROYED) {
                browser.set(activity)
                if (stage == Stage.CREATED) createdBrowser.compareAndSet(null, activity)
            }
        }

        try {
            // Browser は起動 Intent を消費して空にするため、Intent 一致を要する Scenario は使わない。
            onMain("本体の lifecycle 監視を開始") {
                lifecycle.addLifecycleCallback(callback)
                for (stage in listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)) {
                    val existing = lifecycle.getActivitiesInStage(stage).filterIsInstance<BrowserActivity>()
                        .firstOrNull { !it.isFinishing && !it.isDestroyed }
                    if (existing != null) {
                        browser.set(existing)
                        break
                    }
                }
                instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, BrowserActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            val activity = awaitBrowser(browser)
            onMain("既存の本体状態を記録") {
                originalLiveValue = AppPrefs.ad_block.get()
                originalControllerValue = activity.isEnableAdBlock
                originalTabId = activity.tabManager.currentTabData.id
                originalTabIds = activity.tabManager.indexDataList.map { it.id }.toSet()
            }
            // 永続値の変更はテストスレッドで行い、main の停止時にも先に復旧できるようにする。
            preferenceChanged = true
            assertTrue(preferences.edit().putBoolean("ad_block", false).commit())
            onMain("専用タブを追加") {
                AppPrefs.ad_block.set(false)
                activity.isEnableAdBlock = false
                activity.openInNewTab("about:blank", TabType.WINDOW, true)
                val tab = activity.tabManager.currentTabData
                ownTabId = tab.id
                tab.isNavLock = false
            }

            val routes = listOf(
                Route("citrus:settings", MainSettingsActivity::class.java, true),
                Route("citrus:setting", MainSettingsActivity::class.java, false),
                Route("citrus:adblock", AdBlockActivity::class.java, true),
            )
            for (route in routes) {
                val monitor = instrumentation.addMonitor(route.activity.name, null, false)
                try {
                    onMain("内部リンクを開く: ${route.url}") {
                        val index = activity.indexOf(requireNotNull(ownTabId))
                        assertTrue("専用タブがありません", index >= 0)
                        // 公開ロード入口から実 WebClient を通し、result callback を置き換えない。
                        activity.loadUrl(requireNotNull(activity.getTabOrNull(index)), route.url, false)
                    }
                    opened = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                    assertNotNull("内部リンクで設定画面が開きません: ${route.url}", opened)
                    assertTrue(preferences.edit().putBoolean("ad_block", route.enabled).commit())
                    onMain("設定画面から戻る: ${route.url}") { opened!!.finish() }
                    awaitState(activity, route)
                    opened = null
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
                }
            }
            // UI・idle を待つ前に永続値を復旧する。後段が失敗しても元の失敗原因を隠さない。
            if (preferenceChanged) cleanup {
                val editor = preferences.edit()
                if (hadPreference) editor.putBoolean("ad_block", originalValue) else editor.remove("ad_block")
                assertTrue("元の設定値を復旧できません", editor.commit())
            }
            cleanup { onMain("開いた設定画面を終了") { opened?.takeUnless { it.isDestroyed }?.finish() } }
            val activity = browser.get()
            if (activity != null && originalLiveValue != null) {
                cleanup { awaitBrowser(browser, 5_000) }
                cleanup { onMain("専用タブと本体メモリを復旧") {
                    originalLiveValue?.let { AppPrefs.ad_block.set(it) }
                    if (activity.isDestroyed) return@onMain
                    originalControllerValue?.let { activity.isEnableAdBlock = it }
                    ownTabId?.let { id ->
                        val index = activity.indexOf(id)
                        if (index >= 0) {
                            assertTrue("専用タブを閉じられません", activity.removeTab(index, false, true))
                        }
                    }
                    originalTabId?.let { id ->
                        val index = activity.indexOf(id)
                        if (index >= 0) activity.setCurrentTab(index)
                    }
                    if (originalTabIds.isNotEmpty()) {
                        assertEquals("既存のタブを変更しました", originalTabIds,
                            activity.tabManager.indexDataList.map { it.id }.toSet())
                    }
                    activity.tabManager.saveData()
                } }
            }
            cleanup { onMain("テストが起動した本体だけを終了") {
                createdBrowser.get()?.takeUnless { it.isDestroyed }?.finish()
            } }
            cleanup { onMain("lifecycle 監視を解除") { lifecycle.removeLifecycleCallback(callback) } }
            cleanupFailure?.let { error -> failure?.addSuppressed(error) ?: throw error }
        }
    }

    private fun awaitBrowser(reference: AtomicReference<BrowserActivity>, timeout: Long = 10_000): BrowserActivity {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            val activity = onMain("本体の RESUMED を待機", remaining(deadline)) {
                reference.get()?.takeIf {
                    !it.isFinishing && !it.isDestroyed &&
                        ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(it) == Stage.RESUMED
                }
            }
            if (activity != null) return activity
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("本体の起動が完了しません。設定反映の検証には未到達です")
    }

    private fun awaitState(activity: BrowserActivity, route: Route) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            val reflected = onMain("広告遮断状態を確認: ${route.url}", remaining(deadline)) {
                activity.isEnableAdBlock == route.enabled && AppPrefs.ad_block.get() == route.enabled
            }
            if (reflected) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("設定から戻っても本体の広告遮断状態が更新されません: ${route.url}")
    }

    private fun remaining(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).coerceIn(1, 5_000)

    /** 全体 idle を待たず、未実行のタイムアウト済み操作は後から実行されないよう取り消す。 */
    private fun <T> onMain(label: String, timeout: Long = 5_000, action: () -> T): T {
        val task = FutureTask(Callable { action() })
        check(Handler(Looper.getMainLooper()).post(task)) { "main に操作を登録できません: $label" }
        return try {
            task.get(timeout, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            task.cancel(false)
            throw AssertionError("main の操作が ${timeout}ms で完了しません: $label", error)
        } catch (error: InterruptedException) {
            task.cancel(false)
            throw error
        } catch (error: ExecutionException) {
            throw requireNotNull(error.cause)
        }
    }

    private data class Route(val url: String, val activity: Class<out Activity>, val enabled: Boolean)
}
