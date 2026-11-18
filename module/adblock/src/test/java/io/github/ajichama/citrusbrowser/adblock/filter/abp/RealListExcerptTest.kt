/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.ajichama.citrusbrowser.adblock.filter.abp

import org.junit.Assert.assertEquals
import org.junit.Test

class RealListExcerptTest {
    @Test
    fun easyListKeepsUnsupportedModifiersRejected() = verify("easylist", 48, 5)

    @Test
    fun japaneseListAcceptsSupportedNetworkExtensions() = verify("adguard-jp", 54, 6)

    @Test
    fun ublockListAcceptsNetworkExtensionsWithoutClaimingScriptletSupport() = verify("ubo-filters", 62, 29)

    private fun verify(name: String, expectedLines: Int, expectedDiscarded: Int) {
        val lines = javaClass.getResourceAsStream("/adblock/excerpts/$name.txt")!!.bufferedReader().use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('!') }
        }
        val discarded = lines.count { line ->
            val set = AbpFilterDecoder().decode(line.reader().buffered(), null)
            set.blackList.isEmpty() && set.whiteList.isEmpty() &&
                set.elementDisableFilter.isEmpty() && set.elementList.isEmpty()
        }
        assertEquals("抜粋の行数が変わった場合は出典と比較基準も見直してください", expectedLines, lines.size)
        // 変更前は順に5・15・47行。対応外の制約を落として数値だけ改善しない。
        assertEquals("$name: 対応構文と拒否すべき構文の双方を見直してください", expectedDiscarded, discarded)
        println("EXCERPT $name active=${lines.size} discarded=$discarded")
    }
}
