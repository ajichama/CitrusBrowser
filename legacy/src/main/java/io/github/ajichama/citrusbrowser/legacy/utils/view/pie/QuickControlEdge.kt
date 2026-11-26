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

/**
 * クイックコントロールを画面の端のどこで開くかの判定（#38）。
 *
 * ジェスチャーナビゲーションでは、OS が画面の左右の端からのスワイプを「戻る」に使う。
 * そのため、次のどちらかで開く。3ボタンのナビゲーションでは、今までどおり端の縦全体で開く。
 * - 帯: 端の縦の一部（帯）でだけ開き、その帯を OS の「戻る」の対象から外す。
 *   外せる高さは OS が各端で 200dp までに制限している。
 * - 内側: OS のジェスチャーの幅の分だけ内側まで、開く範囲を広げる。
 */
internal object QuickControlEdge {

    const val SIDE_NONE = 0
    const val SIDE_LEFT = 1
    const val SIDE_RIGHT = 2

    /** 反応する位置の設定（qc_position）: 左右・左・右 */
    const val POSITION_BOTH = 0
    const val POSITION_LEFT = 1
    const val POSITION_RIGHT = 2

    /** ジェスチャーナビゲーションでの開き方（qc_gesture_mode） */
    const val MODE_BAND = 0
    const val MODE_INNER = 1

    /** 帯の縦の位置（qc_gesture_band）: 上寄り・中央・下寄り */
    const val BAND_TOP = 0
    const val BAND_MIDDLE = 1
    const val BAND_BOTTOM = 2

    /** OS が端ごとに「戻る」の対象から外せる高さの上限 */
    const val MAX_EXCLUSION_DP = 200

    data class Config(
        val width: Int,
        val height: Int,
        /** 端から何 px までを開く範囲にするか */
        val slop: Int,
        val position: Int,
        /** OS のジェスチャーの左右の幅（3ボタンのナビゲーションでは 0） */
        val gestureLeft: Int,
        val gestureRight: Int,
        val mode: Int,
        val band: Int,
        /** 帯の高さ（px）。MAX_EXCLUSION_DP を px にしたもの */
        val bandHeight: Int,
    ) {
        val isGestureNavigation: Boolean
            get() = gestureLeft > 0 || gestureRight > 0

        val usesBand: Boolean
            get() = isGestureNavigation && mode == MODE_BAND
    }

    /** 縦の範囲（top 以上、bottom 未満） */
    data class Span(val top: Int, val bottom: Int) {
        operator fun contains(y: Int): Boolean = y in top until bottom
    }

    /** 指を置いた位置で開くなら、その側を返す */
    fun hitSide(config: Config, x: Int, y: Int): Int {
        if (config.usesBand && y !in bandSpan(config)) return SIDE_NONE
        val inner = config.isGestureNavigation && config.mode == MODE_INNER
        val leftLimit = config.slop + if (inner) config.gestureLeft else 0
        val rightLimit = config.width - config.slop - if (inner) config.gestureRight else 0
        return when {
            config.position != POSITION_LEFT && x > rightLimit -> SIDE_RIGHT
            config.position != POSITION_RIGHT && x < leftLimit -> SIDE_LEFT
            else -> SIDE_NONE
        }
    }

    /** 帯の縦の範囲。帯の中心を、上寄り 30%・中央 50%・下寄り 70% の高さに置く */
    fun bandSpan(config: Config): Span {
        val height = config.height
        if (height <= config.bandHeight) return Span(0, height)
        val ratio = when (config.band) {
            BAND_TOP -> 0.3f
            BAND_BOTTOM -> 0.7f
            else -> 0.5f
        }
        val top = (height * ratio - config.bandHeight / 2f).toInt()
            .coerceIn(0, height - config.bandHeight)
        return Span(top, top + config.bandHeight)
    }

    /** OS の「戻る」の対象から外す範囲の側。帯で開くときだけ、開く側の端を外す */
    fun excludedSides(config: Config): List<Int> {
        if (!config.usesBand) return emptyList()
        return buildList {
            if (config.position != POSITION_RIGHT) add(SIDE_LEFT)
            if (config.position != POSITION_LEFT) add(SIDE_RIGHT)
        }
    }
}
