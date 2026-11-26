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

package io.github.ajichama.citrusbrowser.legacy.utils.view.pie

import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.QuickControlEdge.BAND_BOTTOM
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.QuickControlEdge.BAND_MIDDLE
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.QuickControlEdge.BAND_TOP
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.QuickControlEdge.MODE_BAND
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.QuickControlEdge.MODE_INNER
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.QuickControlEdge.POSITION_BOTH
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.QuickControlEdge.POSITION_LEFT
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.QuickControlEdge.SIDE_LEFT
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.QuickControlEdge.SIDE_NONE
import io.github.ajichama.citrusbrowser.legacy.utils.view.pie.QuickControlEdge.SIDE_RIGHT
import org.junit.Assert.assertEquals
import org.junit.Test

/** クイックコントロールを端のどこで開くか（#38） */
class QuickControlEdgeTest {

    // 幅 1000・高さ 2000、端から 20 px で開く。帯の高さは 400 px
    private fun config(
        gesture: Int = 0,
        mode: Int = MODE_BAND,
        band: Int = BAND_MIDDLE,
        position: Int = POSITION_BOTH,
    ) = QuickControlEdge.Config(
        width = 1000, height = 2000, slop = 20, position = position,
        gestureLeft = gesture, gestureRight = gesture, mode = mode, band = band, bandHeight = 400,
    )

    @Test
    fun threeButtonNavigationOpensAlongWholeEdge() {
        val c = config(gesture = 0)
        assertEquals(SIDE_LEFT, QuickControlEdge.hitSide(c, 5, 50))
        assertEquals(SIDE_RIGHT, QuickControlEdge.hitSide(c, 995, 1950))
        assertEquals(SIDE_NONE, QuickControlEdge.hitSide(c, 30, 1000))
        assertEquals(emptyList<Int>(), QuickControlEdge.excludedSides(c))
    }

    @Test
    fun bandModeOpensOnlyInsideBand() {
        val c = config(gesture = 60, mode = MODE_BAND, band = BAND_MIDDLE)
        assertEquals(QuickControlEdge.Span(800, 1200), QuickControlEdge.bandSpan(c))
        assertEquals(SIDE_LEFT, QuickControlEdge.hitSide(c, 5, 800))
        assertEquals(SIDE_RIGHT, QuickControlEdge.hitSide(c, 995, 1199))
        assertEquals(SIDE_NONE, QuickControlEdge.hitSide(c, 5, 799))
        assertEquals(SIDE_NONE, QuickControlEdge.hitSide(c, 5, 1200))
        assertEquals(listOf(SIDE_LEFT, SIDE_RIGHT), QuickControlEdge.excludedSides(c))
    }

    @Test
    fun bandPositionsAreUpperMiddleLower() {
        assertEquals(QuickControlEdge.Span(400, 800), QuickControlEdge.bandSpan(config(gesture = 60, band = BAND_TOP)))
        assertEquals(QuickControlEdge.Span(1200, 1600), QuickControlEdge.bandSpan(config(gesture = 60, band = BAND_BOTTOM)))
    }

    @Test
    fun bandCoversWholeHeightWhenViewIsShort() {
        val c = config(gesture = 60).copy(height = 300)
        assertEquals(QuickControlEdge.Span(0, 300), QuickControlEdge.bandSpan(c))
    }

    @Test
    fun innerModeWidensByGestureWidthWithoutExclusion() {
        val c = config(gesture = 60, mode = MODE_INNER)
        assertEquals(SIDE_LEFT, QuickControlEdge.hitSide(c, 70, 50))
        assertEquals(SIDE_NONE, QuickControlEdge.hitSide(c, 80, 50))
        assertEquals(SIDE_RIGHT, QuickControlEdge.hitSide(c, 930, 1950))
        assertEquals(emptyList<Int>(), QuickControlEdge.excludedSides(c))
    }

    @Test
    fun positionSettingLimitsSideAndExclusion() {
        val c = config(gesture = 60, position = POSITION_LEFT)
        assertEquals(SIDE_NONE, QuickControlEdge.hitSide(c, 995, 1000))
        assertEquals(SIDE_LEFT, QuickControlEdge.hitSide(c, 5, 1000))
        assertEquals(listOf(SIDE_LEFT), QuickControlEdge.excludedSides(c))
    }
}
