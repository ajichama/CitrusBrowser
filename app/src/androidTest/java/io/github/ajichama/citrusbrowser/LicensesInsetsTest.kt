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

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ajichama.citrusbrowser.legacy.licenses.LicensesActivity
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** 本体のライセンス本文を実WebViewで読み、実際の表示領域を検証する。 */
@RunWith(AndroidJUnit4::class)
class LicensesInsetsTest {
    @Test
    fun licenseWebViewFitsBetweenTheActionBarAndSystemBars() {
        ActivityScenario.launch(LicensesActivity::class.java).use { scenario ->
            val snapshot = awaitLoadedLayout(scenario)
            val safeArea = Rect(snapshot.window).apply {
                left += snapshot.insets.left
                top += snapshot.insets.top
                right -= snapshot.insets.right
                bottom -= snapshot.insets.bottom
            }
            assertTrue("ライセンス画面の ActionBar が表示されていません", snapshot.actionBarVisible)
            assertTrue("WebView がシステムバーまたはノッチに重なっています: $snapshot", safeArea.contains(snapshot.web))
            assertTrue("WebView が ActionBar に重なっています: $snapshot", snapshot.web.top >= snapshot.actionBar.bottom)
            assertTrue("WebView の表示領域がありません: $snapshot", snapshot.web.width() > 0 && snapshot.web.height() > 0)
        }
    }

    private fun awaitLoadedLayout(scenario: ActivityScenario<LicensesActivity>): LayoutSnapshot {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            var snapshot: LayoutSnapshot? = null
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val web = findWebView(activity.findViewById(android.R.id.content))
                val actionBar = activity.findViewById<View>(androidx.appcompat.R.id.action_bar_container)
                val rootInsets = ViewCompat.getRootWindowInsets(decor)
                // 読込先や WebViewClient を置換せず、本体が生成した HTML のレイアウトを待つ。
                if (web != null && actionBar != null && rootInsets != null &&
                    web.progress == 100 && web.contentHeight > 0 && web.width > 0 && web.height > 0
                ) {
                    snapshot = LayoutSnapshot(
                        bounds(decor), bounds(web), bounds(actionBar),
                        rootInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()),
                        activity.supportActionBar?.isShowing == true && actionBar.isShown
                    )
                }
            }
            snapshot?.let { return it }
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("ライセンス本文の読込と表示領域の確定が完了しません")
        error("到達しない分岐")
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    // getGlobalVisibleRect は隠れた領域を切り落とすため、未クリップの View 座標を比較する。
    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private data class LayoutSnapshot(
        val window: Rect,
        val web: Rect,
        val actionBar: Rect,
        val insets: Insets,
        val actionBarVisible: Boolean
    )
}
