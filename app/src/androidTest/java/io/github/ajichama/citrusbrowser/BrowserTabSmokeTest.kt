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

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ajichama.citrusbrowser.legacy.action.SingleAction
import io.github.ajichama.citrusbrowser.ui.settings.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 起動とタブの基本の操作（開く・切り替える・閉じる・開き直す）を、実際の操作の経路で確かめる。
 * テスト用に開いたタブだけを使い、既存のタブ・設定は変えない（後始末は補助が行う）。
 */
@RunWith(AndroidJUnit4::class)
class BrowserTabSmokeTest {
    @Test(timeout = 90_000)
    fun opensSwitchesClosesAndRestoresTabs() = withEngineNavigationFixture { fixture ->
        // 開く: 新しいタブは末尾に入り、選ばれた状態になる。
        val first = fixture.newTab()
        val firstPage = fixture.server.page("smoke_first")
        fixture.load(first, firstPage)
        val second = fixture.newTab()
        val secondPage = fixture.server.page("smoke_second")
        fixture.load(second, secondPage)
        assertEquals("新しいタブが選ばれていません", second, fixture.currentId())
        val ids = fixture.ids()
        assertEquals("新しいタブが並びの末尾に入っていません", listOf(first, second), ids.takeLast(2))

        // 切り替える: 左右のタブへ移る操作。
        fixture.run(second, SingleAction.makeInstance(SingleAction.LEFT_TAB))
        fixture.await("左のタブへ移る") { fixture.currentId() == first }
        fixture.run(first, SingleAction.makeInstance(SingleAction.RIGHT_TAB))
        fixture.await("右のタブへ移る") { fixture.currentId() == second }

        // 閉じる: 閉じたタブは並びから消え、隣のタブが選ばれる。
        fixture.run(second, SingleAction.makeInstance(SingleAction.CLOSE_TAB))
        fixture.await("タブを閉じる") { second !in fixture.ids() }
        assertFalse("閉じたタブが選ばれたままです", fixture.currentId() == second)
        assertEquals("閉じたタブ以外の並びが変わりました", ids - second, fixture.ids())

        // 開き直す: 閉じたタブの履歴が有効なら、同じページのタブが戻る（履歴は元の件数に戻る）。
        if (fixture.ui { AppPrefs.save_closed_tab.get() }) {
            val restored = fixture.run(first, SingleAction.makeInstance(SingleAction.RESTORE_TAB))
            assertEquals("開き直したタブは一件です", 1, restored.size)
            val tab = restored.single()
            fixture.await("開き直したタブが同じページを開く") { fixture.ui { fixture.tab(tab).url == secondPage.url } }
            assertEquals("開き直したタブが選ばれていません", tab, fixture.currentId())
        }
        assertTrue("最初に開いたタブが残っていません", first in fixture.ids())
    }
}
