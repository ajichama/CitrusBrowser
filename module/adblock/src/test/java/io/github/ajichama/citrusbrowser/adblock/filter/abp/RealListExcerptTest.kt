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
    fun easyListKeepsUnsupportedModifiersRejected() = verify("easylist", 48, 4)

    @Test
    fun japaneseListAcceptsSupportedNetworkExtensions() = verify("adguard-jp", 54, 1)

    @Test
    fun ublockListAcceptsNetworkExtensionsAndOnlyImplementedScriptlets() = verify("ubo-filters", 62, 6)

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
        // 第3段階で、実装済みの scriptlet（set・aopr・aopw・acs・nostif・aeld）の行を受け付けた（adguard-jp 6→5、ubo-filters 29→16）。
        // 第3段階の第2弾で ra（remove-attr）も受け付けた（ubo-filters 16→15）。未実装の popads-dummy 等の行は引き続き破棄する。
        // procedural（#?#…:has-text、:has(…:contains)、:upward():style()、:style()）を受け付けた（easylist 5→4、adguard-jp 5→3、
        // ubo-filters 15→14）。WebView で実行できない HTML フィルタ（##^）は引き続き破棄する。
        // 通信の条件（entity の domain・denyallow・to・redirect-rule・redirect の優先度）を受け付けた（adguard-jp 3→1、ubo-filters 14→7）。
        // 未知の資源（fuckadblock.js・google-ima.js）と、要素非表示の例外に entity を使う行は引き続き破棄する。
        // 単純な HTML フィルタ（##^script:has-text）は rmnt で代用して受け付けた（ubo-filters 7→6）。
        assertEquals("$name: 対応構文と拒否すべき構文の双方を見直してください", expectedDiscarded, discarded)
        println("EXCERPT $name active=${lines.size} discarded=$discarded")
    }
}
