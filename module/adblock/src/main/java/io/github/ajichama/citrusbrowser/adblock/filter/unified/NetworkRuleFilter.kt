/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.ajichama.citrusbrowser.adblock.filter.unified

import android.net.Uri
import io.github.ajichama.citrusbrowser.adblock.core.ContentRequest

/** 分割された通信・要素非表示制御にも元のルールの識別子を残す。 */
internal class NetworkRuleFilter(
    private val delegate: UnifiedFilter,
    val ruleKey: String,
    flags: Int,
    /** DomainMap で表せない追加条件（entity の $domain、denyallow、to、method）。 */
    val constraints: NetworkConstraints? = null,
) : UnifiedFilter(delegate.pattern, delegate.contentType or flags, delegate.ignoreCase, delegate.domains, delegate.thirdParty) {
    override val filterType: Int get() = delegate.filterType
    override val isRegex: Boolean get() = delegate.isRegex
    override fun check(url: Uri): Boolean = delegate.check(url)

    // v2 のレコード形式を保ち、専用レコードの文字列欄へ識別子と実際のパターンを保存する。
    // 追加条件がある場合だけ、識別子とパターンの間に条件の欄を挟む（旧形式は3欄のまま読める）。
    val storedPattern: String get() = constraints?.let { "$filterType\u0000$ruleKey\u0000${it.encode()}\u0000$pattern" }
        ?: "$filterType\u0000$ruleKey\u0000$pattern"

    override fun isMatch(request: ContentRequest): Boolean =
        super.isMatch(request) && (constraints?.matches(request) ?: true)

    override fun equals(other: Any?): Boolean = other is NetworkRuleFilter &&
        super.equals(other) && ruleKey == other.ruleKey && constraints == other.constraints

    override fun hashCode(): Int = 31 * (31 * super.hashCode() + ruleKey.hashCode()) + (constraints?.hashCode() ?: 0)
}

/** 同じ元ルールの全カテゴリを、全有効購読にまたがって取り除く。 */
internal fun withoutBadFilters(filters: List<UnifiedFilter>, disabledKeys: Set<String>): List<UnifiedFilter> =
    filters.filter { filter ->
        filter.contentType and ContentRequest.FLAG_BADFILTER == 0 &&
            (filter !is NetworkRuleFilter || filter.ruleKey !in disabledKeys)
    }

internal fun disabledRuleKeys(vararg categories: List<UnifiedFilter>): Set<String> =
    categories.asSequence().flatten().filterIsInstance<NetworkRuleFilter>()
        .filter { it.contentType and ContentRequest.FLAG_BADFILTER != 0 }
        .map { it.ruleKey }.toSet()
