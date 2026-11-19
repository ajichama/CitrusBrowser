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

package io.github.ajichama.citrusbrowser.adblock.service

/**
 * 購読の配布元が一時的に使えないときに使う、同じ内容の公式の予備。
 * 利用者が追加した任意の URL には予備を作らない（既知の配布元だけ）。
 */
internal object AbpListMirrors {
    // AdGuard のフィルタは、配布用 CDN と同じものを AdGuard 公式の FiltersRegistry（GitHub）でも公開している。
    private val adGuard = Regex("^https://filters\\.adtidy\\.org/extension/ublock/filters/([0-9A-Za-z_]+\\.txt)$")
    private const val ADGUARD_MIRROR = "https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/platforms/extension/ublock/filters/"

    fun of(url: String): List<String> {
        adGuard.matchEntire(url)?.let { return listOf(ADGUARD_MIRROR + it.groupValues[1]) }
        return emptyList()
    }
}
