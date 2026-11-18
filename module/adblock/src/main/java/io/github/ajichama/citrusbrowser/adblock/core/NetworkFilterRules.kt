/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.ajichama.citrusbrowser.adblock.core

import io.github.ajichama.citrusbrowser.adblock.filter.unified.UnifiedFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.disabledRuleKeys
import io.github.ajichama.citrusbrowser.adblock.filter.unified.withoutBadFilters

/** どの購読にある badfilter も、コンテナへ連結する前に全カテゴリへ適用する。 */
internal class NetworkFilterRules(
    denies: List<UnifiedFilter>,
    allows: List<UnifiedFilter>,
    cosmeticControls: List<UnifiedFilter>,
) {
    private val disabled = disabledRuleKeys(denies, allows, cosmeticControls)
    private val blocking = withoutBadFilters(denies, disabled)
    val important = blockingContainer(blocking.filter { it.contentType and ContentRequest.FLAG_IMPORTANT != 0 })
    val deny = blockingContainer(blocking.filter { it.contentType and ContentRequest.FLAG_IMPORTANT == 0 })
    val allow = container(withoutBadFilters(allows, disabled))
    val cosmetic = container(withoutBadFilters(cosmeticControls, disabled))

    private fun blockingContainer(filters: List<UnifiedFilter>): FilterContainer {
        // 各フィルタの next は一度だけ連結する。全候補の走査に増やさず、空資源を先に調べる。
        val (redirects, plain) = filters.partition { it.contentType and ContentRequest.REDIRECT_MASK != 0 }
        val preferred = if (redirects.isEmpty()) null else container(redirects)
        return FilterContainer(preferred).also { target -> plain.forEach(target::plusAssign) }
    }

    private fun container(filters: List<UnifiedFilter>) = FilterContainer().also { container ->
        filters.forEach(container::plusAssign)
    }
}
