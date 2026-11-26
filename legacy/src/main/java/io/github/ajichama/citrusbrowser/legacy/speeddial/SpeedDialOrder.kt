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

package io.github.ajichama.citrusbrowser.legacy.speeddial

/**
 * スピードダイヤルのページで並べ替えた順番を、保存してある一覧に当てる（#39）。
 *
 * ページは古いまま表示されていることがある（別の画面で足した・消した後など）。
 * そのため、ページから届いた番号のうち、今もあるものだけをその順に並べ、
 * ページに無かったものは、元の順のまま後ろへ続ける。項目を消したり増やしたりはしない。
 */
object SpeedDialOrder {

    /** 「1,3,2」の形の番号の並びを読む。数でないものは無視する */
    fun parseIds(text: String): List<Int> =
        text.split(',').mapNotNull { it.trim().toIntOrNull() }

    /**
     * @param current 保存してある順の一覧
     * @param requested ページで並べ替えた後の番号の順
     * @return 新しい順の一覧。順番が変わらないときは null
     */
    fun <T> reorder(current: List<T>, requested: List<Int>, idOf: (T) -> Int): List<T>? {
        val byId = current.associateBy(idOf)
        val result = ArrayList<T>(current.size)
        val used = HashSet<Int>()
        for (id in requested) {
            val item = byId[id] ?: continue
            if (used.add(id)) result.add(item)
        }
        for (item in current) {
            if (used.add(idOf(item))) result.add(item)
        }
        return if (result.map(idOf) == current.map(idOf)) null else result
    }
}
