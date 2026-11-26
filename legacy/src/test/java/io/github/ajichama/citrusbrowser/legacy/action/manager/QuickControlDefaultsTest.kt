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

package io.github.ajichama.citrusbrowser.legacy.action.manager

import io.github.ajichama.citrusbrowser.legacy.action.SingleAction
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** クイックコントロールの既定の項目（#36）。決めた中身と順番を保つ。 */
class QuickControlDefaultsTest {

    @Test
    fun level1HasNavigationAndTabList() {
        assertArrayEquals(
            intArrayOf(SingleAction.GO_BACK, SingleAction.GO_FORWARD, SingleAction.WEB_RELOAD_STOP, SingleAction.TAB_LIST),
            QuickControlActionManager.DEFAULT_LEVEL1,
        )
    }

    @Test
    fun level2HasPageEdgesAndTabOperations() {
        assertArrayEquals(
            intArrayOf(SingleAction.PAGE_TOP, SingleAction.PAGE_BOTTOM, SingleAction.NEW_TAB, SingleAction.CLOSE_TAB, SingleAction.RESTORE_TAB),
            QuickControlActionManager.DEFAULT_LEVEL2,
        )
    }
}
