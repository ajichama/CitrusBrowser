/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.github.ajichama.citrusbrowser

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ajichama.citrusbrowser.legacy.action.SingleAction
import io.github.ajichama.citrusbrowser.legacy.action.item.GoBackSingleAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 本体の実 ActionExecutor から共通境界を通し、既存のタブ以外で操作結果を確認する。 */
@RunWith(AndroidJUnit4::class)
class WebViewEngineNavigationIntegrationTest {
    @Test(timeout = 90_000)
    fun historyReloadAndBothStopActionsOperateOnTheRealWebView() = withEngineNavigationFixture { fixture ->
        val tab = fixture.newTab()
        val first = fixture.server.page("history_first")
        val second = fixture.server.page("history_second")
        fixture.load(tab, first)
        fixture.load(tab, second)
        fixture.run(tab, backWithoutFallback())
        fixture.awaitPage(tab, first)
        fixture.run(tab, SingleAction.makeInstance(SingleAction.GO_FORWARD))
        fixture.awaitPage(tab, second)
        fixture.reloadAndAwaitOneRequest(tab, second, SingleAction.WEB_RELOAD)
        fixture.reloadAndAwaitOneRequest(tab, second, SingleAction.WEB_RELOAD_STOP)

        for ((name, action) in listOf("stop_explicit" to SingleAction.WEB_STOP, "stop_toggle" to SingleAction.WEB_RELOAD_STOP)) {
            val slow = fixture.server.page(name, gated = true)
            fixture.loadUntilGated(tab, slow)
            val before = fixture.marker(tab)
            assertEquals("停止前の script はまだ応答していません", 0, before.optInt("scriptRuns"))
            fixture.run(tab, SingleAction.makeInstance(action))
            fixture.await("停止操作で実読込が終了") { fixture.ui { !fixture.tab(tab).isInPageLoad } }
            // gate を開いた後も遅れて到着した script が実行されないことを比較する。
            fixture.server.releaseAndAwait(slow)
            Thread.sleep(250)
            val after = fixture.marker(tab)
            assertEquals("停止後も同じ文書です", before.getString("nonce"), after.getString("nonce"))
            assertEquals("停止した応答の script が実行されました", 0, after.optInt("scriptRuns"))
        }
        fixture.load(tab, fixture.server.page("after_stop"))
    }

    @Test(timeout = 90_000)
    fun navigationLockOpensHistoryInOwnedNewTabsAndKeepsTheSourceHistory() = withEngineNavigationFixture { fixture ->
        val source = fixture.newTab()
        val first = fixture.server.page("lock_first")
        val second = fixture.server.page("lock_second")
        fixture.load(source, first)
        fixture.load(source, second)
        val sourceHistory = fixture.ui { fixture.tab(source).mWebView.copyMyBackForwardList().current }
        fixture.ui { fixture.tab(source).isNavLock = true }
        val backwardChild = fixture.runAndExpectOneNewTab(source, backWithoutFallback())
        fixture.awaitPage(backwardChild, first)
        fixture.ui {
            assertTrue(fixture.tab(source).isNavLock)
            assertEquals(sourceHistory, fixture.tab(source).mWebView.copyMyBackForwardList().current)
            assertEquals(second.title, fixture.tab(source).title)
            fixture.tab(source).isNavLock = false
        }
        fixture.run(source, backWithoutFallback())
        fixture.awaitPage(source, first)
        val forwardHistory = fixture.ui { fixture.tab(source).mWebView.copyMyBackForwardList().current }
        fixture.ui { fixture.tab(source).isNavLock = true }
        val forwardChild = fixture.runAndExpectOneNewTab(source, SingleAction.makeInstance(SingleAction.GO_FORWARD))
        fixture.awaitPage(forwardChild, second)
        fixture.ui {
            assertTrue(fixture.tab(source).isNavLock)
            assertEquals(forwardHistory, fixture.tab(source).mWebView.copyMyBackForwardList().current)
            assertEquals(first.title, fixture.tab(source).title)
        }
    }

    @Test(timeout = 60_000)
    fun unavailableBackRunsTheConfiguredFallbackInsteadOfClosingAnExistingTab() = withEngineNavigationFixture { fixture ->
        val tab = fixture.newTab()
        val page = fixture.server.page("fallback")
        fixture.load(tab, page)
        // 消すのは専用タブの履歴だけ。既存の保存操作やユーザー設定は変更しない。
        fixture.ui { fixture.tab(tab).mWebView.clearHistory() }
        fixture.await("専用タブが戻れない状態") { fixture.ui { !fixture.tab(tab).mWebView.canGoBack() } }
        val back = SingleAction.makeInstance(SingleAction.GO_BACK) as GoBackSingleAction
        back.defaultAction.clear()
        back.defaultAction.add(SingleAction.makeInstance(SingleAction.WEB_RELOAD))
        val requests = fixture.server.requests(page)
        val ids = fixture.ui { fixture.ids() }
        fixture.run(tab, back)
        fixture.awaitPage(tab, page, requests + 1)
        assertEquals("fallback の更新は一回です", requests + 1, fixture.server.requests(page))
        fixture.ui { assertEquals("他のタブを閉じていません", ids, fixture.ids()) }
    }

    /** 履歴が作れない負例でも、既定のタブ閉じを実行して閉じた履歴を汚さない。 */
    private fun backWithoutFallback(): GoBackSingleAction =
        (SingleAction.makeInstance(SingleAction.GO_BACK) as GoBackSingleAction).apply { defaultAction.clear() }
}
