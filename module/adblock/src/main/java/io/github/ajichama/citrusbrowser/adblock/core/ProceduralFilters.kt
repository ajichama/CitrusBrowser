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

package io.github.ajichama.citrusbrowser.adblock.core

/**
 * procedural の要素フィルタ（`:has-text()`、`:upward()`、`:style()` 等）の判定と検証。
 *
 * 仕様は uBO wiki の公開文書（Procedural-cosmetic-filters と Static-filter-syntax）だけを参照した独自実装。
 * 実行は文書開始時の固定スクリプト（assets/adblock/scriptlets.js）で行い、ここでは保存してよい行かを判定する。
 */
internal object ProceduralFilters {
    sealed interface Result {
        /** 通常の CSS として扱う。 */
        object Css : Result
        /** 未対応・壊れた構文。行ごと破棄する。 */
        object Invalid : Result
        data class Procedural(val body: String) : Result
    }

    private val operators = setOf(
        "has-text", "matches-attr", "matches-css", "matches-css-before", "matches-css-after", "matches-media",
        "matches-path", "matches-prop", "min-text-length", "others", "upward", "nth-ancestor", "watch-attr", "xpath",
        // ABP・AdGuard の書き方の別名（:-abp-has は :has、:-abp-contains・:contains は :has-text と同じ）。
        "-abp-has", "-abp-contains", "contains",
    )
    private val actions = setOf("remove", "remove-attr", "remove-class", "style")
    /** 引数が CSS なら通常の CSS、procedural を含めば procedural として扱う。 */
    private val cssCapable = setOf("has", "not")
    /** 文書に仕様がなく、意味を推測しないもの。 */
    private val undocumented = setOf("if", "if-not", "spath", "-abp-properties", "matches-property")

    private val forbiddenInStyle = listOf("url(", "image-set(", "/*", "*/", "\\", "//", "{", "}", "<", "expression(")

    fun classify(body: String, explicitProcedural: Boolean): Result {
        val text = body.trim()
        if (text.isEmpty() || text.length > 4096 || text.any { it == '\n' || it == '\r' }) return Result.Invalid
        // HTML フィルタ（##^）は WebView では実行できないため、procedural として扱わない。
        if (text.startsWith('^')) return Result.Invalid
        val tokens = scan(text) ?: return if (explicitProcedural || mentionsProcedural(text)) Result.Invalid else Result.Css
        if (tokens.any { it.name in undocumented }) return Result.Invalid
        val first = tokens.indexOfFirst { it.isProcedural() }
        if (first < 0) return Result.Css
        // 先頭の CSS 部分に、procedural を並べるための区切り（カンマ）を置かない（wiki: 連結は未対応）。
        if (hasTopLevelComma(text.substring(0, tokens[first].start))) return Result.Invalid
        val chain = tokens.drop(first)
        chain.forEachIndexed { index, token ->
            val known = token.name in operators || token.name in actions || token.name in cssCapable
            if (!known) return Result.Invalid
            if (token.name in actions && index != chain.lastIndex) return Result.Invalid
            if (token.name == "style" && !isSafeDeclarations(token.argument)) return Result.Invalid
            if (token.name in cssCapable && token.argument.isBlank()) return Result.Invalid
            if (token.name in cssCapable && hasProceduralOperator(token.argument) &&
                classify(token.argument, true) !is Result.Procedural) return Result.Invalid
        }
        // 演算子と演算子の間に CSS が挟まる場合は、その CSS を前の結果からの相対セレクタとして扱う。
        for (index in 0 until chain.lastIndex) {
            val between = text.substring(chain[index].end, chain[index + 1].start)
            if (hasTopLevelComma(between)) return Result.Invalid
        }
        if (hasTopLevelComma(text.substring(chain.last().end))) return Result.Invalid
        if (chain.last().name in actions && text.substring(chain.last().end).isNotBlank()) return Result.Invalid
        return Result.Procedural(text)
    }

    /** 括弧の対応が壊れていても、procedural の演算子名を含むか（CSS として誤って通さないため）。 */
    private fun mentionsProcedural(text: String): Boolean {
        val lower = text.lowercase()
        return (operators + actions + undocumented).any { ":$it(" in lower }
    }

    fun hasProceduralOperator(text: String): Boolean =
        scan(text.trim())?.any { it.isProcedural() } ?: false

    private class Token(val name: String, val argument: String, val start: Int, val end: Int) {
        fun isProcedural(): Boolean = name in operators || name in actions || name in undocumented ||
            (name in cssCapable && hasProceduralOperator(argument))
    }

    /** 最上位の `:名前(引数)` を順に取り出す。括弧・角括弧・引用符の対応が壊れていれば null。 */
    private fun scan(text: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '[' -> i = skipBracket(text, i) ?: return null
                '"', '\'' -> i = skipQuoted(text, i) ?: return null
                ':' -> {
                    val match = NAME.matchAt(text, i + 1)
                    if (match != null && text.getOrNull(match.range.last + 1) == '(') {
                        val open = match.range.last + 1
                        val close = closingParenthesis(text, open) ?: return null
                        tokens += Token(match.value.lowercase(), text.substring(open + 1, close), i, close + 1)
                        i = close + 1
                    } else i++
                }
                '(', ')' -> return null
                else -> i++
            }
        }
        return tokens
    }

    /** 引数の先頭が引用符なら引用符の範囲を飛ばし、`\` の次の文字は数えない。 */
    private fun closingParenthesis(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        var argumentStart = true
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> { i += 2; argumentStart = false; continue }
                argumentStart && (c == '"' || c == '\'') -> { i = skipQuoted(text, i) ?: return null; argumentStart = false; continue }
                c == '(' -> { depth++; argumentStart = depth == 1 }
                c == ')' -> { depth--; if (depth == 0) return i }
                c == ' ' && argumentStart -> Unit
                else -> argumentStart = false
            }
            i++
        }
        return null
    }

    private fun skipQuoted(text: String, start: Int): Int? {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            if (text[i] == '\\') { i += 2; continue }
            if (text[i] == quote) return i + 1
            i++
        }
        return null
    }

    private fun skipBracket(text: String, start: Int): Int? {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> { i += 2; continue }
                '"', '\'' -> { i = skipQuoted(text, i) ?: return null; continue }
                ']' -> return i + 1
            }
            i++
        }
        return null
    }

    private fun hasTopLevelComma(css: String): Boolean {
        var depth = 0
        var i = 0
        while (i < css.length) {
            when (css[i]) {
                '\\' -> i++
                '(', '[' -> depth++
                ')', ']' -> depth--
                ',' -> if (depth == 0) return true
            }
            i++
        }
        return false
    }

    private fun isSafeDeclarations(declarations: String): Boolean {
        val lower = declarations.lowercase()
        if (declarations.isBlank() || forbiddenInStyle.any { it in lower }) return false
        return declarations.split(';').filter { it.isNotBlank() }.all { DECLARATION.matches(it.trim()) }
    }

    private val NAME = Regex("-?[a-zA-Z][a-zA-Z-]*")
    private val DECLARATION = Regex("-{0,2}[a-zA-Z][a-zA-Z0-9-]*\\s*:\\s*[^:;]+")
}
