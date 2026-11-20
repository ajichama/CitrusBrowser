/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.ajichama.citrusbrowser.adblock.core

import io.github.ajichama.citrusbrowser.adblock.filter.ContentFilter
import io.github.ajichama.citrusbrowser.adblock.filter.unified.*
import java.util.Locale

/** 通常通信専用。URL 内でも完全な token になると証明できる部分だけ索引化する。 */
internal object NetworkFilterTag {
    private val words = Regex("[a-z0-9%]{3,}")
    private val ignored = setOf("http", "https", "html", "jpg", "png")

    fun best(filter: ContentFilter): String {
        if (filter.isRegex) return ""
        val pattern = filter.pattern.lowercase(Locale.ROOT)
        val startBound = filter.filterType in setOf(FILTER_TYPE_HOST, FILTER_TYPE_START, FILTER_TYPE_START_END)
        val endBound = filter.filterType in setOf(FILTER_TYPE_HOST, FILTER_TYPE_END, FILTER_TYPE_START_END)
        return words.findAll(pattern).filter { word ->
            val start = word.range.first
            val end = word.range.last + 1
            word.value !in ignored &&
                (if (start == 0) startBound else pattern[start - 1] != '*') &&
                (if (end == pattern.length) endBound else pattern[end] != '*')
        }.maxByOrNull { it.value.length }?.value.orEmpty()
    }
}
